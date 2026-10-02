package com.dogpound.prideprism;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.dogpound.realmcoin.bank.Account;
import com.dogpound.realmcoin.bank.Ledger;
import com.dogpound.realmcoin.market.Worth;
import net.minecraft.server.MinecraftServer;

/** Only touched when RealmCoin is installed (see RealmCoinBank). Uses RealmCoin's own rules: no overdraft, frozen stays frozen, taxed. */
final class RealmCoinLive {
    static final String SHOP = "b:website-shop";
    private static final ConcurrentLinkedQueue<Object[]> money = new ConcurrentLinkedQueue<>(); // {tx, fromName, toName}
    private static volatile boolean installed;

    private RealmCoinLive() {}

    private static Ledger ledger(MinecraftServer s) {
        return Ledger.get(s.getWorld(0));
    }

    static String charge(MinecraftServer s, UUID u, String name, long cents, String what) {
        Ledger l = ledger(s);
        l.personal(u, name, 0);
        if (l.get(SHOP) == null) l.create(SHOP, Account.Kind.BUSINESS, "Website Shop", null); // every sale shows up in RealmCoin's books
        Ledger.Result r = l.transfer("p:" + u, SHOP, cents, "website-order", what == null ? "website order" : what);
        switch (r) {
            case OK: return null;
            case INSUFFICIENT: return "not enough coins";
            case FROZEN: return "that account is frozen";
            default: return "payment failed (" + r + ")";
        }
    }

    static void pay(MinecraftServer s, UUID u, String name, long cents, String what) {
        Ledger l = ledger(s);
        l.personal(u, name, 0);
        l.mint("p:" + u, cents, "website-reward", what == null ? "website reward" : what);
    }

    static long balance(MinecraftServer s, UUID u, String name) {
        Account a = ledger(s).get("p:" + u);
        return a == null ? 0 : a.balance;
    }

    /** Hear every coin movement: into the action log (inspect/lookup) and pp_money (website). */
    static void install() {
        if (installed) return;
        installed = true;
        Ledger.LISTENERS.add((ledger, tx) -> {
            Account from = tx.from == null ? null : ledger.get(tx.from), to = tx.to == null ? null : ledger.get(tx.to);
            String fromName = tx.from == null ? "(new money)" : from != null ? from.name : tx.from;
            String toName = tx.to == null ? "(destroyed)" : to != null ? to.name : tx.to;
            money.add(new Object[]{tx, fromName, toName});
            Action a = new Action();
            a.time = tx.time; a.action = "money"; a.who = fromName;
            if (from != null && from.kind == Account.Kind.PERSONAL && !from.owners.isEmpty()) a.uuid = from.owners.iterator().next().toString();
            a.amount = (int) Math.min(Integer.MAX_VALUE, tx.amount);
            a.extra("" + tx.type + ": " + fromName + " -> " + toName + " " + String.format("%.2f", tx.amount / 100.0) + (tx.memo == null ? "" : " (" + tx.memo + ")"));
            Db.log(a); // dim/x/y/z stay 0: money has no place — lookups use a:money
        });
    }

    static void tables(java.sql.Statement s) throws Exception {
        s.execute("CREATE TABLE IF NOT EXISTS pp_money (tx_id BIGINT PRIMARY KEY, t BIGINT NOT NULL, from_acct VARCHAR(96), from_name VARCHAR(128),"
            + "to_acct VARCHAR(96), to_name VARCHAR(128), cents BIGINT NOT NULL, type VARCHAR(48), memo VARCHAR(255),"
            + "INDEX t (t), INDEX f (from_acct), INDEX tt (to_acct)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        s.execute("CREATE TABLE IF NOT EXISTS pp_accounts (id VARCHAR(96) PRIMARY KEY, kind VARCHAR(16), name VARCHAR(128),"
            + "owners TEXT, balance_cents BIGINT, frozen TINYINT, updated BIGINT) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        s.execute("CREATE TABLE IF NOT EXISTS pp_prices (item_key VARCHAR(160) PRIMARY KEY, cents BIGINT NOT NULL, updated BIGINT)"
            + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    /** every 5s: new transactions; every minute: all accounts; every 10 minutes: the price list */
    static void flush(Connection c, MinecraftServer s, int tick) throws Exception {
        if (!money.isEmpty()) {
            try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO pp_money (tx_id,t,from_acct,from_name,to_acct,to_name,cents,type,memo)"
                    + " VALUES (?,?,?,?,?,?,?,?,?)")) {
                for (Object[] m; (m = money.poll()) != null; ) {
                    Ledger.Tx tx = (Ledger.Tx) m[0];
                    ps.setLong(1, tx.id); ps.setLong(2, tx.time); ps.setString(3, tx.from); ps.setString(4, (String) m[1]);
                    ps.setString(5, tx.to); ps.setString(6, (String) m[2]); ps.setLong(7, tx.amount); ps.setString(8, tx.type);
                    ps.setString(9, tx.memo == null ? null : tx.memo.length() > 250 ? tx.memo.substring(0, 250) : tx.memo);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        if (s == null) return;
        long now = System.currentTimeMillis();
        if (tick % 12 == 0) {
            try (PreparedStatement ps = c.prepareStatement("REPLACE INTO pp_accounts (id,kind,name,owners,balance_cents,frozen,updated) VALUES (?,?,?,?,?,?,?)")) {
                for (Account a : ledger(s).all()) {
                    ps.setString(1, a.id); ps.setString(2, a.kind.name()); ps.setString(3, a.name);
                    ps.setString(4, a.owners.toString()); ps.setLong(5, a.balance); ps.setInt(6, a.frozen ? 1 : 0); ps.setLong(7, now);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        if (tick % 120 == 1) {
            try (PreparedStatement ps = c.prepareStatement("REPLACE INTO pp_prices (item_key,cents,updated) VALUES (?,?,?)")) {
                int n = 0;
                for (Map.Entry<String, Long> e : Worth.all().entrySet()) {
                    ps.setString(1, e.getKey()); ps.setLong(2, e.getValue()); ps.setLong(3, now);
                    ps.addBatch();
                    if (++n % 2000 == 0) ps.executeBatch();
                }
                ps.executeBatch();
            }
        }
    }
}
