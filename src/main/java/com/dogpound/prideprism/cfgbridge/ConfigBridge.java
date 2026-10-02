package com.dogpound.prideprism.cfgbridge;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigCategory;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/**
 * Every setting of a mod, editable from its menu (her ask 2026-09-28: "every option, every setting… extremely complex").
 * The same file goes into each of our mods (own package). Two kinds of config are supported:
 *   - an @Config class (RealmCoin): values live in its static fields; Forge's own Configuration behind it gives the
 *     defaults, ranges and comments;
 *   - a plain Forge Configuration (PridePrism): values live in the Configuration; `reload` copies them into the mod.
 * Only ops (permission 2) can see or change anything. Rows use the same layout as PrideGuard's options:
 *   scope,key,group,label,type,value,def,min,max,step,about,choices
 */
public final class ConfigBridge {
    private ConfigBridge() {}

    private static String modid;
    private static Configuration cfg;          // the file (either kind)
    private static Class<?> annotated;         // @Config class, or null
    private static String cfgName;             // its file name (Forge may not have loaded it yet at preInit)
    private static Runnable reload;            // plain Configuration: copy values into the mod
    private static SimpleNetworkWrapper CH;
    /** client side: the last rows the server sent (the page reads these) */
    public static List<String[]> rows = new ArrayList<>();
    public static long rowsAt;
    public static String lastResult = "";
    public static Consumer<List<String[]>> onRows;

    // ---------------- setup ----------------
    /** an @Config mod: call in preInit, after Forge has loaded the config */
    public static void initAnnotated(String modid, Class<?> configClass) {
        ConfigBridge.modid = modid;
        annotated = configClass;
        String name = configClass.getAnnotation(Config.class).name();
        cfgName = name.isEmpty() ? modid : name;
        cfg = forgeConfigFor(cfgName);
        channel();
    }

    /** a plain Configuration mod: `reload` must re-read every value from cfg into the mod's fields */
    public static void initPlain(String modid, Configuration configuration, Runnable reloadIntoMod) {
        ConfigBridge.modid = modid;
        cfg = configuration;
        reload = reloadIntoMod;
        channel();
    }

    private static void channel() {
        CH = NetworkRegistry.INSTANCE.newSimpleChannel(("dpcfg_" + modid).substring(0, Math.min(20, 6 + modid.length())));
        CH.registerMessage(Ask.H.class, Ask.class, 0, Side.SERVER);
        CH.registerMessage(Rows.H.class, Rows.class, 1, Side.CLIENT);
        CH.registerMessage(Set.H.class, Set.class, 2, Side.SERVER);
    }

    /** the Configuration Forge built for an @Config class: Cleanroom's class→config map, then Forge's own lookup, then
     *  a scan of its file map (her pack runs Cleanroom; the file-path scan alone found nothing there) */
    @SuppressWarnings("unchecked")
    private static Configuration forgeConfigFor(String name) {
        try {
            Map<Class<?>, Configuration> byClass = ObfuscationReflectionHelper.getPrivateValue(ConfigManager.class, null, "CLASS_TO_CONFIG");
            if (annotated != null && byClass != null && byClass.get(annotated) != null) return byClass.get(annotated);
        } catch (Throwable ignored) {}                      // plain Forge has no such map
        try {
            java.lang.reflect.Method m = ConfigManager.class.getDeclaredMethod("getConfiguration", String.class, String.class);
            m.setAccessible(true);
            Configuration c = (Configuration) m.invoke(null, modid, name);
            if (c != null) return c;
        } catch (Throwable ignored) {}
        try {
            Map<String, Configuration> all = ObfuscationReflectionHelper.getPrivateValue(ConfigManager.class, null, "CONFIGS");
            for (Map.Entry<String, Configuration> e : all.entrySet())
                if (new File(e.getKey()).getName().equals(name + ".cfg")) return e.getValue();
            System.out.println("[ConfigBridge] no Forge config for " + modid + "/" + name + "; known: " + all.keySet());
        } catch (Throwable t) {
            System.out.println("[ConfigBridge] can't reach Forge's config map: " + t);
        }
        return null;
    }

    // ---------------- client → server ----------------
    public static void ask() { CH.sendToServer(new Ask()); }

    /** key = "category.name" (the category path as Forge names it), value as text; "*default" resets */
    public static void set(String key, String value) { CH.sendToServer(new Set(key, value)); }

