package com.dogpound.prideprism;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

/** Screen <-> server: the screen asks with Prism words, the server answers with rows; admins can teleport/undo one. */
public final class Net {
    public static final SimpleNetworkWrapper CH = NetworkRegistry.INSTANCE.newSimpleChannel("prideprism");

    static void init() {
        CH.registerMessage(Query.Handler.class, Query.class, 0, Side.SERVER);
        CH.registerMessage(Results.Handler.class, Results.class, 1, Side.CLIENT);
        CH.registerMessage(RowAction.Handler.class, RowAction.class, 2, Side.SERVER);
        CH.registerMessage(Open.Handler.class, Open.class, 3, Side.CLIENT);
        CH.registerMessage(MachineList.Handler.class, MachineList.class, 4, Side.CLIENT);
        CH.registerMessage(MachineToggle.Handler.class, MachineToggle.class, 5, Side.SERVER);
        CH.registerMessage(ClientAct.Handler.class, ClientAct.class, 6, Side.SERVER);
        CH.registerMessage(GraphAsk.Handler.class, GraphAsk.class, 7, Side.SERVER);
        CH.registerMessage(GraphData.Handler.class, GraphData.class, 8, Side.CLIENT);
        CH.registerMessage(PlanAsk.Handler.class, PlanAsk.class, 9, Side.SERVER);
        CH.registerMessage(PlanData.Handler.class, PlanData.class, 10, Side.CLIENT);
        CH.registerMessage(PlanGo.Handler.class, PlanGo.class, 11, Side.SERVER);
    }

    static void str(ByteBuf b, String s) {
        byte[] d = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        b.writeShort(Math.min(d.length, 32000));
        b.writeBytes(d, 0, Math.min(d.length, 32000));
    }

    static String str(ByteBuf b) {
        byte[] d = new byte[b.readUnsignedShort()];
        b.readBytes(d);
        return new String(d, StandardCharsets.UTF_8);
    }

    private static boolean admin(EntityPlayerMP p) {
        return Perm.has(p, Perm.ADMIN);
    }

    /** the screen asks: Prism words (p: a: t: r: b: i:), a page, and whether "near me" applies */
    public static class Query implements IMessage {
        public String words = "";
        public int page;

        public Query() {}
        public Query(String words, int page) { this.words = words; this.page = page; }

        @Override public void fromBytes(ByteBuf b) { words = str(b); page = b.readInt(); }
        @Override public void toBytes(ByteBuf b) { str(b, words); b.writeInt(page); }

        public static class Handler implements IMessageHandler<Query, IMessage> {
            @Override
            public IMessage onMessage(Query m, MessageContext ctx) {
                EntityPlayerMP p = ctx.getServerHandler().player;
                if (!admin(p)) { p.getServerWorld().addScheduledTask(() -> p.sendMessage(new TextComponentString("§cPridePrism is for admins."))); return null; }
                String[] words = m.words.trim().isEmpty() ? new String[0] : m.words.trim().split("\\s+");
                Filter f = Filter.parse(words, p.dimension, (int) Math.floor(p.posX), (int) Math.floor(p.posY), (int) Math.floor(p.posZ));
                int page = Math.max(0, m.page);
                new Thread(() -> {
                    Results r = new Results();
                    r.page = page;
                    try {
                        List<Action> found = Db.lookup(f, (page + 1) * Results.PER_PAGE + 1);
                        r.more = found.size() > (page + 1) * Results.PER_PAGE;
                        r.rows = found.subList(Math.min(found.size(), page * Results.PER_PAGE), Math.min(found.size(), (page + 1) * Results.PER_PAGE));
                        r.status = Db.status;
                    } catch (Exception e) {
                        r.rows = Collections.emptyList();
                        r.status = "can't reach the database: " + e.getMessage();
                    }
                    p.getServerWorld().addScheduledTask(() -> CH.sendTo(r, p));
                }, "PridePrism-screen").start();
                return null;
            }
        }
    }

