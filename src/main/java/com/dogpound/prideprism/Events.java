package com.dogpound.prideprism;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.block.BlockButton;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.BlockFenceGate;
import net.minecraft.block.BlockLever;
import net.minecraft.block.BlockTrapDoor;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

/** Everything worth remembering, caught server-side at LOWEST priority (so cancelled actions aren't logged). */
public final class Events {
    /** the block each player last right-clicked: that's where the container they then open lives */
    private final Map<UUID, Object[]> lastClicked = new HashMap<>();   // {World, BlockPos}
    /** what was in a container when it opened, to see what changed when it closes */
    private final Map<UUID, Map<String, Integer>> openedWith = new HashMap<>();

    private static boolean server(World w) {
        return w != null && !w.isRemote;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBreak(BlockEvent.BreakEvent e) {
        if (!server(e.getWorld()) || !PridePrism.logBlocks) return;
        Db.log(Action.at("break", e.getWorld(), e.getPos(), null, e.getPlayer())
            .before(e.getState(), e.getWorld().getTileEntity(e.getPos())));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPlace(BlockEvent.PlaceEvent e) {
        if (!server(e.getWorld()) || !PridePrism.logBlocks) return;
        List<net.minecraftforge.common.util.BlockSnapshot> snaps = e instanceof BlockEvent.MultiPlaceEvent
            ? ((BlockEvent.MultiPlaceEvent) e).getReplacedBlockSnapshots() : java.util.Collections.singletonList(e.getBlockSnapshot());
        for (net.minecraftforge.common.util.BlockSnapshot s : snaps) {
            BlockPos p = s.getPos();
            Action a = Action.at("place", e.getWorld(), p, null, e.getPlayer()).after(e.getWorld().getBlockState(p), null);
            a.blockOld = s.getReplacedBlock().getBlock().getRegistryName() + "";
            a.metaOld = s.getReplacedBlock().getBlock().getMetaFromState(s.getReplacedBlock());
            a.nbtOld = s.getNbt();
            Db.log(a);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onExplode(ExplosionEvent.Detonate e) {
        if (!server(e.getWorld()) || !PridePrism.logBlocks) return;
        Entity by = e.getExplosion().getExplosivePlacedBy();
        Entity src = net.minecraftforge.fml.common.ObfuscationReflectionHelper.getPrivateValue(net.minecraft.world.Explosion.class, e.getExplosion(), "field_77283_e"); // what blew up (private in 1.12)
        String mod = by == null && src == null ? causedBy() : null;
        String cause = by instanceof EntityPlayer ? by.getName() : src != null ? "#" + src.getName() : "#" + (mod == null ? "explosion" : mod.substring(mod.lastIndexOf('.') + 1));
        if (cause.length() > 64) cause = cause.substring(0, 64);
        EntityPlayer player = by instanceof EntityPlayer ? (EntityPlayer) by : null;
        net.minecraft.util.math.Vec3d c = e.getExplosion().getPosition();
        String where = String.format("%sblast at %.0f %.0f %.0f%s", src != null ? "by " + src.getName() + ", " : "", c.x, c.y, c.z, mod != null ? ", set off by " + mod : "");
        for (BlockPos p : e.getAffectedBlocks()) {
            IBlockState s = e.getWorld().getBlockState(p);
            if (s.getBlock().isAir(s, e.getWorld(), p)) continue;
            Db.log(Action.at("explode", e.getWorld(), p, cause, player).before(s, e.getWorld().getTileEntity(p)).extra(where));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onFluidMakesBlock(BlockEvent.FluidPlaceBlockEvent e) {
        if (!server(e.getWorld()) || !PridePrism.logBlocks) return;
        Db.log(Action.at("fluid", e.getWorld(), e.getPos(), "#" + e.getOriginalState().getBlock().getRegistryName(), null)
            .before(e.getOriginalState(), null).after(e.getNewState(), null));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBucket(FillBucketEvent e) {
        if (!server(e.getWorld()) || e.getTarget() == null || e.getTarget().getBlockPos() == null) return;
        Db.log(Action.at("bucket", e.getWorld(), e.getTarget().getBlockPos(), null, e.getEntityPlayer())
            .item(e.getEmptyBucket(), 1).extra(e.getEmptyBucket().getDisplayName()));
    }

    /** doors, trapdoors, gates, buttons, levers, and any block you right-click that opens a screen */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent.RightClickBlock e) {
        World w = e.getWorld();
        if (!server(w) || e.getHand() != EnumHand.MAIN_HAND) return;
        lastClicked.put(e.getEntityPlayer().getUniqueID(), new Object[]{w, e.getPos()});
        if (!PridePrism.logUse) return;
        IBlockState s = w.getBlockState(e.getPos());
        Block b = s.getBlock();
        String kind = b instanceof BlockDoor ? "door" : b instanceof BlockTrapDoor ? "trapdoor" : b instanceof BlockFenceGate ? "gate"
            : b instanceof BlockButton ? "button" : b instanceof BlockLever ? "lever" : w.getTileEntity(e.getPos()) != null ? "open" : null;
        if (kind == null) return; // right-clicking plain stone isn't news
        Db.log(Action.at(kind.equals("open") ? "use" : kind, w, e.getPos(), null, e.getEntityPlayer()).before(s, null).extra(kind));
    }

    @SubscribeEvent
    public void onContainerOpen(PlayerContainerEvent.Open e) {
        if (!server(e.getEntityPlayer().world) || !PridePrism.logContainers) return;
        openedWith.put(e.getEntityPlayer().getUniqueID(), contents(e.getContainer(), e.getEntityPlayer()));
    }

    @SubscribeEvent
    public void onContainerClose(PlayerContainerEvent.Close e) {
        EntityPlayer pl = e.getEntityPlayer();
        Map<String, Integer> before = openedWith.remove(pl.getUniqueID());
        Object[] where = lastClicked.get(pl.getUniqueID());
        if (before == null || where == null || !server(pl.world)) return;
        Map<String, Integer> after = contents(e.getContainer(), pl);
        Map<String, Integer> all = new HashMap<>(before);
        after.forEach((k, v) -> all.merge(k, 0, Integer::sum));
        for (String key : all.keySet()) {
            int diff = after.getOrDefault(key, 0) - before.getOrDefault(key, 0);
            if (diff == 0) continue;
            String[] parts = key.split("@", 2); // item@meta
            Action a = Action.at(diff > 0 ? "item-put" : "item-take", (World) where[0], (BlockPos) where[1], null, pl);
            a.item = parts[0];
            a.itemMeta = Integer.parseInt(parts[1]);
            a.amount = Math.abs(diff);
            Db.log(a);
        }
    }

    /** item@meta -> count, for the container's own slots (not the player's inventory) */
    private static Map<String, Integer> contents(Container c, EntityPlayer pl) {
        Map<String, Integer> m = new HashMap<>();
        for (Slot s : c.inventorySlots) {
            if (s.inventory == pl.inventory) continue;
            ItemStack st = s.getStack();
            if (!st.isEmpty()) m.merge(st.getItem().getRegistryName() + "@" + st.getMetadata(), st.getCount(), Integer::sum);
        }
        return m;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDeath(LivingDeathEvent e) {
        EntityLivingBase dead = e.getEntityLiving();
        if (!server(dead.world) || !PridePrism.logEntities) return;
        Entity killer = e.getSource().getTrueSource();
        boolean precious = precious(dead);
        if (!(dead instanceof EntityPlayer) && !(killer instanceof EntityPlayer) && !precious) return; // mobs killing mobs: noise
        Action a = Action.at(dead instanceof EntityPlayer ? "death" : "kill", dead.world, dead.getPosition(),
            killer != null ? killer.getName() : "#" + e.getSource().getDamageType(), killer instanceof EntityPlayer ? (EntityPlayer) killer : null);
        if (dead instanceof EntityPlayer && !(killer instanceof EntityPlayer)) { a.who = dead.getName(); a.uuid = dead.getUniqueID().toString(); }
        // what a rollback needs to undo it: the player's whole inventory (unless keepInventory kept it), or the pet itself
        if (dead instanceof EntityPlayer && !dead.world.getGameRules().getBoolean("keepInventory")) {
            EntityPlayer pl = (EntityPlayer) dead;
            net.minecraft.nbt.NBTTagList inv = new net.minecraft.nbt.NBTTagList();
            for (int i = 0; i < pl.inventory.getSizeInventory(); i++) {
                ItemStack st = pl.inventory.getStackInSlot(i);
                if (!st.isEmpty()) inv.appendTag(st.writeToNBT(new net.minecraft.nbt.NBTTagCompound()));
            }
            a.nbtOld = new net.minecraft.nbt.NBTTagCompound();
            a.nbtOld.setTag("inv", inv);
            a.nbtOld.setInteger("xp", pl.experienceTotal);
            a.nbtOld.setString("victim", pl.getUniqueID().toString());   // in PvP the entry's "who" is the killer
        } else if (precious) {
            net.minecraft.nbt.NBTTagCompound n = new net.minecraft.nbt.NBTTagCompound();
            net.minecraft.util.ResourceLocation id = net.minecraft.entity.EntityList.getKey(dead);
            if (id != null) { n.setString("id", id.toString()); dead.writeToNBT(n); a.nbtOld = n; }
        }
        Db.log(a.extra(dead.getName() + " (" + e.getSource().getDamageType() + ")"));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDrop(ItemTossEvent e) {
        if (!server(e.getPlayer().world) || !PridePrism.logItems) return;
        ItemStack st = e.getEntityItem().getItem();
        Db.log(Action.at("drop", e.getPlayer().world, e.getPlayer().getPosition(), null, e.getPlayer()).item(st, st.getCount()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPickup(EntityItemPickupEvent e) {
        if (!server(e.getEntityPlayer().world) || !PridePrism.logItems) return;
        ItemStack st = e.getItem().getItem();
        Db.log(Action.at("pickup", e.getEntityPlayer().world, e.getItem().getPosition(), null, e.getEntityPlayer()).item(st, st.getCount()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onCraft(PlayerEvent.ItemCraftedEvent e) {
        if (!server(e.player.world) || !PridePrism.logItems || e.crafting.isEmpty()) return;
        Db.log(Action.at("craft", e.player.world, e.player.getPosition(), null, e.player).item(e.crafting, e.crafting.getCount()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onChat(ServerChatEvent e) {
        if (!PridePrism.logChat) return;
        Db.log(Action.at("chat", e.getPlayer().world, e.getPlayer().getPosition(), null, e.getPlayer()).extra(e.getMessage()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onCommand(CommandEvent e) {
        if (!PridePrism.logChat || !(e.getSender() instanceof EntityPlayerMP)) return;
        EntityPlayerMP p = (EntityPlayerMP) e.getSender();
        Db.log(Action.at("command", p.world, p.getPosition(), null, p).extra("/" + e.getCommand().getName() + " " + String.join(" ", e.getParameters())));
    }

    @SubscribeEvent
    public void onJoin(PlayerEvent.PlayerLoggedInEvent e) {
        if (!server(e.player.world)) return;
        Db.log(Action.at("join", e.player.world, e.player.getPosition(), null, e.player));
        if (e.player instanceof EntityPlayerMP) SelfTest.run((EntityPlayerMP) e.player);
    }

    @SubscribeEvent
    public void onLeave(PlayerEvent.PlayerLoggedOutEvent e) {
        Web.left(e.player.getUniqueID()); // settle play time before the leave is counted
        lastClicked.remove(e.player.getUniqueID());
        openedWith.remove(e.player.getUniqueID());
        Db.log(Action.at("leave", e.player.world, e.player.getPosition(), null, e.player));
    }

    @SubscribeEvent
    public void onChangeDim(PlayerEvent.PlayerChangedDimensionEvent e) {
        Db.log(Action.at("teleport", e.player.world, e.player.getPosition(), null, e.player).extra("dimension " + e.fromDim + " -> " + e.toDim));
    }

    /** no entity behind an explosion (a mod's bomb, a machine blowing up): name the mod class that set it off */
    private static String causedBy() {
        for (StackTraceElement t : Thread.currentThread().getStackTrace()) {
            String c = t.getClassName();
            if (c.startsWith("java.") || c.startsWith("sun.") || c.startsWith("net.minecraft.") || c.startsWith("net.minecraftforge.")
                || c.startsWith("com.dogpound.prideprism.") || c.contains("$$") || c.startsWith("jdk.")) continue;
            return c;
        }
        return null;
    }

    /** mobs worth bringing back: pets, anything named, villagers, golems, farm animals */
    static boolean precious(EntityLivingBase e) {
        if (e instanceof EntityPlayer) return false;
        return e.hasCustomName() || e instanceof net.minecraft.entity.passive.EntityTameable && ((net.minecraft.entity.passive.EntityTameable) e).isTamed()
            || e instanceof net.minecraft.entity.passive.AbstractHorse && ((net.minecraft.entity.passive.AbstractHorse) e).isTame()
            || e instanceof net.minecraft.entity.INpc || e instanceof net.minecraft.entity.monster.EntityGolem || e instanceof net.minecraft.entity.passive.EntityAnimal;
    }
}
