package com.dogpound.prideprism;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.BlockFire;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IWorldEventListener;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fluids.IFluidBlock;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Lava / water / fire griefing (her story 2026-09-28: someone poured lava over her whole city). Every block change
 * in the world passes through here with its before AND after state:
 *   pour   — a player empties a lava/water bucket: that spot is stamped with their name
 *   flow   — the fluid spreads from a stamped spot: logged under the same player, with what it replaced
 *   ignite — fire appears near their lava or fire (or from their flint & steel)
 *   burn   — a block next to their fire burns away
 * All four are block changes the rollback engine undoes (sources first, then flows, then burned blocks back).
 * Stamps fade 30 minutes after the last spread.
 */
public final class GriefTracker implements IWorldEventListener {
    static final long FADE = 30 * 60_000L;
    static final int MAX = 400_000;

    static final class Owner {
        final String who, uuid;
        long until;
        Owner(String who, String uuid) { this.who = who; this.uuid = uuid; this.until = System.currentTimeMillis() + FADE; }
    }

    private static final Map<Integer, Map<Long, Owner>> OWNERS = new HashMap<>();

    private static Map<Long, Owner> dim(World w) { return OWNERS.computeIfAbsent(w.provider.getDimension(), k -> new HashMap<>()); }

    static void stamp(World w, BlockPos p, String who, String uuid) {
        Map<Long, Owner> m = dim(w);
        if (m.size() > MAX) m.values().removeIf(o -> o.until < System.currentTimeMillis());
        if (m.size() <= MAX) m.put(p.toLong(), new Owner(who, uuid));
    }

