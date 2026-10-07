package com.dogpound.prideprism;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

/**
 * The rollback engine (requested feature).
 *
 *  plan()  — the admin picked entries (or a search): sort them by kind, check each against the world NOW
 *            (conflict = someone changed that spot since), count everything, and list spots for the in-world preview.
 *  start() — run a plan as a BATCH: a few hundred changes per tick with a progress bar, saved to history so
 *            the whole rollback can itself be undone later (undoBatch).
 *
 * What it can put back: blocks (+ chest/machine contents), chest thefts (items go back in, and are taken back
 * from the thief if they still carry them), items put into chests, a player's whole inventory from a death,
 * and killed pets / named animals / villagers (respawned from what they were).
 */
public final class Rollback {
    private Rollback() {}

    static final String BATCHES = "prideprism_batches";
    static final int PER_TICK = 300;

    static final List<String> BLOCK = Arrays.asList("break", "place", "explode", "fluid", "trample", "pour", "flow", "ignite", "burn");
    static final List<String> CHEST = Arrays.asList("item-take", "item-put");

    /** what the admin switched on in the planner */
    public static final class Options {
        public boolean blocks = true, chests = true, deaths = true, pets = true, fromThieves = true, force = false;
        int bits() { return (blocks ? 1 : 0) | (chests ? 2 : 0) | (deaths ? 4 : 0) | (pets ? 8 : 0) | (fromThieves ? 16 : 0) | (force ? 32 : 0); }
        static Options of(int b) {
            Options o = new Options();
            o.blocks = (b & 1) != 0; o.chests = (b & 2) != 0; o.deaths = (b & 4) != 0; o.pets = (b & 8) != 0; o.fromThieves = (b & 16) != 0; o.force = (b & 32) != 0;
            return o;
        }
    }

    /** one planned change */
    static final class Step {
        final Action a;
        final String kind;        // block, chest, death, pet
        boolean conflict;
        Step(Action a, String kind) { this.a = a; this.kind = kind; }
    }

    public static final class Plan {
        final boolean rollback;
        final Options opts;
        final List<Step> steps = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();
        int conflicts;
        long made = System.currentTimeMillis();
        Plan(boolean rollback, Options opts) { this.rollback = rollback; this.opts = opts; }

        List<Step> todo() {
            List<Step> out = new ArrayList<>();
            for (Step s : steps) if (!s.conflict || opts.force) out.add(s);
            return out;
        }

        /** the planner's summary, line by line */
        List<String> summary() {
            List<String> l = new ArrayList<>();
            Map<String, Integer> kinds = new TreeMap<>(), who = new TreeMap<>(), things = new HashMap<>();
            for (Step s : steps) {
                kinds.merge(s.kind, 1, Integer::sum);
                who.merge(s.a.who, 1, Integer::sum);
                String t = s.a.item != null ? s.a.item : s.a.blockOld != null ? s.a.blockOld : s.a.blockNew;
                if (t != null) things.merge(t, 1, Integer::sum);
            }
            l.add((rollback ? "§dRoll back " : "§aRestore ") + todo().size() + " of " + steps.size() + " changes");
            StringBuilder k = new StringBuilder("§7");
            kinds.forEach((a, n) -> k.append(n).append(' ').append(a).append(n == 1 ? "  " : "s  "));
            l.add(k.toString());
            StringBuilder w = new StringBuilder("§7by ");
            who.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(6).forEach(e -> w.append("§f").append(e.getKey()).append(" §8").append(e.getValue()).append("  "));
            l.add(w.toString());
            StringBuilder t = new StringBuilder("§7most: ");
            things.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(5)
                .forEach(e -> t.append("§f").append(e.getKey().replace("minecraft:", "")).append(" §8×").append(e.getValue()).append("  "));
            l.add(t.toString());
            if (conflicts > 0) l.add("§e⚠ " + conflicts + " spots changed since — " + (opts.force ? "§cthey'll be overwritten" : "they'll be skipped (switch on Force to overwrite)"));
            if (!skipped.isEmpty()) l.add("§8" + skipped.size() + " can't be " + (rollback ? "rolled back" : "restored") + " (" + String.join(", ", new TreeSet<>(skipped)) + ")");
            return l;
        }
    }

    private static final Map<UUID, Plan> PLANS = new HashMap<>();

