package com.dogpound.prideprism;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * The data behind the graph ball (requested feature). A path picks how deep you are:
 *   []                       -> categories (Break, Combat, Menus...)
 *   [cat]                    -> the actions in it
 *   [cat, action]            -> the players who did it
 *   [cat, action, who]       -> their latest entries, one dot each (with the full story)
 * Every node: {key, label, count, detail}.
 */
public final class Graph {
    private Graph() {}

    static final int MAX_DOTS = 90;

    public static List<String[]> children(List<String> path) throws Exception {
        Db.flush();
        List<String[]> out = new ArrayList<>();
        try (Connection c = Db.connect()) {
            if (path.isEmpty()) {
                Map<String, Long> cats = new LinkedHashMap<>();
                for (String k : Kinds.ALL.keySet()) cats.put(k, 0L);
                try (PreparedStatement ps = c.prepareStatement("SELECT action, COUNT(*) FROM " + Db.TABLE + " GROUP BY action"); ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) cats.merge(Kinds.of(rs.getString(1)), rs.getLong(2), Long::sum);
                }
                cats.forEach((k, n) -> { if (n > 0) out.add(new String[]{k, k, n + "", String.join(", ", Kinds.ALL.getOrDefault(k, new String[]{"anything new"}))}); });
            } else if (path.size() == 1) {
                try (PreparedStatement ps = c.prepareStatement("SELECT action, COUNT(*) n, COUNT(DISTINCT who) w FROM " + Db.TABLE + " GROUP BY action ORDER BY n DESC"); ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) if (Kinds.of(rs.getString(1)).equals(path.get(0)))
                        out.add(new String[]{rs.getString(1), rs.getString(1), rs.getLong(2) + "", rs.getLong(3) + " different players/causes"});
                }
            } else if (path.size() == 2) {
                try (PreparedStatement ps = c.prepareStatement("SELECT who, COUNT(*) n, MAX(t) last FROM " + Db.TABLE + " WHERE action=? GROUP BY who ORDER BY n DESC LIMIT " + MAX_DOTS)) {
                    ps.setString(1, path.get(1));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) out.add(new String[]{rs.getString(1), rs.getString(1), rs.getLong(2) + "", "last time: " + when(rs.getLong(3))});
                    }
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + Db.TABLE + " WHERE action=? AND who=? ORDER BY id DESC LIMIT " + MAX_DOTS)) {
                    ps.setString(1, path.get(1));
                    ps.setString(2, path.get(2));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            Action a = Action.read(rs);
                            out.add(new String[]{"#" + a.id, short_(a), "1", story(a)});
                        }
                    }
                }
            }
        }
        return out;
    }

    private static String when(long t) { return new SimpleDateFormat("MMM d HH:mm:ss").format(new Date(t)); }

    private static String short_(Action a) {
        String what = a.item != null ? a.item : a.blockOld != null ? a.blockOld : a.blockNew != null ? a.blockNew : a.extra;
        if (what == null) what = a.action;
        if (what.contains(":")) what = what.substring(what.indexOf(':') + 1);
        return what.length() > 28 ? what.substring(0, 28) + "…" : what;
    }

    /** everything about one entry, line by line (\n) for the side panel */
    private static String story(Action a) {
        StringBuilder s = new StringBuilder();
        s.append(a.who).append(" — ").append(a.action).append('\n');
        s.append(when(a.time)).append('\n');
        s.append("dimension ").append(a.dim).append(" at ").append(a.x).append(' ').append(a.y).append(' ').append(a.z).append('\n');
        if (a.blockOld != null) s.append("before: ").append(a.blockOld).append(a.metaOld != 0 ? " :" + a.metaOld : "").append('\n');
        if (a.blockNew != null) s.append("after: ").append(a.blockNew).append(a.metaNew != 0 ? " :" + a.metaNew : "").append('\n');
        if (a.item != null) s.append("item: ").append(a.amount).append("x ").append(a.item).append(a.itemMeta != 0 ? " :" + a.itemMeta : "").append('\n');
        if (a.extra != null) s.append(a.extra).append('\n');
        if (a.rolledBack) s.append("(undone)\n");
        s.append("entry #").append(a.id);
        return s.toString();
    }
}
