package com.dogpound.prideprism;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Inspect mode (/pp i): left- or right-click a block to see its history instead of breaking/using it. */
public final class Inspect {
    static final Set<UUID> inspecting = new HashSet<>();

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLeft(PlayerInteractEvent.LeftClickBlock e) {
        if (handle(e.getEntityPlayer(), e.getPos())) e.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRight(PlayerInteractEvent.RightClickBlock e) {
        if (handle(e.getEntityPlayer(), e.getPos().offset(e.getFace()))) e.setCanceled(true); // right = the space on that face
    }

    private boolean handle(EntityPlayer p, BlockPos pos) {
        if (p.world.isRemote || !inspecting.contains(p.getUniqueID())) return false;
        Filter f = new Filter();
        f.dim = p.world.provider.getDimension();
        f.cx = pos.getX(); f.cy = pos.getY(); f.cz = pos.getZ();
        f.exactBlock = true;
        show((EntityPlayerMP) p, f, "History of " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
        return true;
    }

    /** Runs the search off the game thread, then prints the answer back on it. */
    static void show(EntityPlayerMP p, Filter f, String title) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        new Thread(() -> {
            String[] lines;
            try {
                List<Action> found = Db.lookup(f, 12);
                lines = new String[found.size() + 1];
                lines[0] = "§d§l✦ " + title + (found.isEmpty() ? " §7- nothing logged" : "");
                for (int i = 0; i < found.size(); i++) lines[i + 1] = line(found.get(i));
            } catch (Exception ex) {
                lines = new String[]{"§cPridePrism can't reach its database: " + ex.getMessage()};
            }
            String[] out = lines;
            server.addScheduledTask(() -> { for (String l : out) p.sendMessage(new TextComponentString(l)); });
        }, "PridePrism-lookup").start();
    }

    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("MMM d HH:mm");

    static String line(Action a) {
        String what = a.item != null ? a.amount + "x " + a.item.replace("minecraft:", "")
            : a.action.equals("place") ? a.blockNew : a.blockOld != null ? a.blockOld : "";
        return "§8" + ago(a.time) + " §b" + a.who + " §" + color(a.action) + a.action + " §f"
            + (what == null ? "" : what.replace("minecraft:", "")) + (a.extra != null ? " §7" + a.extra : "")
            + (a.rolledBack ? " §8(rolled back)" : "") + " §8@" + a.x + "," + a.y + "," + a.z;
    }

    static String ago(long t) {
        long s = (System.currentTimeMillis() - t) / 1000;
        if (s < 60) return s + "s ago";
        if (s < 3600) return s / 60 + "m ago";
        if (s < 86400) return s / 3600 + "h ago";
        synchronized (CLOCK) { return CLOCK.format(new Date(t)); }
    }

    private static char color(String action) {
        switch (action) {
            case "break": case "explode": case "item-take": case "death": return 'c';
            case "place": case "item-put": case "craft": return 'a';
            case "door": case "trapdoor": case "gate": case "button": case "lever": case "use": return 'e';
            default: return '6';
        }
    }
}