    // ---------------- planning ----------------
    static Plan plan(MinecraftServer server, List<Action> acts, boolean rollback, Options o) {
        Plan p = new Plan(rollback, o);
        acts.sort(Comparator.comparingLong((Action a) -> a.id));
        if (rollback) Collections.reverse(acts);              // undo newest first; redo oldest first
        // a lava flood: take out every source/flow/fire FIRST (or it keeps flowing), then put the burnt blocks back
        if (rollback) acts.sort(Comparator.comparingInt((Action a) -> a.action.equals("pour") ? 0 : a.action.equals("flow") || a.action.equals("ignite") ? 1 : 2));
        Set<String> seenBlock = new HashSet<>();
        for (Action a : acts) {
            if (a.rolledBack == rollback) { p.skipped.add("already " + (rollback ? "undone" : "in place")); continue; }
            String kind = BLOCK.contains(a.action) ? "block" : CHEST.contains(a.action) ? "chest"
                : a.action.equals("death") && a.nbtOld != null ? "death" : a.action.equals("kill") && a.nbtOld != null ? "pet" : null;
            if (kind == null) { p.skipped.add(a.action); continue; }
            if (kind.equals("block") && !o.blocks || kind.equals("chest") && !o.chests || kind.equals("death") && !o.deaths || kind.equals("pet") && !o.pets) continue;
            Step s = new Step(a, kind);
            WorldServer w = server.getWorld(a.dim);
            if (kind.equals("block") && w != null) {
                // only the NEWEST planned change at a spot is checked against the world; older ones stack on top of it
                if (seenBlock.add(a.dim + ":" + a.x + ":" + a.y + ":" + a.z)) {
                    boolean loose = a.action.equals("pour") || a.action.equals("flow") || a.action.equals("ignite") || a.action.equals("burn");
                    s.conflict = loose ? !looseOk(w.getBlockState(a.pos()), a, rollback) : !matches(w.getBlockState(a.pos()), expectedNow(a, rollback));
                }
            }
            if (s.conflict) p.conflicts++;
            p.steps.add(s);
        }
        return p;
    }

    /** the block that SHOULD be there now if nothing happened since this entry */
    /** fluids change level and flowing/still ids as they spread, fire comes and goes: judge those loosely */
    private static boolean looseOk(IBlockState now, Action a, boolean rollback) {
        boolean spill = a.action.equals("pour") || a.action.equals("flow") || a.action.equals("ignite");
        boolean gone = now.getMaterial() == net.minecraft.block.material.Material.AIR || now.getBlock() instanceof net.minecraft.block.BlockFire;
        if (rollback) return spill ? GriefTracker.fluid(now) || gone : gone;   // burn: the spot should still be empty
        return true;
    }

    private static String expectedNow(Action a, boolean rollback) {
        boolean removed = a.action.equals("break") || a.action.equals("explode");
        if (rollback) return removed ? "minecraft:air" : a.blockNew;
        return removed ? a.blockOld : a.blockOld;           // restoring: the spot should look like before the change
    }

    private static boolean matches(IBlockState now, String id) {
        if (id == null) return true;
        return (now.getBlock().getRegistryName() + "").equals(id);
    }

    static void keep(EntityPlayerMP admin, Plan p) { if (p == null) PLANS.remove(admin.getUniqueID()); else PLANS.put(admin.getUniqueID(), p); }
    static Plan kept(EntityPlayerMP admin) {
        Plan p = PLANS.get(admin.getUniqueID());
        return p != null && System.currentTimeMillis() - p.made < 15 * 60_000 ? p : null;
    }

    /** spots for the in-world preview: x,y,z,dim,colour (0 appears, 1 disappears, 2 conflict) */
    static int[][] preview(Plan p, int max) {
        List<int[]> out = new ArrayList<>();
        for (Step s : p.steps) {
            if (!s.kind.equals("block") && !s.kind.equals("chest")) continue;
            boolean appears = s.kind.equals("chest") || ((s.a.action.equals("break") || s.a.action.equals("explode") || s.a.action.equals("trample")) == p.rollback);
            out.add(new int[]{s.a.x, s.a.y, s.a.z, s.a.dim, s.conflict ? 2 : appears ? 0 : 1});
            if (out.size() >= max) break;
        }
        return out.toArray(new int[0][]);
    }

