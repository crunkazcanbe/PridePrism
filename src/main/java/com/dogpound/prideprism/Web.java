package com.dogpound.prideprism;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Loader;

/**
 * The website's hooks. The site never talks to the game directly — both sides share the database
 * (contract: docs/website-hooks.md):
 *   pp_players  game writes: live stats, online, where they are, RealmCoin balance
 *   pp_links    site writes a code for a site account; the player types /link CODE in game
 *   pp_inbox    site (shop, forum rewards) writes orders/rewards; the game delivers them when the player is on.
 *               Orders are PAID IN-GAME through RealmCoin (never real money): money stays the game's to decide.
 */
public final class Web {
    /** per online-or-recent player: counters since the last flush */
    static final Map<UUID, Stat> stats = new ConcurrentHashMap<>();
    private static volatile Thread thread;
    private static volatile boolean running;

    static final class Stat {
        volatile String name;
        final Map<String, Integer> counts = new ConcurrentHashMap<>();
        volatile long onlineSince; // 0 = offline
        volatile int dim, x, y, z;
    }

    static void tables(Statement s) throws Exception {
        s.execute("CREATE TABLE IF NOT EXISTS pp_players (uuid CHAR(36) PRIMARY KEY, name VARCHAR(64) NOT NULL,"
            + "first_seen BIGINT, last_seen BIGINT, online TINYINT NOT NULL DEFAULT 0, play_seconds BIGINT NOT NULL DEFAULT 0,"
            + "blocks_broken BIGINT NOT NULL DEFAULT 0, blocks_placed BIGINT NOT NULL DEFAULT 0, deaths BIGINT NOT NULL DEFAULT 0,"
            + "kills BIGINT NOT NULL DEFAULT 0, crafted BIGINT NOT NULL DEFAULT 0, chats BIGINT NOT NULL DEFAULT 0,"
            + "things_used BIGINT NOT NULL DEFAULT 0, items_taken BIGINT NOT NULL DEFAULT 0, items_put BIGINT NOT NULL DEFAULT 0,"
            + "balance_cents BIGINT, dim INT, x INT, y INT, z INT, INDEX name (name)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        s.execute("CREATE TABLE IF NOT EXISTS pp_links (code VARCHAR(16) PRIMARY KEY, site_user VARCHAR(128) NOT NULL,"
            + "created BIGINT NOT NULL, uuid CHAR(36), name VARCHAR(64), linked_at BIGINT, INDEX uuid (uuid))"
            + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        s.execute("CREATE TABLE IF NOT EXISTS pp_inbox (id BIGINT AUTO_INCREMENT PRIMARY KEY, uuid CHAR(36) NOT NULL,"
            + "kind VARCHAR(12) NOT NULL, item VARCHAR(128), meta INT NOT NULL DEFAULT 0, amount INT NOT NULL DEFAULT 1,"
            + "nbt TEXT, price_cents BIGINT NOT NULL DEFAULT 0, reward_cents BIGINT NOT NULL DEFAULT 0, title VARCHAR(255),"
            + "source VARCHAR(64), created BIGINT NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'pending', status_msg VARCHAR(255),"
            + "done_at BIGINT, INDEX todo (status, uuid)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        if (RealmCoinBank.present()) RealmCoinBank.tables(s);
        Trains.tables(s);
    }

    /** called for every logged action: keeps the per-player counters the website shows */
    static void count(Action a) {
        if (a.uuid == null) return;
        Stat st = stats.computeIfAbsent(UUID.fromString(a.uuid), k -> new Stat());
        st.name = a.who;
        st.dim = a.dim; st.x = a.x; st.y = a.y; st.z = a.z;
        String col;
        switch (a.action) {
            case "break": col = "blocks_broken"; break;
            case "place": col = "blocks_placed"; break;
            case "death": col = "deaths"; break;
            case "kill": col = "kills"; break;
            case "craft": col = "crafted"; break;
            case "chat": col = "chats"; break;
            case "door": case "trapdoor": case "gate": case "button": case "lever": case "use": col = "things_used"; break;
            case "item-take": col = "items_taken"; break;
            case "item-put": col = "items_put"; break;
            case "join": st.onlineSince = System.currentTimeMillis(); return;
            case "leave": return; // play time is settled by flushStats before the player is dropped
            default: return;
        }
        st.counts.merge(col, a.item != null ? Math.max(1, a.amount) : 1, Integer::sum);
    }

    static void start() {
        if (RealmCoinBank.present()) RealmCoinBank.install();
        running = true;
        thread = new Thread(() -> {
            int tick = 0;
            while (running) {
                try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
                try {
                    deliver();
                    if (++tick % 6 == 0) { flushStats(); try (Connection c = Db.connect()) { Trains.flush(c); } } // every 30s
                    if (RealmCoinBank.present()) {
                        try (Connection c = Db.connect()) { RealmCoinBank.flush(c, FMLCommonHandler.instance().getMinecraftServerInstance(), tick); }
                    }
                } catch (Exception e) {
                    PridePrism.LOG.warn("[PridePrism] website hooks: " + e.getMessage());
                }
            }
        }, "PridePrism-web");
        thread.setDaemon(true);
        thread.start();
    }

    static void stop() {
        running = false;
        if (thread != null) thread.interrupt();
        for (UUID u : new ArrayList<>(stats.keySet())) left(u); // world closing: everyone's play time ends now
        try { flushStats(); } catch (Exception ignored) { }
    }

    static void left(UUID u) {
        Stat st = stats.get(u);
        if (st != null && st.onlineSince > 0) {
            st.counts.merge("play_seconds", (int) ((System.currentTimeMillis() - st.onlineSince) / 1000), Integer::sum);
            st.onlineSince = 0;
            st.counts.merge("__offline", 1, Integer::sum);
        }
    }

    /** Adds this round's counters onto each player's row (creating it the first time). */
    static synchronized void flushStats() throws Exception {
        if (stats.isEmpty()) return;
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        long now = System.currentTimeMillis();
        try (Connection c = Db.connect(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO pp_players (uuid,name,first_seen,last_seen,online,play_seconds,blocks_broken,blocks_placed,deaths,kills,"
                + "crafted,chats,things_used,items_taken,items_put,balance_cents,dim,x,y,z) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                + " ON DUPLICATE KEY UPDATE name=VALUES(name), last_seen=VALUES(last_seen), online=VALUES(online),"
                + " play_seconds=play_seconds+VALUES(play_seconds), blocks_broken=blocks_broken+VALUES(blocks_broken),"
                + " blocks_placed=blocks_placed+VALUES(blocks_placed), deaths=deaths+VALUES(deaths), kills=kills+VALUES(kills),"
                + " crafted=crafted+VALUES(crafted), chats=chats+VALUES(chats), things_used=things_used+VALUES(things_used),"
                + " items_taken=items_taken+VALUES(items_taken), items_put=items_put+VALUES(items_put),"
                + " balance_cents=COALESCE(VALUES(balance_cents), balance_cents), dim=VALUES(dim), x=VALUES(x), y=VALUES(y), z=VALUES(z)")) {
            List<UUID> gone = new ArrayList<>();
            for (Map.Entry<UUID, Stat> e : stats.entrySet()) {
                Stat st = e.getValue();
                if (st.name == null) continue;
                Map<String, Integer> n = new HashMap<>();
                for (String k : new ArrayList<>(st.counts.keySet())) n.put(k, st.counts.remove(k)); // take and reset
                boolean online = st.onlineSince > 0;
                long play = n.getOrDefault("play_seconds", 0);
                if (online) { play += (now - st.onlineSince) / 1000; st.onlineSince = now; }
                Long bal = null;
                try { if (RealmCoinBank.present() && server != null) bal = RealmCoinBank.balance(server, e.getKey(), st.name); }
                catch (Exception ignored) { } // world already closing: keep the last balance
                int i = 1;
                ps.setString(i++, e.getKey().toString()); ps.setString(i++, st.name); ps.setLong(i++, now); ps.setLong(i++, now);
                ps.setInt(i++, online ? 1 : 0); ps.setLong(i++, play);
                for (String col : new String[]{"blocks_broken", "blocks_placed", "deaths", "kills", "crafted", "chats", "things_used", "items_taken", "items_put"})
                    ps.setLong(i++, n.getOrDefault(col, 0));
                if (bal == null) ps.setNull(i++, java.sql.Types.BIGINT); else ps.setLong(i++, bal);
                ps.setInt(i++, st.dim); ps.setInt(i++, st.x); ps.setInt(i++, st.y); ps.setInt(i, st.z);
                ps.addBatch();
                if (!online) gone.add(e.getKey());
            }
            ps.executeBatch();
            for (UUID u : gone) stats.remove(u); // offline and written: nothing left to add
        }
    }

    /** /link CODE: ties the site account that made CODE (within 15 minutes) to this player. */
    static void link(EntityPlayerMP p, String code) {
        MinecraftServer server = p.getServer();
        UUID uuid = p.getUniqueID();
        String name = p.getName();
        new Thread(() -> {
            String msg;
            try (Connection c = Db.connect(); PreparedStatement ps = c.prepareStatement(
                    "UPDATE pp_links SET uuid=?, name=?, linked_at=? WHERE code=? AND uuid IS NULL AND created>=?")) {
                ps.setString(1, uuid.toString()); ps.setString(2, name); ps.setLong(3, System.currentTimeMillis());
                ps.setString(4, code.trim()); ps.setLong(5, System.currentTimeMillis() - 15 * 60_000L);
                if (ps.executeUpdate() == 1) {
                    String user = "";
                    try (PreparedStatement q = c.prepareStatement("SELECT site_user FROM pp_links WHERE code=?")) {
                        q.setString(1, code.trim());
                        try (ResultSet r = q.executeQuery()) { if (r.next()) user = r.getString(1); }
                    }
                    msg = "§a✔ Linked! §fYour website account §d" + user + "§f is now tied to " + name + ".";
                } else msg = "§cThat code isn't valid (codes last 15 minutes). Get a new one on the website.";
            } catch (Exception ex) {
                msg = "§cCan't reach the database right now: " + ex.getMessage();
            }
            String m = msg;
            server.addScheduledTask(() -> p.sendMessage(new TextComponentString(m)));
        }, "PridePrism-link").start();
    }

    /** Hands out pending orders/rewards to players who are online. Each row is claimed once (no double gifts). */
    private static void deliver() throws Exception {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;
        List<String> online = new ArrayList<>();
        for (EntityPlayerMP p : server.getPlayerList().getPlayers()) online.add(p.getUniqueID().toString());
        if (online.isEmpty()) return;
        List<Object[]> claimed = new ArrayList<>();
        try (Connection c = Db.connect()) {
            StringBuilder in = new StringBuilder();
            for (int i = 0; i < online.size(); i++) in.append(i == 0 ? "?" : ",?");
            List<Long> ids = new ArrayList<>();
            try (PreparedStatement q = c.prepareStatement("SELECT id FROM pp_inbox WHERE status='pending' AND uuid IN (" + in + ") ORDER BY id LIMIT 50")) {
                for (int i = 0; i < online.size(); i++) q.setString(i + 1, online.get(i));
                try (ResultSet r = q.executeQuery()) { while (r.next()) ids.add(r.getLong(1)); }
            }
            for (long id : ids) {
                try (PreparedStatement claim = c.prepareStatement("UPDATE pp_inbox SET status='delivering' WHERE id=? AND status='pending'")) {
                    claim.setLong(1, id);
                    if (claim.executeUpdate() != 1) continue; // someone else got it
                }
                try (PreparedStatement q = c.prepareStatement("SELECT uuid,kind,item,meta,amount,nbt,price_cents,reward_cents,title FROM pp_inbox WHERE id=?")) {
                    q.setLong(1, id);
                    try (ResultSet r = q.executeQuery()) {
                        if (r.next()) claimed.add(new Object[]{id, r.getString(1), r.getString(2), r.getString(3), r.getInt(4), r.getInt(5),
                            r.getString(6), r.getLong(7), r.getLong(8), r.getString(9)});
                    }
                }
            }
        }
        for (Object[] row : claimed) {
            java.util.concurrent.FutureTask<String[]> task = new java.util.concurrent.FutureTask<>(() -> give(server, row));
            server.addScheduledTask(task);
            String[] result = task.get(); // {status, message}
            try (Connection c = Db.connect(); PreparedStatement ps = c.prepareStatement(
                    "UPDATE pp_inbox SET status=?, status_msg=?, done_at=? WHERE id=?")) {
                ps.setString(1, result[0]); ps.setString(2, result[1]); ps.setLong(3, System.currentTimeMillis()); ps.setLong(4, (Long) row[0]);
                ps.executeUpdate();
            }
        }
    }

    /** On the game thread: charge (orders) or pay (coin rewards) through RealmCoin, then give the item. */
    private static String[] give(MinecraftServer server, Object[] row) {
        EntityPlayerMP p = server.getPlayerList().getPlayerByUUID(UUID.fromString((String) row[1]));
        if (p == null) return new String[]{"pending", "player went offline"}; // tried again next time they're on
        String kind = (String) row[2], itemId = (String) row[3], nbt = (String) row[6], title = (String) row[9];
        int meta = (Integer) row[4], amount = Math.max(1, Math.min(4096, (Integer) row[5]));
        long price = (Long) row[7], coins = (Long) row[8];
        ItemStack stack = ItemStack.EMPTY;
        if (itemId != null && !itemId.isEmpty()) {
            Item item = Item.REGISTRY.getObject(new ResourceLocation(itemId));
            if (item == null) return new String[]{"failed", "no such item in this pack: " + itemId};
            stack = new ItemStack(item, amount, meta);
            if (nbt != null && !nbt.isEmpty()) {
                try { stack.setTagCompound(JsonToNBT.getTagFromJson(nbt)); } catch (Exception e) { return new String[]{"failed", "bad item data: " + e.getMessage()}; }
            }
        }
        if ("order".equals(kind) && price > 0) {
            if (!RealmCoinBank.present()) return new String[]{"failed", "RealmCoin isn't installed, so nothing can be bought"};
            String paid = RealmCoinBank.charge(server, p.getUniqueID(), p.getName(), price, title);
            if (paid != null) {
                p.sendMessage(new TextComponentString("§cWebsite order \"" + title + "\" couldn't go through: " + paid));
                return new String[]{"failed", paid};
            }
        }
        if (coins > 0) {
            if (!RealmCoinBank.present()) return new String[]{"failed", "RealmCoin isn't installed"};
            RealmCoinBank.pay(server, p.getUniqueID(), p.getName(), coins, title);
        }
        if (!stack.isEmpty()) {
            ItemStack left = stack.copy();
            if (!p.inventory.addItemStackToInventory(left) && !left.isEmpty()) p.dropItem(left, false); // full bag: at their feet
        }
        String what = title != null ? title : !stack.isEmpty() ? stack.getCount() + "x " + stack.getDisplayName() : "coins";
        p.sendMessage(new TextComponentString("order".equals(kind)
            ? "§d✦ Your website order arrived: §f" + what
            : "§d✦ Reward from the website: §f" + what + " §d❤"));
        Db.log(Action.at("order".equals(kind) ? "web-order" : "web-reward", p.world, p.getPosition(), null, p)
            .extra(what + (price > 0 ? " for " + price + " cents" : "")));
        return new String[]{"done", what};
    }
}
