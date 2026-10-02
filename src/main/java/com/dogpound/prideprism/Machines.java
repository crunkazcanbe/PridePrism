package com.dogpound.prideprism;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldSavedData;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;

/**
 * Wireless machines (her rule: you WORK for it): a crafted Wireless Transmitter is used up on each machine you
 * right-click, and you need a crafted Machine Tablet in hand to open the machine menu. Stats come from Forge's
 * shared energy/item/fluid capabilities (any mod); Mekanism and Immersive Engineering can also be switched off/on.
 */
public final class Machines {
    public static Item TRANSMITTER, TABLET;

    // ---------------- items ----------------
    public static class ItemTransmitter extends Item {
        public ItemTransmitter() {
            setRegistryName("prideprism", "wireless_transmitter");
            setUnlocalizedName("prideprism.wireless_transmitter");
            setCreativeTab(CreativeTabs.TOOLS);
            setMaxStackSize(16);
        }

        @Override
        public EnumActionResult onItemUseFirst(EntityPlayer p, World w, BlockPos pos, EnumFacing side, float hx, float hy, float hz, EnumHand hand) {
            if (w.isRemote) return EnumActionResult.SUCCESS;
            TileEntity te = w.getTileEntity(pos);
            if (te == null || !isMachine(te)) {
                p.sendStatusMessage(new TextComponentString("§cThat isn't a machine the transmitter can talk to."), true);
                return EnumActionResult.FAIL;
            }
            Links links = Links.get(w);
            if (links.find(w.provider.getDimension(), pos) != null) {
                p.sendStatusMessage(new TextComponentString("§eThis machine is already wireless."), true);
                return EnumActionResult.FAIL;
            }
            Link l = new Link();
            l.owner = p.getUniqueID(); l.dim = w.provider.getDimension(); l.pos = pos;
            l.block = String.valueOf(w.getBlockState(pos).getBlock().getRegistryName());
            l.name = w.getBlockState(pos).getBlock().getPickBlock(w.getBlockState(pos), null, w, pos, p).getDisplayName();
            links.list.add(l);
            links.markDirty();
            if (!p.capabilities.isCreativeMode) p.getHeldItem(hand).shrink(1);
            w.playSound(null, pos, net.minecraft.init.SoundEvents.BLOCK_NOTE_CHIME, SoundCategory.BLOCKS, 1f, 1.6f);
            p.sendStatusMessage(new TextComponentString("§d✦ " + l.name + " is now wireless §7(open it with your Machine Tablet)"), true);
            Db.log(Action.at("link", w, pos, null, p).extra(l.name));
            return EnumActionResult.SUCCESS;
        }
    }

    public static class ItemTablet extends Item {
        public ItemTablet() {
            setRegistryName("prideprism", "machine_tablet");
            setUnlocalizedName("prideprism.machine_tablet");
            setCreativeTab(CreativeTabs.TOOLS);
            setMaxStackSize(1);
        }

        @Override
        public ActionResult<ItemStack> onItemRightClick(World w, EntityPlayer p, EnumHand hand) {
            if (!w.isRemote) Net.CH.sendTo(new Net.MachineList(list((EntityPlayerMP) p)), (EntityPlayerMP) p);
            return new ActionResult<>(EnumActionResult.SUCCESS, p.getHeldItem(hand));
        }
    }

    static boolean holdsTablet(EntityPlayer p) {
        return p.getHeldItemMainhand().getItem() == TABLET || p.getHeldItemOffhand().getItem() == TABLET;
    }

    // ---------------- which machines are wireless (saved with the world) ----------------
    public static final class Link {
        public UUID owner;
        public int dim;
        public BlockPos pos;
        public String block, name;
        public String savedMode; // Mekanism: the redstone mode to go back to when switched on again
    }

    public static final class Links extends WorldSavedData {
        final List<Link> list = new ArrayList<>();

        public Links(String name) { super(name); }

        static Links get(World w) {
            World overworld = w.getMinecraftServer() != null ? w.getMinecraftServer().getWorld(0) : w;
            Links l = (Links) overworld.getMapStorage().getOrLoadData(Links.class, "prideprism_machines");
            if (l == null) { l = new Links("prideprism_machines"); overworld.getMapStorage().setData("prideprism_machines", l); }
            return l;
        }

        Link find(int dim, BlockPos pos) {
            for (Link l : list) if (l.dim == dim && l.pos.equals(pos)) return l;
            return null;
        }