    /**
     * Research gap (LogBlock does it, CoreProtect doesn't): stamps live in memory, so a restart forgot whose lava was
     * whose. At server start, reload the last few hours of pour/flow/ignite rows in the background — a flood that
     * outlives a restart stays pinned on whoever started it. The game never waits on the database for this.
     */
    static void reloadFromDatabase(net.minecraft.server.MinecraftServer server) {
        new Thread(() -> {
            int n = 0;
            long since = System.currentTimeMillis() - 6 * 3_600_000L;
            java.util.Map<Integer, java.util.Map<Long, Owner>> loaded = new HashMap<>();
            try (java.sql.Connection c = Db.connect(); java.sql.PreparedStatement ps = c.prepareStatement(
                    "SELECT dim, x, y, z, who, uuid FROM " + Db.TABLE + " WHERE t > ? AND action IN ('pour','flow','ignite') ORDER BY id DESC LIMIT 200000")) {
                ps.setLong(1, since);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long key = new BlockPos(rs.getInt(2), rs.getInt(3), rs.getInt(4)).toLong();
                        loaded.computeIfAbsent(rs.getInt(1), k -> new HashMap<>()).putIfAbsent(key, new Owner(rs.getString(5), rs.getString(6)));
                        n++;
                    }
                }
            } catch (Exception e) {
                PridePrism.LOG.warn("[PridePrism] couldn't reload lava/fire owners: " + e);
                return;
            }
            int total = n;
            server.addScheduledTask(() -> {
                loaded.forEach((dim, m) -> { Map<Long, Owner> live = OWNERS.computeIfAbsent(dim, k -> new HashMap<>()); m.forEach(live::putIfAbsent); });
                PridePrism.LOG.info("[PridePrism] lava/fire ownership: " + total + " spots from the last 6 h reloaded");
            });
        }, "PridePrism-grief-reload").start();
    }

    // ---------------- who started it ----------------
    @SubscribeEvent
    public void worldLoad(WorldEvent.Load e) {
        if (!e.getWorld().isRemote && PridePrism.logBlocks) e.getWorld().addEventListener(this);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void bucket(FillBucketEvent e) {
        if (e.getWorld().isRemote || e.getTarget() == null || e.getTarget().getBlockPos() == null) return;
        ItemStack held = e.getEmptyBucket();
        if (held.isEmpty() || held.getItem() == Items.BUCKET || held.getItem() == Items.MILK_BUCKET) return;   // filling, not pouring
        BlockPos p = e.getTarget().getBlockPos();
        if (!e.getWorld().getBlockState(p).getBlock().isReplaceable(e.getWorld(), p)) p = p.offset(e.getTarget().sideHit);
        stamp(e.getWorld(), p, e.getEntityPlayer().getName(), e.getEntityPlayer().getUniqueID().toString());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void lighter(PlayerInteractEvent.RightClickBlock e) {
        if (e.getWorld().isRemote) return;
        ItemStack s = e.getItemStack();
        if (s.getItem() != Items.FLINT_AND_STEEL && s.getItem() != Items.FIRE_CHARGE) return;
        BlockPos p = e.getPos().offset(e.getFace() == null ? EnumFacing.UP : e.getFace());
        stamp(e.getWorld(), p, e.getEntityPlayer().getName(), e.getEntityPlayer().getUniqueID().toString());
    }

    // ---------------- every block change ----------------
    private static boolean warned;

    /** a logger must never take the world down (2026-09-28: Primal Core leaves crashed the server through here) */
    @Override
    public void notifyBlockUpdate(World w, BlockPos pos, IBlockState was, IBlockState now, int flags) {
        try { track(w, pos, was, now); }
        catch (Throwable t) { if (!warned) { warned = true; PridePrism.LOG.warn("[PridePrism] grief tracker skipped a block update it couldn't read (further ones silent): " + t); } }
    }

    private void track(World w, BlockPos pos, IBlockState was, IBlockState now) {
        if (w.isRemote || was == now) return;
        Map<Long, Owner> m = OWNERS.get(w.provider.getDimension());
        if (m == null || m.isEmpty()) return;                       // nobody's lava/fire around: nothing to do, fast
        boolean fluidNow = fluid(now), fireNow = now.getBlock() instanceof BlockFire;
        long key = pos.toLong();
        Owner here = m.get(key);
        if (here != null && here.until < System.currentTimeMillis()) { m.remove(key); here = null; }

        if ((fluidNow || fireNow) && !fluid(was) && !(was.getBlock() instanceof BlockFire)) {
            Owner o = here;
            String kind;
            if (o != null) kind = fluidNow ? "pour" : "ignite";            // the stamped spot itself: the bucket / the lighter
            else if (fluidNow) { o = near(m, pos, 1, 1, 1, true); kind = "flow"; }
            else { o = near(m, pos, 3, 1, 3, false); kind = "ignite"; }     // lava / fire can light things a few blocks off
            if (o == null) return;
            o.until = System.currentTimeMillis() + FADE;
            m.put(key, here != null ? here : new Owner(o.who, o.uuid));
            log(kind, w, pos, o, was, now);
            return;
        }
        if (!fluidNow && !fireNow && now.getMaterial() == Material.AIR && net.minecraft.init.Blocks.FIRE.getFlammability(was.getBlock()) > 0 && !fluid(was)) {
            Owner o = near(m, pos, 1, 1, 1, false);                         // burnt away next to their fire
            if (o != null && fireNearby(w, pos)) log("burn", w, pos, o, was, now);
        }
    }

    private static boolean fireNearby(World w, BlockPos p) {
        for (EnumFacing f : EnumFacing.values()) if (w.getBlockState(p.offset(f)).getBlock() instanceof BlockFire) return true;
        return w.getBlockState(p).getBlock() instanceof BlockFire;
    }

    /** a stamped fluid/fire spot within the box around p */
    private static Owner near(Map<Long, Owner> m, BlockPos p, int dx, int dy, int dz, boolean sameKind) {
        long now = System.currentTimeMillis();
        for (int x = -dx; x <= dx; x++) for (int y = -dy; y <= dy + (sameKind ? 0 : 2); y++) for (int z = -dz; z <= dz; z++) {
            if (x == 0 && y == 0 && z == 0) continue;
            Owner o = m.get(p.add(x, y, z).toLong());
            if (o != null && o.until >= now) return o;
        }
        return null;
    }

    static boolean fluid(IBlockState s) {
        Block b = s.getBlock();
        return b instanceof BlockLiquid || b instanceof IFluidBlock;
    }

    static volatile String lastKind, lastWho;       // for the self-test

    private static void log(String kind, World w, BlockPos pos, Owner o, IBlockState was, IBlockState now) {
        lastKind = kind; lastWho = o.who;
        Action a = Action.at(kind, w, pos, o.who, null).before(was, null).after(now, null);
        a.uuid = o.uuid;
        Db.log(a);
    }

    // ---------------- the rest of IWorldEventListener: not needed ----------------
    @Override public void notifyLightSet(BlockPos pos) {}
    @Override public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {}
    @Override public void playSoundToAllNearExcept(EntityPlayer player, SoundEvent s, SoundCategory c, double x, double y, double z, float v, float p) {}
    @Override public void playRecord(SoundEvent s, BlockPos pos) {}
    @Override public void spawnParticle(int id, boolean ignoreRange, double x, double y, double z, double xs, double ys, double zs, int... p) {}
    @Override public void spawnParticle(int id, boolean ignoreRange, boolean minParticles, double x, double y, double z, double xs, double ys, double zs, int... p) {}
    @Override public void onEntityAdded(Entity e) {}
    @Override public void onEntityRemoved(Entity e) {}
    @Override public void broadcastSound(int id, BlockPos pos, int data) {}
    @Override public void playEvent(EntityPlayer player, int type, BlockPos pos, int data) {}
    @Override public void sendBlockBreakProgress(int breakerId, BlockPos pos, int progress) {}
}