    // ---------------- running a batch ----------------
    static final class Job {
        final long batch;
        final EntityPlayerMP admin;
        final Plan plan;
        final List<Step> todo;
        final List<Action> done = new ArrayList<>();
        int i, ok, failed;
        Job(long batch, EntityPlayerMP admin, Plan plan) { this.batch = batch; this.admin = admin; this.plan = plan; this.todo = plan.todo(); }
    }

    private static final Deque<Job> JOBS = new ArrayDeque<>();
    private static boolean registered;

    static void start(EntityPlayerMP admin, Plan p) {
        if (!registered) { MinecraftForge.EVENT_BUS.register(new Rollback.Ticker()); registered = true; }
        long batch = saveBatch(admin, p);
        JOBS.add(new Job(batch, admin, p));
        admin.sendMessage(new TextComponentString("§d✦ " + (p.rollback ? "Rolling back " : "Restoring ") + p.todo().size() + " changes"
            + (batch > 0 ? " §8(batch #" + batch + " — /pp batch undo " + batch + " reverses it)" : "")));
    }

    public static final class Ticker {
        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent e) {
            if (e.phase != TickEvent.Phase.END || JOBS.isEmpty()) return;
            Job j = JOBS.peek();
            MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
            for (int n = 0; n < PER_TICK && j.i < j.todo.size(); n++, j.i++) {
                Step s = j.todo.get(j.i);
                boolean ok;
                try { ok = apply(server, s, j.plan.rollback, j.plan.opts); } catch (Exception ex) { ok = false; }
                if (ok) { j.ok++; j.done.add(s.a); } else j.failed++;
            }
            if (j.admin.connection != null && (j.i % (PER_TICK * 5) == 0 || j.i >= j.todo.size()))
                j.admin.sendStatusMessage(new TextComponentString(bar(j.i, j.todo.size())), true); // above the hotbar
            if (j.i < j.todo.size()) return;
            JOBS.poll();
            j.admin.sendMessage(new TextComponentString("§d✦ Done: " + j.ok + (j.plan.rollback ? " rolled back" : " restored") + (j.failed > 0 ? ", §e" + j.failed + " couldn't be (block/item gone from the pack, chest full, player offline…)" : "")));
            List<Action> done = j.done;
            boolean rb = j.plan.rollback;
            new Thread(() -> { try { Db.markRolledBack(done, rb); } catch (Exception ignored) { } }, "PridePrism-mark").start();
            Db.log(Action.at(rb ? "rollback" : "restore", j.admin.world, j.admin.getPosition(), null, j.admin)
                .extra((rb ? "rolled back " : "restored ") + j.ok + " changes, batch #" + j.batch));
        }
    }

    private static String bar(int i, int n) {
        int w = 20, f = n == 0 ? w : i * w / n;
        StringBuilder b = new StringBuilder("§d✦ ");
        for (int k = 0; k < w; k++) b.append(k < f ? "§d█" : "§8█");
        return b.append(" §f").append(i).append('/').append(n).toString();
    }

    // ---------------- doing one change ----------------
    static boolean apply(MinecraftServer server, Step s, boolean rollback, Options o) {
        Action a = s.a;
        WorldServer w = server.getWorld(a.dim);
        switch (s.kind) {
            case "block": return w != null && block(w, a, rollback);
            case "chest": return w != null && chest(server, w, a, rollback, o.fromThieves);
            case "death": return rollback && deathInventory(server, a);
            case "pet": return rollback && w != null && pet(w, a);
            default: return false;
        }
    }

    @SuppressWarnings("deprecation")
    static boolean block(World w, Action a, boolean rollback) {
        boolean removed = a.action.equals("break") || a.action.equals("explode") || a.action.equals("trample");
        String id = rollback ? a.blockOld : (removed ? (a.action.equals("trample") ? "minecraft:dirt" : "minecraft:air") : a.blockNew);
        int meta = rollback ? a.metaOld : removed ? 0 : a.metaNew;
        if (id == null || !Block.REGISTRY.containsKey(new ResourceLocation(id))) return false;
        BlockPos pos = a.pos();
        w.setBlockState(pos, Block.REGISTRY.getObject(new ResourceLocation(id)).getStateFromMeta(meta), 3);
        NBTTagCompound nbt = rollback ? a.nbtOld : (removed ? null : a.nbtNew);
        if (nbt != null) {
            TileEntity te = w.getTileEntity(pos);
            if (te != null) {
                nbt = nbt.copy();
                nbt.setInteger("x", pos.getX()); nbt.setInteger("y", pos.getY()); nbt.setInteger("z", pos.getZ());
                te.readFromNBT(nbt);
                te.markDirty();
            }
        }
        return true;
    }

    /** item-take rolled back = back into the chest (and out of the thief's pockets); item-put rolled back = out again */
    static boolean chest(MinecraftServer server, World w, Action a, boolean rollback, boolean fromThief) {
        Item item = a.item == null ? null : Item.getByNameOrId(a.item);
        if (item == null || a.amount <= 0) return false;
        TileEntity te = w.getTileEntity(a.pos());
        IItemHandler inv = te == null ? null : te.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null)
            ? te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null)
            : te.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, EnumFacing.UP) ? te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, EnumFacing.UP) : null;
        if (inv == null) return false;
        boolean intoChest = a.action.equals("item-take") == rollback;
        EntityPlayerMP who = a.uuid == null ? null : server.getPlayerList().getPlayerByUUID(UUID.fromString(a.uuid));
        if (intoChest) {
            if (fromThief && who != null) take(who, item, a.itemMeta, a.amount);  // they took it: it leaves their pockets
            ItemStack left = ItemHandlerHelper.insertItemStacked(inv, new ItemStack(item, a.amount, a.itemMeta), false);
            if (!left.isEmpty()) net.minecraft.inventory.InventoryHelper.spawnItemStack(w, a.x + 0.5, a.y + 1, a.z + 0.5, left); // chest full: drop it on top
            return true;
        }
        int need = a.amount;
        for (int i = 0; i < inv.getSlots() && need > 0; i++) {
            ItemStack in = inv.getStackInSlot(i);
            if (in.getItem() == item && in.getMetadata() == a.itemMeta) need -= inv.extractItem(i, need, false).getCount();
        }
        int got = a.amount - need;
        if (got > 0 && fromThief && who != null) ItemHandlerHelper.giveItemToPlayer(who, new ItemStack(item, got, a.itemMeta)); // it was theirs
        return got > 0;
    }

    private static void take(EntityPlayerMP p, Item item, int meta, int amount) {
        for (int i = 0; i < p.inventory.getSizeInventory() && amount > 0; i++) {
            ItemStack s = p.inventory.getStackInSlot(i);
            if (s.getItem() == item && s.getMetadata() == meta) { int n = Math.min(amount, s.getCount()); s.shrink(n); amount -= n; }
        }
        p.inventoryContainer.detectAndSendChanges();
    }

    /** give a dead player back everything they carried (saved at the moment of death) */
    static boolean deathInventory(MinecraftServer server, Action a) {
        String victim = a.nbtOld.hasKey("victim") ? a.nbtOld.getString("victim") : a.uuid;
        EntityPlayerMP p = victim == null ? null : server.getPlayerList().getPlayerByUUID(UUID.fromString(victim));
        if (p == null || !a.nbtOld.hasKey("inv")) return false;                      // must be online to get it
        NBTTagList list = a.nbtOld.getTagList("inv", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            ItemStack s = new ItemStack(list.getCompoundTagAt(i));
            if (!s.isEmpty()) ItemHandlerHelper.giveItemToPlayer(p, s);
        }
        if (a.nbtOld.hasKey("xp")) p.addExperience(a.nbtOld.getInteger("xp"));
        p.sendMessage(new TextComponentString("§d✦ An admin gave you back what you had when you died."));
        return true;
    }

    /** bring a killed pet / named mob / villager back, exactly as it was (owner, name, gear) */
    static boolean pet(World w, Action a) {
        NBTTagCompound nbt = a.nbtOld.copy();
        nbt.removeTag("UUIDMost"); nbt.removeTag("UUIDLeast");                           // a fresh one: never a duplicate
        nbt.setFloat("Health", Math.max(1, nbt.getFloat("Health") <= 0 ? 10 : nbt.getFloat("Health")));
        nbt.setShort("DeathTime", (short) 0);
        Entity e = EntityList.createEntityFromNBT(nbt, w);
        if (e == null) return false;
        e.setPosition(a.x + 0.5, a.y, a.z + 0.5);
        if (e instanceof net.minecraft.entity.EntityLivingBase) ((net.minecraft.entity.EntityLivingBase) e).setHealth(((net.minecraft.entity.EntityLivingBase) e).getMaxHealth());
        return w.spawnEntity(e);
    }

    // ---------------- history: every rollback is a batch that can be undone ----------------
    static void tables(Statement s) throws Exception {
        s.execute("CREATE TABLE IF NOT EXISTS " + BATCHES + " (id BIGINT AUTO_INCREMENT PRIMARY KEY, t BIGINT NOT NULL, by_who VARCHAR(64) NOT NULL,"
            + " rollback TINYINT NOT NULL, opts INT NOT NULL, count INT NOT NULL, summary VARCHAR(512), ids MEDIUMTEXT NOT NULL, undone TINYINT NOT NULL DEFAULT 0)"
            + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    private static long saveBatch(EntityPlayerMP admin, Plan p) {
        StringBuilder ids = new StringBuilder();
        for (Step s : p.todo()) { if (ids.length() > 0) ids.append(','); ids.append(s.a.id); }
        try (Connection c = Db.connect(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO " + BATCHES + " (t, by_who, rollback, opts, count, summary, ids) VALUES (?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, System.currentTimeMillis()); ps.setString(2, admin.getName()); ps.setInt(3, p.rollback ? 1 : 0);
            ps.setInt(4, p.opts.bits()); ps.setInt(5, p.todo().size());
            String sum = String.join(" | ", p.summary()).replaceAll("§.", "");
            ps.setString(6, sum.length() > 500 ? sum.substring(0, 500) : sum); ps.setString(7, ids.toString());
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { return rs.next() ? rs.getLong(1) : -1; }
        } catch (Exception e) {
            PridePrism.LOG.warn("[PridePrism] couldn't save rollback history: " + e);
            return -1;
        }
    }

    /** the last rollbacks, newest first, one line each */
    static List<String> history(int limit) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = Db.connect(); PreparedStatement ps = c.prepareStatement("SELECT id, t, by_who, rollback, count, undone, summary FROM " + BATCHES + " ORDER BY id DESC LIMIT " + limit);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add("§d#" + rs.getLong(1) + " §8" + Inspect.ago(rs.getLong(2)) + " §f" + rs.getString(3) + (rs.getInt(4) == 1 ? " §drolled back " : " §arestored ")
                + rs.getInt(5) + (rs.getInt(6) == 1 ? " §8(reversed)" : ""));
        }
        return out;
    }

    /** reverse a whole batch: what it rolled back gets restored (or the other way round) */
    static void undoBatch(EntityPlayerMP admin, long id) {
        MinecraftServer server = admin.getServer();
        new Thread(() -> {
            try (Connection c = Db.connect(); PreparedStatement ps = c.prepareStatement("SELECT rollback, opts, ids, undone FROM " + BATCHES + " WHERE id=?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) { server.addScheduledTask(() -> admin.sendMessage(new TextComponentString("§cNo batch #" + id))); return; }
                    if (rs.getInt(4) == 1) { server.addScheduledTask(() -> admin.sendMessage(new TextComponentString("§eBatch #" + id + " was already reversed."))); return; }
                    boolean wasRollback = rs.getInt(1) == 1;
                    Options o = Options.of(rs.getInt(2));
                    o.force = true;                                   // it's our own change we're reversing
                    List<Long> ids = new ArrayList<>();
                    for (String s : rs.getString(3).split(",")) if (!s.isEmpty()) ids.add(Long.parseLong(s));
                    List<Action> acts = Db.byIds(ids);
                    try (PreparedStatement up = c.prepareStatement("UPDATE " + BATCHES + " SET undone=1 WHERE id=?")) { up.setLong(1, id); up.executeUpdate(); }
                    server.addScheduledTask(() -> start(admin, plan(server, acts, !wasRollback, o)));
                }
            } catch (Exception e) {
                server.addScheduledTask(() -> admin.sendMessage(new TextComponentString("§cCan't reach the database: " + e.getMessage())));
            }
        }, "PridePrism-unbatch").start();
    }
}