        @Override
        public void readFromNBT(NBTTagCompound t) {
            list.clear();
            NBTTagList ls = t.getTagList("links", 10);
            for (int i = 0; i < ls.tagCount(); i++) {
                NBTTagCompound c = ls.getCompoundTagAt(i);
                Link l = new Link();
                l.owner = c.getUniqueId("owner"); l.dim = c.getInteger("dim"); l.pos = BlockPos.fromLong(c.getLong("pos"));
                l.block = c.getString("block"); l.name = c.getString("name");
                l.savedMode = c.hasKey("mode") ? c.getString("mode") : null;
                list.add(l);
            }
        }

        @Override
        public NBTTagCompound writeToNBT(NBTTagCompound t) {
            NBTTagList ls = new NBTTagList();
            for (Link l : list) {
                NBTTagCompound c = new NBTTagCompound();
                c.setUniqueId("owner", l.owner); c.setInteger("dim", l.dim); c.setLong("pos", l.pos.toLong());
                c.setString("block", l.block); c.setString("name", l.name);
                if (l.savedMode != null) c.setString("mode", l.savedMode);
                ls.appendTag(c);
            }
            t.setTag("links", ls);
            return t;
        }
    }

    /** a broken machine stops being wireless (the transmitter is lost with it) */
    @SubscribeEvent
    public void onBreak(BlockEvent.BreakEvent e) {
        if (e.getWorld().isRemote) return;
        Links links = Links.get(e.getWorld());
        Link l = links.find(e.getWorld().provider.getDimension(), e.getPos());
        if (l != null) { links.list.remove(l); links.markDirty(); }
    }

    // ---------------- reading a machine ----------------
    static boolean isMachine(TileEntity te) {
        return cap(te, CapabilityEnergy.ENERGY) != null || cap(te, CapabilityItemHandler.ITEM_HANDLER_CAPABILITY) != null
            || cap(te, CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) != null || irc(te) != null || ieMaster(te) != null
            || Trains.call(te, "getActive") != null;
    }

    static <T> T cap(TileEntity te, Capability<T> c) {
        try {
            if (te.hasCapability(c, null)) return te.getCapability(c, null);
            for (EnumFacing f : EnumFacing.values()) if (te.hasCapability(c, f)) return te.getCapability(c, f);
        } catch (Throwable ignored) { }
        return null;
    }

    /** one line of the machine menu */
    public static final class Row {
        public int index;
        public String name, block, state = "", detail = "";
        public int dim, x, y, z;
        public long energy = -1, energyMax;
        public int fluid = -1, fluidMax;
        public String fluidName = "";
        public int slotsUsed = -1, slots;
        public byte control; // 0 = view only, 1 = can switch; running reported in state
        public boolean on = true, loaded;
    }

    static List<Row> list(EntityPlayerMP p) {
        List<Row> out = new ArrayList<>();
        Links links = Links.get(p.world);
        int i = 0;
        for (Link l : links.list) {
            if (!l.owner.equals(p.getUniqueID()) && !Perm.has(p, Perm.SEE_ALL)) { i++; continue; } // yours (admins see all)
            Row r = new Row();
            r.index = i++; r.name = l.name; r.block = l.block; r.dim = l.dim; r.x = l.pos.getX(); r.y = l.pos.getY(); r.z = l.pos.getZ();
            World w = p.getServer().getWorld(l.dim);
            r.loaded = w != null && w.isBlockLoaded(l.pos);
            if (r.loaded) read(w.getTileEntity(l.pos), r, l);
            else r.state = "not loaded (chunk is asleep)";
            out.add(r);
        }
        return out;
    }

    private static void read(TileEntity te, Row r, Link l) {
        if (te == null) { r.state = "machine is gone"; return; }
        IEnergyStorage en = cap(te, CapabilityEnergy.ENERGY);
        if (en != null) { r.energy = en.getEnergyStored(); r.energyMax = en.getMaxEnergyStored(); }
        IFluidHandler fh = cap(te, CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY);
        if (fh != null) {
            int amt = 0, max = 0;
            for (IFluidTankProperties t : fh.getTankProperties()) {
                max += t.getCapacity();
                FluidStack fs = t.getContents();
                if (fs != null) { amt += fs.amount; if (r.fluidName.isEmpty()) r.fluidName = fs.getLocalizedName(); }
            }
            r.fluid = amt; r.fluidMax = max;
        }
        IItemHandler ih = cap(te, CapabilityItemHandler.ITEM_HANDLER_CAPABILITY);
        if (ih != null) {
            int used = 0;
            for (int s = 0; s < ih.getSlots(); s++) if (!ih.getStackInSlot(s).isEmpty()) used++;
            r.slotsUsed = used; r.slots = ih.getSlots();
        }
        Object active = Trains.call(te, "getActive");                // Mekanism IActiveState and many others
        if (active == null) active = Trains.call(te, "isActive");
        Object irc = irc(te), master = ieMaster(te);
        if (irc != null) {
            r.control = 1;
            String mode = String.valueOf(Trains.call(te, "getControlType"));
            r.on = l.savedMode == null;                             // we only store a mode while it's switched off
            r.detail = "Redstone mode " + mode;
        } else if (master != null) {
            r.control = 1;
            Optional<?> co = ieComputerOn(master);
            r.on = co == null || !co.isPresent() || Boolean.TRUE.equals(co.get());
        }
        if (active instanceof Boolean) r.state = (Boolean) active ? "running" : "idle";
        if (r.control == 1 && !r.on) r.state = "switched off";
    }

