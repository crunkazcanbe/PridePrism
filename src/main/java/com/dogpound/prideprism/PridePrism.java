package com.dogpound.prideprism;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** PridePrism — logs everything that happens in the world to MySQL/MariaDB, with inspect, lookup and rollback. */
@Mod(modid = "prideprism", name = "PridePrism", version = "0.1.0", acceptableRemoteVersions = "*")
public class PridePrism {
    public static final Logger LOG = LogManager.getLogger("PridePrism");
    public static String dbUrl, dbUser, dbPassword;
    public static boolean logBlocks, logUse, logContainers, logItems, logEntities, logChat, logTrains, logCombat, logPlayer, logMenus, logMovement;
    public static int moveSeconds;

    static Configuration config;

    /** read every setting from the file into the fields above (at start, and live from ⚙ Settings) */
    static void loadConfig() {
        Configuration c = config;
        dbUrl = c.getString("url", "database", "jdbc:mariadb://127.0.0.1:3306/prideprism", "MySQL or MariaDB address (jdbc:mariadb://host:port/database)");
        dbUser = c.getString("user", "database", "prideprism", "database user");
        dbPassword = c.getString("password", "database", "", "database password");
        logBlocks = c.getBoolean("blocks", "log", true, "placing, breaking, explosions, fluids, buckets");
        logUse = c.getBoolean("use", "log", true, "doors, trapdoors, gates, buttons, levers, blocks that open a screen");
        logContainers = c.getBoolean("containers", "log", true, "exactly which items go in and out of chests and machines");
        logItems = c.getBoolean("items", "log", true, "drops, pickups, crafting");
        logEntities = c.getBoolean("deaths", "log", true, "player deaths and kills");
        logChat = c.getBoolean("chat", "log", true, "chat and commands");
        logCombat = c.getBoolean("combat", "log", true, "every hit a player makes or takes: who, what, with what, how much");
        logPlayer = c.getBoolean("player", "log", true, "eating, item use, punching blocks, clicking mobs, xp, levels, advancements, sleep, respawn, mounts, taming, breeding, ender pearls, tools breaking");
        logMenus = c.getBoolean("menus", "log", true, "every screen opened/closed, every button and slot clicked, keybinds pressed (names only, never typed text)");
        logMovement = c.getBoolean("movement", "log", true, "where each player is, every few seconds while they move");
        moveSeconds = c.getInt("movementSeconds", "log", 10, 1, 600, "how often movement is logged");
        logTrains = c.getBoolean("trains", "log", true, "Immersive Railroading + Traincraft: odometers, speeds, fuel, freight, passengers, trips, drivers");
        if (c.hasChanged()) c.save();
    }

    @Mod.EventHandler
    public void init(net.minecraftforge.fml.common.event.FMLInitializationEvent e) {
        LOG.info("[PridePrism] loaded OK");
        Perm.register();                    // Forge: permission nodes can only be registered from init on
    }

    @Mod.EventHandler
    public void pre(FMLPreInitializationEvent e) {
        config = new Configuration(e.getSuggestedConfigurationFile());
        loadConfig();
        com.dogpound.prideprism.cfgbridge.ConfigBridge.initPlain("prideprism", config, PridePrism::loadConfig);   // ⚙ Settings in the menu
        MinecraftForge.EVENT_BUS.register(new Events());
        MinecraftForge.EVENT_BUS.register(new MoreEvents());
        MinecraftForge.EVENT_BUS.register(new GriefTracker());
        MinecraftForge.EVENT_BUS.register(new Inspect());
        MinecraftForge.EVENT_BUS.register(new Trains());
        MinecraftForge.EVENT_BUS.register(new Machines());
        Net.init();
        if (net.minecraftforge.fml.common.FMLCommonHandler.instance().getSide().isClient()) { ClientHooks.register(); MinecraftForge.EVENT_BUS.register(new ClientSpy()); }
    }

    @Mod.EventHandler
    public void serverStart(FMLServerStartingEvent e) {
        GriefTracker.reloadFromDatabase(e.getServer());
        Db.start();
        Web.start();
        e.registerServerCommand(new PrismCommand());
        e.registerServerCommand(new LinkCommand());
    }

    @Mod.EventHandler
    public void serverStop(FMLServerStoppedEvent e) {
        Web.stop();
        Db.stop();
    }
}
