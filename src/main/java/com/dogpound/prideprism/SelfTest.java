package com.dogpound.prideprism;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.entity.passive.EntityWolf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/**
 * Dev check (only when a file "prideprism-selftest" is in the game folder, i.e. the throwaway test world): runs the
 * rollback engine for real against the world, no database needed. Logs PRIDEPRISM ROLLBACK PASS/FAIL.
 */
final class SelfTest {
    private static boolean ran;

    static void run(EntityPlayerMP p) {
        if (ran || !new File("prideprism-selftest").isFile()) return;
        ran = true;
        List<String> fails = new ArrayList<>();
        WorldServer w = p.getServerWorld();
        BlockPos base = p.getPosition().add(3, 0, 3);
        Rollback.Options all = new Rollback.Options();
        try {
            // 1. a broken stone block comes back
            BlockPos a = base;
            Action br = Action.at("break", w, a, null, p).before(Blocks.STONE.getDefaultState(), null);
            w.setBlockToAir(a);
            Rollback.Plan plan = Rollback.plan(p.getServer(), new ArrayList<>(Collections.singletonList(br)), true, all);
            if (plan.conflicts != 0) fails.add("untouched break seen as a conflict");
            for (Rollback.Step s : plan.todo()) Rollback.apply(p.getServer(), s, true, all);
            if (w.getBlockState(a).getBlock() != Blocks.STONE) fails.add("broken stone didn't come back");

            // 2. somebody built there since: conflict, skipped unless forced
            BlockPos b = base.add(1, 0, 0);
            Action br2 = Action.at("break", w, b, null, p).before(Blocks.STONE.getDefaultState(), null);
            w.setBlockState(b, Blocks.DIRT.getDefaultState());
            plan = Rollback.plan(p.getServer(), new ArrayList<>(Collections.singletonList(br2)), true, all);
            if (plan.conflicts != 1 || !plan.todo().isEmpty()) fails.add("changed spot wasn't held back as a conflict");
            if (w.getBlockState(b).getBlock() != Blocks.DIRT) fails.add("conflict spot was touched");

            // 3. chest theft: 5 diamonds go back in, and out of the thief's pockets
            BlockPos c = base.add(2, 0, 0);
            w.setBlockState(c, Blocks.CHEST.getDefaultState());
            p.inventory.addItemStackToInventory(new ItemStack(Items.DIAMOND, 5));
            Action take = Action.at("item-take", w, c, null, p);
            take.item = "minecraft:diamond"; take.amount = 5;
            plan = Rollback.plan(p.getServer(), new ArrayList<>(Collections.singletonList(take)), true, all);
            for (Rollback.Step s : plan.todo()) Rollback.apply(p.getServer(), s, true, all);
            TileEntityChest chest = (TileEntityChest) w.getTileEntity(c);
            int inChest = 0, onPlayer = 0;
            for (int i = 0; chest != null && i < chest.getSizeInventory(); i++) if (chest.getStackInSlot(i).getItem() == Items.DIAMOND) inChest += chest.getStackInSlot(i).getCount();
            for (int i = 0; i < p.inventory.getSizeInventory(); i++) if (p.inventory.getStackInSlot(i).getItem() == Items.DIAMOND) onPlayer += p.inventory.getStackInSlot(i).getCount();
            if (inChest != 5) fails.add("chest got " + inChest + " diamonds back, not 5");
            if (onPlayer != 0) fails.add("thief still has " + onPlayer + " diamonds");

            // 4. death: the player gets their stuff back
            Action death = Action.at("death", w, p.getPosition(), null, p);
            death.nbtOld = new NBTTagCompound();
            NBTTagList inv = new NBTTagList();
            inv.appendTag(new ItemStack(Items.GOLDEN_APPLE, 3).writeToNBT(new NBTTagCompound()));
            death.nbtOld.setTag("inv", inv);
            death.nbtOld.setString("victim", p.getUniqueID().toString());
            plan = Rollback.plan(p.getServer(), new ArrayList<>(Collections.singletonList(death)), true, all);
            for (Rollback.Step s : plan.todo()) Rollback.apply(p.getServer(), s, true, all);
            int apples = 0;
            for (int i = 0; i < p.inventory.getSizeInventory(); i++) if (p.inventory.getStackInSlot(i).getItem() == Items.GOLDEN_APPLE) { apples += p.inventory.getStackInSlot(i).getCount(); p.inventory.setInventorySlotContents(i, ItemStack.EMPTY); }
            if (apples != 3) fails.add("death items: got " + apples + " golden apples back, not 3");

            // 5. a killed, named wolf comes back
            BlockPos d = base.add(0, 0, 3);
            EntityWolf wolf = new EntityWolf(w);
            wolf.setCustomNameTag("Selftest Pup");
            NBTTagCompound wn = new NBTTagCompound();
            wn.setString("id", "minecraft:wolf");
            wolf.writeToNBT(wn);
            Action kill = Action.at("kill", w, d, null, p);
            kill.nbtOld = wn;
            plan = Rollback.plan(p.getServer(), new ArrayList<>(Collections.singletonList(kill)), true, all);
            for (Rollback.Step s : plan.todo()) Rollback.apply(p.getServer(), s, true, all);
            List<EntityWolf> pups = w.getEntitiesWithinAABB(EntityWolf.class, new AxisAlignedBB(d).grow(3), x -> "Selftest Pup".equals(x.getCustomNameTag()));
            if (pups.size() != 1) fails.add(pups.size() + " wolves came back, not 1");
            pups.forEach(x -> x.setDead());

            // tidy up
            if (chest != null) chest.clear();
            for (BlockPos q : new BlockPos[]{a, b, c}) w.setBlockToAir(q);
        } catch (Throwable t) {
            fails.add("exception " + t);
            t.printStackTrace();
        }
        griefAt = p.getServer().getTickCounter() + 100;   // the lava part needs the chunks to have ticked once (fresh world)
        griefPlayer = p;
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new SelfTest());
        System.out.println(fails.isEmpty() ? "[PridePrism] PRIDEPRISM ROLLBACK PASS: blocks, conflicts, chest theft, death items, pets" : "[PridePrism] PRIDEPRISM ROLLBACK FAIL: " + fails);
    }

    private static int griefAt;
    private static EntityPlayerMP griefPlayer;

    @net.minecraftforge.fml.common.eventhandler.SubscribeEvent
    public void tick(net.minecraftforge.fml.common.gameevent.TickEvent.ServerTickEvent e) {
        if (e.phase != net.minecraftforge.fml.common.gameevent.TickEvent.Phase.END || griefPlayer == null || griefPlayer.getServer().getTickCounter() < griefAt) return;
        EntityPlayerMP p = griefPlayer;
        griefPlayer = null;
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(this);
        List<String> fails = new ArrayList<>();
        WorldServer w = p.getServerWorld();
        BlockPos base = p.getPosition().add(3, 0, 3);
        try {
                // 6. lava grief: pour, flow and burn all get pinned on the player who poured
                BlockPos l = base.add(-4, 0, 0);
                GriefTracker.stamp(w, l, p.getName(), p.getUniqueID().toString());
                w.setBlockState(l, Blocks.LAVA.getDefaultState(), 2);
                if (!"pour".equals(GriefTracker.lastKind) || !p.getName().equals(GriefTracker.lastWho)) fails.add("lava pour not pinned on the player (" + GriefTracker.lastKind + "/" + GriefTracker.lastWho + ")");
                w.setBlockState(l.west(), Blocks.FLOWING_LAVA.getDefaultState(), 2);
                if (!"flow".equals(GriefTracker.lastKind)) fails.add("lava flow not traced back (" + GriefTracker.lastKind + ")");
                BlockPos plank = l.west().west(), fire = plank.up();
                w.setBlockState(plank, Blocks.PLANKS.getDefaultState(), 2);
                w.setBlockState(fire, Blocks.FIRE.getDefaultState(), 2);
                if (!"ignite".equals(GriefTracker.lastKind)) fails.add("fire near their lava not pinned (" + GriefTracker.lastKind + ")");
                w.setBlockState(plank, Blocks.AIR.getDefaultState(), 2);
                if (!"burn".equals(GriefTracker.lastKind)) fails.add("burnt plank not logged (" + GriefTracker.lastKind + ")");
                for (BlockPos q : new BlockPos[]{l, l.west(), plank, fire}) w.setBlockState(q, Blocks.AIR.getDefaultState(), 2);

        } catch (Throwable t) { fails.add("exception " + t); }
        System.out.println(fails.isEmpty() ? "[PridePrism] PRIDEPRISM GRIEF PASS: lava pour, flow, fire and burn all pinned on the player" : "[PridePrism] PRIDEPRISM GRIEF FAIL: " + fails);
    }
}