    // ---------------- switching machines off/on ----------------
    private static Class<?> ircClass;
    private static Object mekDisabled, mekHigh;

    /** Mekanism's IRedstoneControl, if this tile has one */
    static Object irc(TileEntity te) {
        try {
            if (ircClass == null) {
                ircClass = Class.forName("mekanism.common.base.IRedstoneControl");
                Class<?> e = Class.forName("mekanism.common.base.IRedstoneControl$RedstoneControl");
                mekDisabled = e.getField("DISABLED").get(null);
                mekHigh = e.getField("HIGH").get(null);
            }
            return ircClass.isInstance(te) ? te : null;
        } catch (Throwable t) { return null; }
    }

    /** Immersive Engineering multiblock master (has the `computerOn` switch computer mods use) */
    static Object ieMaster(TileEntity te) {
        try {
            if (!te.getClass().getName().startsWith("blusunrize.immersiveengineering")) return null;
            Object master = Trains.call(te, "master");
            Object m = master != null ? master : te;
            return m.getClass().getField("computerOn") != null ? m : null;
        } catch (Throwable t) { return null; }
    }

    @SuppressWarnings("unchecked")
    static Optional<Boolean> ieComputerOn(Object master) {
        try { return (Optional<Boolean>) master.getClass().getField("computerOn").get(master); } catch (Throwable t) { return null; }
    }

    static void toggle(EntityPlayerMP p, int index) {
        Links links = Links.get(p.world);
        if (!holdsTablet(p)) { p.sendStatusMessage(new TextComponentString("§cHold your Machine Tablet."), true); return; }
        if (index < 0 || index >= links.list.size()) return;
        Link l = links.list.get(index);
        if (!l.owner.equals(p.getUniqueID()) && !Perm.has(p, Perm.SEE_ALL)) return;
        World w = p.getServer().getWorld(l.dim);
        if (w == null || !w.isBlockLoaded(l.pos)) { p.sendStatusMessage(new TextComponentString("§eThat machine's chunk is asleep."), true); return; }
        TileEntity te = w.getTileEntity(l.pos);
        if (te == null) return;
        boolean nowOn;
        try {
            if (irc(te) != null) {
                Method set = ircClass.getMethod("setControlType", mekHigh.getClass());
                if (l.savedMode == null) {                                     // switch OFF: needs a redstone signal it doesn't have
                    l.savedMode = String.valueOf(ircClass.getMethod("getControlType").invoke(te));
                    set.invoke(te, mekHigh);
                    nowOn = false;
                } else {                                                       // switch ON: put its old mode back
                    Object old = mekHigh.getClass().getMethod("valueOf", String.class).invoke(null, l.savedMode);
                    set.invoke(te, old == null ? mekDisabled : old);
                    l.savedMode = null;
                    nowOn = true;
                }
            } else if (ieMaster(te) != null) {
                Object m = ieMaster(te);
                Field f = m.getClass().getField("computerOn");
                Optional<?> cur = (Optional<?>) f.get(m);
                boolean off = cur != null && cur.isPresent() && Boolean.FALSE.equals(cur.get());
                f.set(m, off ? Optional.empty() : Optional.of(Boolean.FALSE)); // off -> back to normal; normal -> forced off
                nowOn = off;
            } else {
                p.sendStatusMessage(new TextComponentString("§eThis machine can only be watched, not switched."), true);
                return;
            }
        } catch (Throwable t) {
            p.sendStatusMessage(new TextComponentString("§cCouldn't switch it: " + t), true);
            return;
        }
        te.markDirty();
        links.markDirty();
        Db.log(Action.at(nowOn ? "machine-on" : "machine-off", w, l.pos, null, p).extra(l.name + " (from the tablet)"));
        Net.CH.sendTo(new Net.MachineList(list(p)), p);
    }
}
