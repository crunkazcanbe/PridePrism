package com.dogpound.prideprism;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * MySQL/MariaDB storage. The game thread only drops actions into a queue; one background thread writes them
 * in batches every second, so logging never makes the game wait on the database.
 */
public final class Db {
    private static final ConcurrentLinkedQueue<Action> queue = new ConcurrentLinkedQueue<>();
    private static volatile Thread writer;
    private static volatile boolean running;
    public static volatile String status = "not connected";

    static final String TABLE = "prideprism_actions";

    private Db() {}

    /** The bundled MariaDB driver, called directly: DriverManager can't see drivers loaded by a mod classloader. */
    public static Connection connect() throws Exception {
        Properties p = new Properties();
        p.setProperty("user", PridePrism.dbUser);
        p.setProperty("password", PridePrism.dbPassword);
        Connection c = new org.mariadb.jdbc.Driver().connect(PridePrism.dbUrl, p);
        if (c == null) throw new IllegalArgumentException("not a MySQL/MariaDB url: " + PridePrism.dbUrl);
        return c;
    }

    static void start() {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, t BIGINT NOT NULL, dim INT NOT NULL, x INT, y INT, z INT,"
                + "action VARCHAR(24) NOT NULL, who VARCHAR(64) NOT NULL, uuid CHAR(36),"
                + "block_old VARCHAR(128), meta_old SMALLINT, block_new VARCHAR(128), meta_new SMALLINT,"
                + "nbt_old MEDIUMBLOB, nbt_new MEDIUMBLOB, item VARCHAR(128), item_meta SMALLINT, amount INT,"
                + "extra VARCHAR(512), rolled_back TINYINT NOT NULL DEFAULT 0,"
                + "INDEX loc (dim, x, z, y), INDEX t (t), INDEX who (who), INDEX act (action))"
                + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            Web.tables(s);
            Rollback.tables(s);
            status = "connected";
        } catch (Exception e) {
            status = "can't reach the database: " + e.getMessage();
            PridePrism.LOG.error("[PridePrism] " + status + " (logging keeps queuing in memory and retries)");
        }
        running = true;
        writer = new Thread(Db::writeLoop, "PridePrism-writer");
        writer.setDaemon(true);
        writer.start();
    }

    static void stop() {
        running = false;
        Thread w = writer;
        if (w != null) { w.interrupt(); try { w.join(10000); } catch (InterruptedException ignored) { } }
        flush(); // whatever is left when the world closes
    }

    public static void log(Action a) {
        Web.count(a); // website stats
        if (queue.size() < 2_000_000) queue.add(a); // ponytail: past 2M unwritten actions (DB down for hours) new ones drop
    }

    private static void writeLoop() {
        while (running) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            flush();
        }
    }

    static synchronized void flush() {
        if (queue.isEmpty()) return;
        List<Action> batch = new ArrayList<>();
        for (Action a; batch.size() < 5000 && (a = queue.poll()) != null; ) batch.add(a);
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("INSERT INTO " + TABLE
                + " (t,dim,x,y,z,action,who,uuid,block_old,meta_old,block_new,meta_new,nbt_old,nbt_new,item,item_meta,amount,extra)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (Action a : batch) { a.bind(ps); ps.addBatch(); }
            ps.executeBatch();
            status = "connected";
        } catch (Exception e) {
            status = "can't reach the database: " + e.getMessage();
            for (Action a : batch) queue.add(a); // keep them; next second tries again
        }
    }

    /** Newest-first actions matching a filter. The where-clause is built only from our own fixed pieces + bound values. */
    public static List<Action> lookup(Filter f, int limit) throws Exception {
        flush(); // include what happened in the last second
        List<Object> args = new ArrayList<>();
        String where = f.sql(args);
        List<Action> out = new ArrayList<>();
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM " + TABLE + " WHERE " + where + " ORDER BY id DESC LIMIT " + Math.max(1, Math.min(limit, 100000)))) {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(Action.read(rs)); }
        }
        return out;
    }

    /** many entries at once (500 per query) — rollback of a big selection */
    public static List<Action> byIds(List<Long> ids) throws Exception {
        flush();
        List<Action> out = new ArrayList<>();
        try (Connection c = connect()) {
            for (int i = 0; i < ids.size(); i += 500) {
                List<Long> part = ids.subList(i, Math.min(ids.size(), i + 500));
                StringBuilder q = new StringBuilder("SELECT * FROM " + TABLE + " WHERE id IN (");
                for (int k = 0; k < part.size(); k++) q.append(k == 0 ? "?" : ",?");
                try (PreparedStatement ps = c.prepareStatement(q.append(")").toString())) {
                    for (int k = 0; k < part.size(); k++) ps.setLong(k + 1, part.get(k));
                    try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(Action.read(rs)); }
                }
            }
        }
        return out;
    }

    public static Action byId(long id) throws Exception {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("SELECT * FROM " + TABLE + " WHERE id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Action.read(rs) : null; }
        }
    }

    public static void markRolledBack(List<Action> actions, boolean rolled) throws Exception {
        if (actions.isEmpty()) return;
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(
                "UPDATE " + TABLE + " SET rolled_back=? WHERE id=?")) {
            for (Action a : actions) { ps.setInt(1, rolled ? 1 : 0); ps.setLong(2, a.id); ps.addBatch(); }
            ps.executeBatch();
        }
    }

    public static int queued() {
        return queue.size();
    }
}