    /** the answer: one page of actions, newest first */
    public static class Results implements IMessage {
        static final int PER_PAGE = 60;
        public List<Action> rows = new ArrayList<>();
        public int page;
        public boolean more;
        public String status = "";

        public Results() {}

        @Override
        public void toBytes(ByteBuf b) {
            b.writeInt(page); b.writeBoolean(more); str(b, status);
            b.writeInt(rows.size());
            for (Action a : rows) {
                b.writeLong(a.id); b.writeLong(a.time); str(b, a.action); str(b, a.who);
                str(b, a.blockOld); b.writeInt(a.metaOld); str(b, a.blockNew); b.writeInt(a.metaNew);
                str(b, a.item); b.writeInt(a.itemMeta); b.writeInt(a.amount); str(b, a.extra);
                b.writeInt(a.dim); b.writeInt(a.x); b.writeInt(a.y); b.writeInt(a.z); b.writeBoolean(a.rolledBack);
            }
        }

        @Override
        public void fromBytes(ByteBuf b) {
            page = b.readInt(); more = b.readBoolean(); status = str(b);
            int n = b.readInt();
            rows = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                Action a = new Action();
                a.id = b.readLong(); a.time = b.readLong(); a.action = str(b); a.who = str(b);
                a.blockOld = nz(str(b)); a.metaOld = b.readInt(); a.blockNew = nz(str(b)); a.metaNew = b.readInt();
                a.item = nz(str(b)); a.itemMeta = b.readInt(); a.amount = b.readInt(); a.extra = nz(str(b));
                a.dim = b.readInt(); a.x = b.readInt(); a.y = b.readInt(); a.z = b.readInt(); a.rolledBack = b.readBoolean();
                rows.add(a);
            }
        }

        private static String nz(String s) { return s.isEmpty() ? null : s; }