    // ---------------- building the rows (server) ----------------
    static List<String[]> build() {
        List<String[]> out = new ArrayList<>();
        if (cfg == null && cfgName != null) cfg = forgeConfigFor(cfgName);   // loaded late
        if (cfg == null) return out;
        for (String catName : new TreeSet<>(cfg.getCategoryNames())) {
            ConfigCategory cat = cfg.getCategory(catName);
            String group = prettify(catName);
            for (Map.Entry<String, Property> e : new TreeMap<>(cat.getValues()).entrySet()) {
                Property p = e.getValue();
                String type = typeOf(p), value = current(catName, p);
                if (secret(p.getName()) && !value.isEmpty()) value = MASK;          // passwords never leave the server
                String[] valid = p.getValidValues();
                if (valid != null && valid.length > 0) type = "CHOICE";
                out.add(new String[]{modid, catName + "." + p.getName(), group, prettify(p.getName()), type, value,
                    p.isList() ? String.join(",", p.getDefaults()) : p.getDefault(),
                    p.getMinValue() == null ? "" : p.getMinValue(), p.getMaxValue() == null ? "" : p.getMaxValue(),
                    type.equals("INT") ? "1" : type.equals("NUM") ? "0.05" : "", p.getComment() == null ? "" : p.getComment().replace("\n", " "),
                    valid == null ? "" : String.join("|", valid)});
            }
        }
        return out;
    }

    static final String MASK = "********";
    static boolean secret(String name) { String n = name.toLowerCase(Locale.ROOT); return n.contains("password") || n.contains("secret") || n.contains("token"); }

    private static String typeOf(Property p) {
        if (p.isList()) return "TEXT";
        switch (p.getType()) {
            case BOOLEAN: return "BOOL";
            case INTEGER: return "INT";
            case DOUBLE: return "NUM";
            default: return "TEXT";
        }
    }

    /** @Config: the live field wins (the file can be stale); plain: the Configuration is the truth */
    private static String current(String catName, Property p) {
        Field f = field(catName, p.getName());
        if (f != null) {
            try {
                Object v = f.get(null);
                if (v instanceof Object[]) { StringBuilder b = new StringBuilder(); for (Object o : (Object[]) v) b.append(b.length() == 0 ? "" : ",").append(o); return b.toString(); }
                if (v instanceof int[]) return Arrays.toString((int[]) v).replaceAll("[\\[\\] ]", "");
                if (v instanceof double[]) return Arrays.toString((double[]) v).replaceAll("[\\[\\] ]", "");
                return String.valueOf(v);
            } catch (IllegalAccessException ignored) {}
        }
        return p.isList() ? String.join(",", p.getStringList()) : p.getString();
    }

