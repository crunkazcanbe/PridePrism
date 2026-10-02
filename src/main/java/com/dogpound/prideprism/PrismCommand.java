package com.dogpound.prideprism;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;

/**
 * /pp i                      inspect mode on/off (click blocks to see their history)
 * /pp l  [p: a: r: t: b: i:] look up what happened
 * /pp rb [p: a: r: t: b:]    roll back block changes      /pp rs [...] put them back
 * /pp status                 database connection + queue
 */
public class PrismCommand extends CommandBase {
    private static final List<String> BLOCK_ACTIONS = Arrays.asList("break", "place", "explode", "fluid");

    @Override public String getName() { return "pp"; }
    @Override public List<String> getAliases() { return Collections.singletonList("prideprism"); }
    @Override public String getUsage(ICommandSender s) { return "/pp gui | i | l <p: a: r: t: b: i:> | rb <...> | rs <...> | batch [undo <#>] | status"; }
    @Override public int getRequiredPermissionLevel() { return 2; }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) throw new CommandException(getUsage(sender));
        String sub = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        if (sub.equals("status")) {
            sender.sendMessage(new TextComponentString("§dPridePrism: §f" + Db.status + " §7(" + Db.queued() + " waiting to be written)"));
            return;
        }
        EntityPlayerMP p = getCommandSenderAsPlayer(sender);
        BlockPos at = p.getPosition();
        Filter f = Filter.parse(rest, p.dimension, at.getX(), at.getY(), at.getZ());
        switch (sub) {
            case "i":
                if (!Inspect.inspecting.remove(p.getUniqueID())) {
                    Inspect.inspecting.add(p.getUniqueID());
                    p.sendMessage(new TextComponentString("§dInspect ON §7- click any block to see its history. /pp i again to stop."));
                } else p.sendMessage(new TextComponentString("§dInspect OFF"));
                return;
            case "l":
                Inspect.show(p, f, "Lookup " + String.join(" ", rest));
                return;
            case "gui":                                                     // /pp gui [box:… at:… t:… p:…] — the land map opens it on an area
                Net.sendOpen(p, String.join(" ", rest));
                return;
            case "batch": {                                                  // rollback history
                if (args.length > 2 && args[1].equals("undo")) { Rollback.undoBatch(p, parseLong(args[2])); return; }
                new Thread(() -> {
                    try { List<String> h = Rollback.history(15); server.addScheduledTask(() -> { p.sendMessage(new TextComponentString("§d✦ Rollback history §7(/pp batch undo <#> reverses one)")); h.forEach(l -> p.sendMessage(new TextComponentString(l))); }); }
                    catch (Exception ex) { server.addScheduledTask(() -> p.sendMessage(new TextComponentString("§cCan't reach the database: " + ex.getMessage()))); }
                }, "PridePrism-history").start();
                return;
            }
            case "rb": case "rs":
                List<String> can = new ArrayList<>(Rollback.BLOCK);          // everything the rollback engine can put back
                can.addAll(Rollback.CHEST); can.add("death"); can.add("kill");
                if (f.actions.isEmpty()) f.actions.addAll(can);
                else f.actions.retainAll(can);
                change(server, p, f, sub.equals("rb"));
                return;
            default:
                throw new CommandException(getUsage(sender));
        }
    }

    /** Search off-thread, then put blocks back (rollback: newest first) or re-apply them (restore: oldest first). */
    private static void change(MinecraftServer server, EntityPlayerMP p, Filter f, boolean rollback) {
        new Thread(() -> {
            List<Action> found;
            try { found = Db.lookup(f, 100000); } catch (Exception ex) {
                server.addScheduledTask(() -> p.sendMessage(new TextComponentString("§cCan't reach the database: " + ex.getMessage())));
                return;
            }
            // same engine as the planner screen: conflict check, a batch in the history, a few hundred per tick
            server.addScheduledTask(() -> Rollback.start(p, Rollback.plan(server, found, rollback, new Rollback.Options())));
        }, "PridePrism-rollback").start();
    }

    /** the screen's row buttons: 0 teleport, 1 undo this one change, 2 redo it */
    static void rowAction(EntityPlayerMP p, Action a, int what) {
        MinecraftServer server = p.getServer();
        if (what == 0) {
            if (p.dimension != a.dim) p.changeDimension(a.dim);
            p.connection.setPlayerLocation(a.x + 0.5, a.y + 1, a.z + 0.5, p.rotationYaw, p.rotationPitch);
            return;
        }
        if (!BLOCK_ACTIONS.contains(a.action)) { p.sendMessage(new TextComponentString("\u00a7eOnly block changes can be undone.")); return; }
        boolean rollback = what == 1;
        if (a.rolledBack == rollback) { p.sendMessage(new TextComponentString("\u00a7eAlready " + (rollback ? "undone." : "in place."))); return; }
        boolean ok = apply(server.getWorld(a.dim), a, rollback);
        p.sendMessage(new TextComponentString(ok ? "\u00a7d" + (rollback ? "Undid " : "Redid ") + a.action + " at " + a.x + " " + a.y + " " + a.z : "\u00a7cThat block no longer exists in this pack."));
        if (ok) new Thread(() -> { try { Db.markRolledBack(java.util.Collections.singletonList(a), rollback); } catch (Exception ignored) { } }).start();
    }

    @SuppressWarnings("deprecation")
    private static boolean apply(World w, Action a, boolean rollback) {
        String id = rollback ? a.blockOld : a.blockNew;
        int meta = rollback ? a.metaOld : a.metaNew;
        if (a.action.equals("break") || a.action.equals("explode")) { if (!rollback) { id = "minecraft:air"; meta = 0; } }
        if (id == null || !Block.REGISTRY.containsKey(new ResourceLocation(id))) return false;
        IBlockState s = Block.REGISTRY.getObject(new ResourceLocation(id)).getStateFromMeta(meta);
        BlockPos pos = a.pos();
        w.setBlockState(pos, s, 3);
        net.minecraft.nbt.NBTTagCompound nbt = rollback ? a.nbtOld : null;
        if (nbt != null) {
            TileEntity te = w.getTileEntity(pos);
            if (te != null) {
                nbt = nbt.copy();
                nbt.setInteger("x", pos.getX()); nbt.setInteger("y", pos.getY()); nbt.setInteger("z", pos.getZ());
                te.readFromNBT(nbt); // chests get their items back, machines their contents
                te.markDirty();
            }
        }
        return true;
    }
}