        public static class Handler implements IMessageHandler<Results, IMessage> {
            @Override
            public IMessage onMessage(Results m, MessageContext ctx) {
                net.minecraft.client.Minecraft.getMinecraft().addScheduledTask(() -> ClientHooks.results(m));
                return null;
            }
        }
    }

    /** a row's buttons: 0 = teleport there, 1 = undo it, 2 = redo it (admins) */
    public static class RowAction implements IMessage {
        public long id;
        public int what;

        public RowAction() {}
        public RowAction(long id, int what) { this.id = id; this.what = what; }

        @Override public void fromBytes(ByteBuf b) { id = b.readLong(); what = b.readByte(); }
        @Override public void toBytes(ByteBuf b) { b.writeLong(id); b.writeByte(what); }

        public static class Handler implements IMessageHandler<RowAction, IMessage> {
            @Override
            public IMessage onMessage(RowAction m, MessageContext ctx) {
                EntityPlayerMP p = ctx.getServerHandler().player;
                if (!admin(p)) return null;
                new Thread(() -> {
                    try {
                        Action a = Db.byId(m.id);
                        if (a == null) return;
                        p.getServerWorld().addScheduledTask(() -> PrismCommand.rowAction(p, a, m.what));
                    } catch (Exception e) {
                        p.getServerWorld().addScheduledTask(() -> p.sendMessage(new TextComponentString("§cPridePrism: " + e.getMessage())));
                    }
                }, "PridePrism-row").start();
                return null;
            }
        }
    }

    /** the Machine Tablet's list: every wireless machine and what it's doing */
    public static class MachineList implements IMessage {
        public List<Machines.Row> rows = new ArrayList<>();

        public MachineList() {}
        public MachineList(List<Machines.Row> rows) { this.rows = rows; }

        @Override
        public void toBytes(ByteBuf b) {
            b.writeInt(rows.size());
            for (Machines.Row r : rows) {
                b.writeInt(r.index); str(b, r.name); str(b, r.block); str(b, r.state); str(b, r.detail);
                b.writeInt(r.dim); b.writeInt(r.x); b.writeInt(r.y); b.writeInt(r.z);
                b.writeLong(r.energy); b.writeLong(r.energyMax); b.writeInt(r.fluid); b.writeInt(r.fluidMax); str(b, r.fluidName);
                b.writeInt(r.slotsUsed); b.writeInt(r.slots); b.writeByte(r.control); b.writeBoolean(r.on); b.writeBoolean(r.loaded);
            }
        }

        @Override
        public void fromBytes(ByteBuf b) {
            int n = b.readInt();
            for (int i = 0; i < n; i++) {
                Machines.Row r = new Machines.Row();
                r.index = b.readInt(); r.name = str(b); r.block = str(b); r.state = str(b); r.detail = str(b);
                r.dim = b.readInt(); r.x = b.readInt(); r.y = b.readInt(); r.z = b.readInt();
                r.energy = b.readLong(); r.energyMax = b.readLong(); r.fluid = b.readInt(); r.fluidMax = b.readInt(); r.fluidName = str(b);
                r.slotsUsed = b.readInt(); r.slots = b.readInt(); r.control = b.readByte(); r.on = b.readBoolean(); r.loaded = b.readBoolean();
                rows.add(r);
            }
        }

        public static class Handler implements IMessageHandler<MachineList, IMessage> {
            @Override
            public IMessage onMessage(MachineList m, MessageContext ctx) {
                net.minecraft.client.Minecraft.getMinecraft().addScheduledTask(() -> ClientHooks.machines(m.rows));
                return null;
            }
        }
    }

    /** tablet: switch machine #index off/on (index -1 = just refresh the list) */
    public static class MachineToggle implements IMessage {
        public int index;

        public MachineToggle() {}
        public MachineToggle(int index) { this.index = index; }

        @Override public void fromBytes(ByteBuf b) { index = b.readInt(); }
        @Override public void toBytes(ByteBuf b) { b.writeInt(index); }

        public static class Handler implements IMessageHandler<MachineToggle, IMessage> {
            @Override
            public IMessage onMessage(MachineToggle m, MessageContext ctx) {
                EntityPlayerMP p = ctx.getServerHandler().player;
                p.getServerWorld().addScheduledTask(() -> {
                    if (!Machines.holdsTablet(p)) return;
                    if (m.index >= 0) Machines.toggle(p, m.index);
                    else CH.sendTo(new MachineList(Machines.list(p)), p);
                });
                return null;
            }
        }
    }

    /** server tells the client to open the screen (from /pp gui) */
    public static class Open implements IMessage {
        String words = "";                                                // e.g. "box:1,2,30,40 t:1h" from the land map
        public Open() {}
        Open(String words) { this.words = words == null ? "" : words; }
        @Override public void fromBytes(ByteBuf b) { words = b.isReadable() ? ByteBufUtils.readUTF8String(b) : ""; }
        @Override public void toBytes(ByteBuf b) { ByteBufUtils.writeUTF8String(b, words); }

        public static class Handler implements IMessageHandler<Open, IMessage> {
            @Override
            public IMessage onMessage(Open m, MessageContext ctx) {
                net.minecraft.client.Minecraft.getMinecraft().addScheduledTask(() -> ClientHooks.open(m.words));
                return null;
            }
        }
    }

    static void sendOpen(EntityPlayerMP p) { sendOpen(p, ""); }

    static void sendOpen(EntityPlayerMP p, String words) { CH.sendTo(new Open(words), p); }

    /** client -> server: a menu/button/slot/key event for the log (see ClientSpy) */
    public static class ClientAct implements IMessage {
        String kind, text;
        public ClientAct() {}
        public ClientAct(String kind, String text) { this.kind = kind; this.text = text.length() > 480 ? text.substring(0, 480) : text; }
        @Override public void toBytes(ByteBuf b) { ByteBufUtils.writeUTF8String(b, kind); ByteBufUtils.writeUTF8String(b, text); }
        @Override public void fromBytes(ByteBuf b) { kind = ByteBufUtils.readUTF8String(b); text = ByteBufUtils.readUTF8String(b); if (text.length() > 480) text = text.substring(0, 480); }

        public static class Handler implements IMessageHandler<ClientAct, IMessage> {
            @Override
            public IMessage onMessage(ClientAct m, MessageContext ctx) {
                net.minecraft.entity.player.EntityPlayerMP p = ctx.getServerHandler().player;
                p.getServerWorld().addScheduledTask(() -> MoreEvents.fromClient(p, m.kind, m.text));
                return null;
            }
        }
    }

    /** graph ball: "what's inside this dot?" — the path from the centre (see Graph) */
    public static class GraphAsk implements IMessage {
        List<String> path = new ArrayList<>();
        public GraphAsk() {}
        public GraphAsk(List<String> path) { this.path = new ArrayList<>(path); }
        @Override public void toBytes(ByteBuf b) { b.writeByte(path.size()); for (String s : path) str(b, s); }
        @Override public void fromBytes(ByteBuf b) { int n = Math.min(4, b.readUnsignedByte()); for (int i = 0; i < n; i++) path.add(str(b)); }

        public static class Handler implements IMessageHandler<GraphAsk, IMessage> {
            @Override
            public IMessage onMessage(GraphAsk m, MessageContext ctx) {
                EntityPlayerMP p = ctx.getServerHandler().player;
                if (!admin(p)) return null;
                new Thread(() -> {
                    GraphData r = new GraphData();
                    r.path = m.path;
                    try { r.nodes = Graph.children(m.path); }
                    catch (Exception e) { r.nodes = new ArrayList<>(); r.error = "can't reach the database: " + e.getMessage(); }
                    p.getServerWorld().addScheduledTask(() -> CH.sendTo(r, p));
                }, "PridePrism-graph").start();
                return null;
            }
        }
    }

    public static class GraphData implements IMessage {
        List<String> path = new ArrayList<>();
        List<String[]> nodes = new ArrayList<>();
        String error = "";
        public GraphData() {}
        @Override public void toBytes(ByteBuf b) {
            b.writeByte(path.size()); for (String s : path) str(b, s);
            str(b, error);
            b.writeShort(nodes.size());
            for (String[] n : nodes) for (int i = 0; i < 4; i++) str(b, n[i]);
        }
        @Override public void fromBytes(ByteBuf b) {
            int n = b.readUnsignedByte(); for (int i = 0; i < n; i++) path.add(str(b));
            error = str(b);
            int k = b.readUnsignedShort();
            for (int i = 0; i < k; i++) nodes.add(new String[]{str(b), str(b), str(b), str(b)});
        }

        public static class Handler implements IMessageHandler<GraphData, IMessage> {
            @Override
            public IMessage onMessage(GraphData m, MessageContext ctx) {
                net.minecraft.client.Minecraft.getMinecraft().addScheduledTask(() -> GuiPrismGraph.receive(m.path, m.nodes, m.error));
                return null;
            }
        }
    }

    /** rollback planner: these picked entries (or, if none, everything the search words find) — what would happen? */
    public static class PlanAsk implements IMessage {
        long[] ids = new long[0];
        String words = "";
        boolean rollback = true;
        int opts;
        public PlanAsk() {}
        public PlanAsk(long[] ids, String words, boolean rollback, int opts) { this.ids = ids; this.words = words; this.rollback = rollback; this.opts = opts; }
        @Override public void toBytes(ByteBuf b) { b.writeInt(ids.length); for (long id : ids) b.writeLong(id); str(b, words); b.writeBoolean(rollback); b.writeInt(opts); }
        @Override public void fromBytes(ByteBuf b) {
            int n = Math.min(b.readInt(), 20000); ids = new long[n]; for (int i = 0; i < n; i++) ids[i] = b.readLong();
            words = str(b); rollback = b.readBoolean(); opts = b.readInt();
        }

        public static class Handler implements IMessageHandler<PlanAsk, IMessage> {
            @Override
            public IMessage onMessage(PlanAsk m, MessageContext ctx) {
                EntityPlayerMP p = ctx.getServerHandler().player;
                if (!admin(p)) return null;
                net.minecraft.server.MinecraftServer server = p.getServer();
                new Thread(() -> {
                    List<Action> acts;
                    String err = "";
                    try {
                        if (m.ids.length > 0) { List<Long> l = new ArrayList<>(); for (long id : m.ids) l.add(id); acts = Db.byIds(l); }
                        else {
                            String[] w = m.words.trim().isEmpty() ? new String[0] : m.words.trim().split("\\s+");
                            acts = Db.lookup(Filter.parse(w, p.dimension, (int) Math.floor(p.posX), (int) Math.floor(p.posY), (int) Math.floor(p.posZ)), 100000);
                        }
                    } catch (Exception e) { acts = new ArrayList<>(); err = "can't reach the database: " + e.getMessage(); }
                    List<Action> found = acts;
                    String error = err;
                    server.addScheduledTask(() -> {
                        Rollback.Plan plan = Rollback.plan(server, found, m.rollback, Rollback.Options.of(m.opts));
                        Rollback.keep(p, plan);
                        PlanData d = new PlanData();
                        d.lines = new ArrayList<>(plan.summary());
                        if (!error.isEmpty()) d.lines.add(0, "§c" + error);
                        d.todo = plan.todo().size();
                        d.spots = Rollback.preview(plan, 6000);
                        CH.sendTo(d, p);
                    });
                }, "PridePrism-plan").start();
                return null;
            }
        }
    }

    public static class PlanData implements IMessage {
        List<String> lines = new ArrayList<>();
        int todo;
        int[][] spots = new int[0][];
        public PlanData() {}
        @Override public void toBytes(ByteBuf b) {
            b.writeByte(lines.size()); for (String l : lines) str(b, l);
            b.writeInt(todo);
            b.writeInt(spots.length); for (int[] s : spots) for (int i = 0; i < 5; i++) b.writeInt(s[i]);
        }
        @Override public void fromBytes(ByteBuf b) {
            int n = b.readUnsignedByte(); for (int i = 0; i < n; i++) lines.add(str(b));
            todo = b.readInt();
            int k = Math.min(b.readInt(), 6000); spots = new int[k][5];
            for (int[] s : spots) for (int i = 0; i < 5; i++) s[i] = b.readInt();
        }
        public static class Handler implements IMessageHandler<PlanData, IMessage> {
            @Override
            public IMessage onMessage(PlanData m, MessageContext ctx) {
                net.minecraft.client.Minecraft.getMinecraft().addScheduledTask(() -> GuiRollback.receive(m.lines, m.todo, m.spots));
                return null;
            }
        }
    }

    /** "do it": run the plan the server is keeping for this admin */
    public static class PlanGo implements IMessage {
        public PlanGo() {}
        @Override public void toBytes(ByteBuf b) {}
        @Override public void fromBytes(ByteBuf b) {}
        public static class Handler implements IMessageHandler<PlanGo, IMessage> {
            @Override
            public IMessage onMessage(PlanGo m, MessageContext ctx) {
                EntityPlayerMP p = ctx.getServerHandler().player;
                if (!admin(p)) return null;
                p.getServerWorld().addScheduledTask(() -> {
                    Rollback.Plan plan = Rollback.kept(p);
                    if (plan == null) p.sendMessage(new TextComponentString("§eThat plan expired — open the planner again."));
                    else { Rollback.start(p, plan); Rollback.keep(p, null); }
                });
                return null;
            }
        }
    }
}