    /** the @Config field behind "general.some_name" (only top-level fields; sub-categories stay file-backed) */
    private static Field field(String catName, String propName) {
        if (annotated == null || !catName.equals("general")) return null;
        for (Field f : annotated.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.isAnnotationPresent(Config.Ignore.class)) continue;
            Config.Name n = f.getAnnotation(Config.Name.class);
            if ((n != null ? n.value() : f.getName()).equals(propName)) return f;
        }
        return null;
    }

    private static String prettify(String s) {
        String t = s.replaceAll("[_.]", " ").replaceAll("([a-z])([A-Z])", "$1 $2").trim();
        return t.isEmpty() ? s : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    // ---------------- applying a change (server) ----------------
    static String apply(String key, String value) {
        int dot = key.lastIndexOf('.');
        if (cfg == null && cfgName != null) cfg = forgeConfigFor(cfgName);
        if (cfg == null || dot < 0) return "no such setting";
        String catName = key.substring(0, dot), name = key.substring(dot + 1);
        if (!cfg.hasCategory(catName) || !cfg.getCategory(catName).containsKey(name)) return "no such setting: " + key;
        Property p = cfg.getCategory(catName).get(name);
        if (secret(name) && value.equals(MASK)) return "unchanged";              // the masked text came back as-is
        if (value.equals("*default")) value = p.isList() ? String.join(",", p.getDefaults()) : p.getDefault();
        String before = current(catName, p);
        try {
            if (p.isList()) {
                p.set(value.isEmpty() ? new String[0] : value.split("\\s*,\\s*"));
            } else switch (p.getType()) {
                case BOOLEAN:
                    if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) return "on/off only";
                    p.set(Boolean.parseBoolean(value)); break;
                case INTEGER: {
                    int v = (int) Math.round(Double.parseDouble(value));
                    if (p.getMinValue() != null) v = Math.max(v, Integer.parseInt(p.getMinValue()));
                    if (p.getMaxValue() != null) v = Math.min(v, Integer.parseInt(p.getMaxValue()));
                    p.set(v); break;
                }
                case DOUBLE: {
                    double v = Double.parseDouble(value);
                    if (p.getMinValue() != null) v = Math.max(v, Double.parseDouble(p.getMinValue()));
                    if (p.getMaxValue() != null) v = Math.min(v, Double.parseDouble(p.getMaxValue()));
                    p.set(v); break;
                }
                default:
                    String[] valid = p.getValidValues();
                    if (valid != null && valid.length > 0 && !Arrays.asList(valid).contains(value)) return "one of " + String.join(", ", valid);
                    p.set(value);
            }
        } catch (NumberFormatException e) {
            return "not a number: " + value;
        }
        // into the running mod
        Field f = field(catName, name);
        if (f != null) {
            try { setField(f, p); } catch (Exception e) { return "saved, but the running mod didn't take it: " + e; }
            ConfigManager.sync(modid, Config.Type.INSTANCE);      // field → file
        } else {
            if (cfg.hasChanged()) cfg.save();
            if (reload != null) reload.run();
        }
        return prettify(name) + (secret(name) ? " changed" : ": " + before + " → " + current(catName, p));
    }

    private static void setField(Field f, Property p) throws IllegalAccessException {
        Class<?> t = f.getType();
        if (t == boolean.class) f.setBoolean(null, p.getBoolean());
        else if (t == int.class) f.setInt(null, p.getInt());
        else if (t == double.class) f.setDouble(null, p.getDouble());
        else if (t == float.class) f.setFloat(null, (float) p.getDouble());
        else if (t == long.class) f.setLong(null, (long) p.getDouble());
        else if (t == String.class) f.set(null, p.getString());
        else if (t == String[].class) f.set(null, p.getStringList());
        else if (t == int[].class) f.set(null, p.getIntList());
        else if (t == double[].class) f.set(null, p.getDoubleList());
        else if (t.isEnum()) {
            for (Object c : t.getEnumConstants()) if (((Enum<?>) c).name().equals(p.getString())) f.set(null, c);
        } else throw new IllegalAccessException("unsupported type " + t.getSimpleName());
    }

    // ---------------- messages ----------------
    static void str(ByteBuf b, String s) { byte[] d = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8); int n = Math.min(d.length, 30000); b.writeShort(n); b.writeBytes(d, 0, n); }
    static String str(ByteBuf b) { byte[] d = new byte[b.readUnsignedShort()]; b.readBytes(d); return new String(d, StandardCharsets.UTF_8); }
    static boolean op(EntityPlayerMP p) { return com.dogpound.prideprism.Perm.has(p, com.dogpound.prideprism.Perm.CONFIG); }

    public static class Ask implements IMessage {
        public Ask() {}
        @Override public void toBytes(ByteBuf b) {}
        @Override public void fromBytes(ByteBuf b) {}
        public static class H implements IMessageHandler<Ask, IMessage> {
            @Override public IMessage onMessage(Ask m, MessageContext ctx) {
                EntityPlayerMP p = ctx.getServerHandler().player;
                p.getServerWorld().addScheduledTask(() -> CH.sendTo(new Rows(op(p) ? build() : new ArrayList<>(), op(p) ? "" : "only ops can change settings"), p));
                return null;
            }
        }
    }

    public static class Rows implements IMessage {
        List<String[]> rows = new ArrayList<>();
        String result = "";
        public Rows() {}
        Rows(List<String[]> rows, String result) { this.rows = rows; this.result = result; }
        @Override public void toBytes(ByteBuf b) {
            str(b, result);
            b.writeShort(rows.size());
            for (String[] r : rows) { b.writeByte(r.length); for (String x : r) str(b, x); }
        }
        @Override public void fromBytes(ByteBuf b) {
            result = str(b);
            int n = b.readUnsignedShort();
            for (int i = 0; i < n; i++) { String[] r = new String[b.readUnsignedByte()]; for (int k = 0; k < r.length; k++) r[k] = str(b); rows.add(r); }
        }
        public static class H implements IMessageHandler<Rows, IMessage> {
            @Override public IMessage onMessage(Rows m, MessageContext ctx) {
                net.minecraft.client.Minecraft.getMinecraft().addScheduledTask(() -> {
                    ConfigBridge.rows = m.rows; rowsAt = System.currentTimeMillis();
                    if (!m.result.isEmpty()) lastResult = m.result;
                    if (onRows != null) onRows.accept(m.rows);
                });
                return null;
            }
        }
    }

    public static class Set implements IMessage {
        String key, value;
        public Set() {}
        Set(String key, String value) { this.key = key; this.value = value; }
        @Override public void toBytes(ByteBuf b) { str(b, key); str(b, value); }
        @Override public void fromBytes(ByteBuf b) { key = str(b); value = str(b); }
        public static class H implements IMessageHandler<Set, IMessage> {
            @Override public IMessage onMessage(Set m, MessageContext ctx) {
                EntityPlayerMP p = ctx.getServerHandler().player;
                p.getServerWorld().addScheduledTask(() -> {
                    if (!op(p)) { CH.sendTo(new Rows(new ArrayList<>(), "only ops can change settings"), p); return; }
                    String r = apply(m.key, m.value);
                    System.out.println("[ConfigBridge] " + p.getName() + " set " + modid + " " + m.key + ": " + r);
                    CH.sendTo(new Rows(build(), r), p);
                });
                return null;
            }
        }
    }
}
