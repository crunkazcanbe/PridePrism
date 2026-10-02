package com.dogpound.prideprism;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Prism-style search words:  p:Name  a:break,use  r:20  t:2h  b:chest  i:diamond  (any order, all optional).
 * From the map: at:x,y,z (search around that spot instead of you) and box:x1,z1,x2,z2 (a rectangle, every height).
 * Time: 30s 10m 2h 3d 1w. Radius is around the player (or the inspected block). SQL is built only from fixed
 * pieces with every value bound as a parameter.
 */
public final class Filter {
    public String player, block, item;
    public List<String> actions = new ArrayList<>();
    public int radius = -1, dim, cx, cy, cz;
    public long since;
    public boolean exactBlock; // inspect: this one block only
    public int[] box;          // x1, z1, x2, z2 (sorted) — a map selection

    public static Filter parse(String[] words, int dim, int x, int y, int z) {
        Filter f = new Filter();
        f.dim = dim; f.cx = x; f.cy = y; f.cz = z;
        for (String w : words) {
            int c = w.indexOf(':');
            if (c < 1) continue;
            String k = w.substring(0, c).toLowerCase(Locale.ROOT), v = w.substring(c + 1);
            switch (k) {
                case "p": f.player = v; break;
                case "a": for (String s : v.split(",")) if (!s.isEmpty()) f.actions.add(s.toLowerCase(Locale.ROOT)); break;
                case "r": f.radius = Math.max(0, Math.min(2000, Integer.parseInt(v))); break;
                case "t": f.since = System.currentTimeMillis() - millis(v); break;
                case "b": f.block = v.toLowerCase(Locale.ROOT); break;
                case "i": f.item = v.toLowerCase(Locale.ROOT); break;
                case "at": {
                    String[] n = v.split(",");
                    if (n.length == 3) { f.cx = Integer.parseInt(n[0].trim()); f.cy = Integer.parseInt(n[1].trim()); f.cz = Integer.parseInt(n[2].trim()); }
                    break;
                }
                case "box": {
                    String[] n = v.split(",");
                    if (n.length == 4) {
                        int x1 = Integer.parseInt(n[0].trim()), z1 = Integer.parseInt(n[1].trim()), x2 = Integer.parseInt(n[2].trim()), z2 = Integer.parseInt(n[3].trim());
                        if (Math.abs(x2 - x1) <= 4000 && Math.abs(z2 - z1) <= 4000)       // a sane map selection
                            f.box = new int[]{Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2)};
                    }
                    break;
                }
                default: break;
            }
        }
        if (f.radius < 0 && f.player == null && f.since == 0 && f.box == null) f.radius = 20; // a bare lookup = what happened around me
        return f;
    }

    static long millis(String v) {
        long total = 0, n = 0;
        for (char ch : v.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isDigit(ch)) { n = n * 10 + (ch - '0'); continue; }
            long unit = ch == 's' ? 1000L : ch == 'm' ? 60_000L : ch == 'h' ? 3_600_000L : ch == 'd' ? 86_400_000L : ch == 'w' ? 604_800_000L : 0;
            total += n * unit;
            n = 0;
        }
        return total + n * 3_600_000L; // a bare number means hours
    }

    String sql(List<Object> args) {
        StringBuilder w = new StringBuilder("dim=?");
        args.add(dim);
        if (exactBlock) {
            w.append(" AND x=? AND y=? AND z=?");
            args.add(cx); args.add(cy); args.add(cz);
        } else if (box != null) {
            w.append(" AND x BETWEEN ? AND ? AND z BETWEEN ? AND ?");
            args.add(box[0]); args.add(box[2]); args.add(box[1]); args.add(box[3]);
        } else if (radius >= 0) {
            w.append(" AND x BETWEEN ? AND ? AND z BETWEEN ? AND ? AND y BETWEEN ? AND ?");
            args.add(cx - radius); args.add(cx + radius); args.add(cz - radius); args.add(cz + radius);
            args.add(cy - radius); args.add(cy + radius);
        }
        if (player != null) { w.append(" AND who=?"); args.add(player); }
        if (since > 0) { w.append(" AND t>=?"); args.add(since); }
        if (!actions.isEmpty()) {
            w.append(" AND action IN (");
            for (int i = 0; i < actions.size(); i++) { w.append(i == 0 ? "?" : ",?"); args.add(actions.get(i)); }
            w.append(")");
        }
        if (block != null) { w.append(" AND (block_old LIKE ? OR block_new LIKE ?)"); args.add("%" + block + "%"); args.add("%" + block + "%"); }
        if (item != null) { w.append(" AND item LIKE ?"); args.add("%" + item + "%"); }
        return w.toString();
    }
}
