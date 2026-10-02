package com.dogpound.prideprism;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.server.permission.DefaultPermissionHandler;
import net.minecraftforge.server.permission.DefaultPermissionLevel;
import net.minecraftforge.server.permission.PermissionAPI;

/** PridePrism's admin powers as named permissions. No permissions mod installed = the old op-level-2 check, exactly. */
public final class Perm {
    public static final String ADMIN = "prideprism.admin", SEE_ALL = "prideprism.seeall", CONFIG = "prideprism.config";

    private Perm() {}

    static void register() {
        PermissionAPI.registerNode(ADMIN, DefaultPermissionLevel.OP, "Use PridePrism's admin tools (look up, roll back)");
        PermissionAPI.registerNode(SEE_ALL, DefaultPermissionLevel.OP, "See every player's machine logs, not just your own");
        PermissionAPI.registerNode(CONFIG, DefaultPermissionLevel.OP, "Change PridePrism's settings in game");
    }

    public static boolean has(ICommandSender who, String node) {
        if (who instanceof EntityPlayerMP && !(who instanceof FakePlayer) && !((EntityPlayer) who).world.isRemote
                && !(PermissionAPI.getPermissionHandler() instanceof DefaultPermissionHandler)) {
            try { return PermissionAPI.hasPermission((EntityPlayer) who, node); } catch (Throwable ignored) {}
        }
        return who != null && who.canUseCommand(2, "pp");
    }
}
