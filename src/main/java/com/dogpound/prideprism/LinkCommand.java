package com.dogpound.prideprism;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;

/** /link CODE — any player: ties the website account that showed CODE to this Minecraft player. */
public class LinkCommand extends CommandBase {
    @Override public String getName() { return "link"; }
    @Override public String getUsage(ICommandSender s) { return "/link <code from the website>"; }
    @Override public int getRequiredPermissionLevel() { return 0; }
    @Override public boolean checkPermission(MinecraftServer server, ICommandSender sender) { return true; }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 1 || args[0].length() > 16) throw new CommandException(getUsage(sender));
        Web.link(getCommandSenderAsPlayer(sender), args[0]);
    }
}
