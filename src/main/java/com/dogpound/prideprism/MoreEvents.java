package com.dogpound.prideprism;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.living.AnimalTameEvent;
import net.minecraftforge.event.entity.living.BabyEntitySpawnEvent;
import net.minecraftforge.event.entity.living.EnderTeleportEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AdvancementEvent;
import net.minecraftforge.event.entity.player.AnvilRepairEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerDestroyItemEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import net.minecraftforge.event.entity.player.PlayerWakeUpEvent;
import net.minecraftforge.event.entity.player.PlayerPickupXpEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Her ask 2026-09-28: log EVERY damn thing. Everything Events.java doesn't already catch, by category switch. */
public final class MoreEvents {
    private final Map<UUID, BlockPos> lastPos = new HashMap<>();
    private final Map<UUID, Integer> lastLevel = new HashMap<>();   // this Forge has no level-change event: checked each second

    private static boolean real(Entity e) { return e != null && !e.world.isRemote; }
    private static String name(Entity e) { return e == null ? "?" : e instanceof EntityPlayer ? e.getName() : e.getName() + " [" + net.minecraft.entity.EntityList.getKey(e) + "]"; }
    private static String held(EntityPlayer p) {
        ItemStack s = p.getHeldItemMainhand();
        return s.isEmpty() ? "bare hand" : s.getDisplayName() + " (" + s.getItem().getRegistryName() + ")";
    }
    private static void log(String action, EntityPlayer p, BlockPos where, String extra) {
        Db.log(Action.at(action, p.world, where, null, p).extra(extra));
    }

    // ---------------- combat ----------------
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void attack(AttackEntityEvent e) {
        if (!PridePrism.logCombat || !real(e.getEntityPlayer())) return;
        log("attack", e.getEntityPlayer(), e.getTarget().getPosition(), name(e.getTarget()) + " with " + held(e.getEntityPlayer()));
    }

    /** damage a player takes, or deals — with the source and the amount after armour isn't known yet, so the raw hit */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void hurt(LivingHurtEvent e) {
        if (!PridePrism.logCombat) return;
        EntityLivingBase hit = e.getEntityLiving();
        Entity by = e.getSource().getTrueSource();
        if (!real(hit) || (!(hit instanceof EntityPlayer) && !(by instanceof EntityPlayer))) return;
        EntityPlayer p = hit instanceof EntityPlayer ? (EntityPlayer) hit : (EntityPlayer) by;
        String what = String.format("%s hurt %s for %.1f (%s)", by == null ? "#" + e.getSource().getDamageType() : name(by), name(hit), e.getAmount(), e.getSource().getDamageType());
        Db.log(Action.at(hit instanceof EntityPlayer ? "hurt" : "hit", hit.world, hit.getPosition(), null, p).extra(what));
    }

    // ---------------- the player's own doings ----------------
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void punch(PlayerInteractEvent.LeftClickBlock e) {
        if (!PridePrism.logPlayer || !real(e.getEntityPlayer())) return;
        Db.log(Action.at("punch", e.getWorld(), e.getPos(), null, e.getEntityPlayer()).before(e.getWorld().getBlockState(e.getPos()), null).extra(held(e.getEntityPlayer())));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void useItem(PlayerInteractEvent.RightClickItem e) {
        if (!PridePrism.logPlayer || !real(e.getEntityPlayer()) || e.getItemStack().isEmpty()) return;
        Db.log(Action.at("use-item", e.getWorld(), e.getPos(), null, e.getEntityPlayer()).item(e.getItemStack(), 1).extra(e.getItemStack().getDisplayName() + (e.getHand() == EnumHand.OFF_HAND ? " (off hand)" : "")));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void useEntity(PlayerInteractEvent.EntityInteract e) {
        if (!PridePrism.logPlayer || !real(e.getEntityPlayer()) || e.getHand() != EnumHand.MAIN_HAND) return;
        log("interact", e.getEntityPlayer(), e.getTarget().getPosition(), name(e.getTarget()) + " with " + held(e.getEntityPlayer()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void finishUsing(LivingEntityUseItemEvent.Finish e) {
        if (!PridePrism.logPlayer || !(e.getEntityLiving() instanceof EntityPlayer) || !real(e.getEntityLiving())) return;
        EntityPlayer p = (EntityPlayer) e.getEntityLiving();
        Db.log(Action.at("consume", p.world, p.getPosition(), null, p).item(e.getItem(), 1).extra(e.getItem().getDisplayName()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void toolBroke(PlayerDestroyItemEvent e) {
        if (!PridePrism.logPlayer || !real(e.getEntityPlayer())) return;
        Db.log(Action.at("item-break", e.getEntityPlayer().world, e.getEntityPlayer().getPosition(), null, e.getEntityPlayer()).item(e.getOriginal(), 1).extra(e.getOriginal().getDisplayName()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void xp(PlayerPickupXpEvent e) {
        if (!PridePrism.logPlayer || !real(e.getEntityPlayer())) return;
        log("xp", e.getEntityPlayer(), e.getEntityPlayer().getPosition(), "+" + e.getOrb().getXpValue() + " xp");
    }

    @SubscribeEvent
    public void advancement(AdvancementEvent e) {
        if (!PridePrism.logPlayer || !real(e.getEntityPlayer()) || e.getAdvancement().getDisplay() == null) return;
        log("advancement", e.getEntityPlayer(), e.getEntityPlayer().getPosition(), e.getAdvancement().getDisplay().getTitle().getUnformattedText() + " (" + e.getAdvancement().getId() + ")");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void sleep(PlayerSleepInBedEvent e) {
        if (!PridePrism.logPlayer || !real(e.getEntityPlayer())) return;
        log("sleep", e.getEntityPlayer(), e.getPos(), e.getResultStatus() == null ? "went to bed" : "couldn't sleep: " + e.getResultStatus());
    }

    @SubscribeEvent
    public void wake(PlayerWakeUpEvent e) {
        if (!PridePrism.logPlayer || !real(e.getEntityPlayer())) return;
        log("wake", e.getEntityPlayer(), e.getEntityPlayer().getPosition(), null);
    }

    @SubscribeEvent
    public void respawn(PlayerEvent.PlayerRespawnEvent e) {
        if (!PridePrism.logPlayer || !real(e.player)) return;
        log("respawn", e.player, e.player.getPosition(), e.isEndConquered() ? "left the End" : null);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void mount(EntityMountEvent e) {
        if (!PridePrism.logPlayer || !(e.getEntityMounting() instanceof EntityPlayer) || e.getWorldObj().isRemote) return;
        log(e.isMounting() ? "mount" : "dismount", (EntityPlayer) e.getEntityMounting(), e.getEntityMounting().getPosition(), name(e.getEntityBeingMounted()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void tame(AnimalTameEvent e) {
        if (!PridePrism.logPlayer || !real(e.getTamer())) return;
        log("tame", e.getTamer(), e.getAnimal().getPosition(), name(e.getAnimal()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void breed(BabyEntitySpawnEvent e) {
        if (!PridePrism.logPlayer || e.getCausedByPlayer() == null || !real(e.getCausedByPlayer())) return;
        log("breed", e.getCausedByPlayer(), e.getParentA().getPosition(), name(e.getParentA()) + " + " + name(e.getParentB()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void pearl(EnderTeleportEvent e) {
        if (!PridePrism.logPlayer || !(e.getEntityLiving() instanceof EntityPlayer) || !real(e.getEntityLiving())) return;
        log("teleport", (EntityPlayer) e.getEntityLiving(), e.getEntityLiving().getPosition(), String.format("ender pearl to %.0f %.0f %.0f", e.getTargetX(), e.getTargetY(), e.getTargetZ()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void trample(BlockEvent.FarmlandTrampleEvent e) {
        if (!PridePrism.logBlocks || !(e.getEntity() instanceof EntityPlayer) || e.getWorld().isRemote) return;
        Db.log(Action.at("trample", e.getWorld(), e.getPos(), null, (EntityPlayer) e.getEntity()).before(e.getState(), null));
    }

    @SubscribeEvent
    public void smelt(PlayerEvent.ItemSmeltedEvent e) {
        if (!PridePrism.logItems || !real(e.player) || e.smelting.isEmpty()) return;
        Db.log(Action.at("smelt", e.player.world, e.player.getPosition(), null, e.player).item(e.smelting, e.smelting.getCount()));
    }

    @SubscribeEvent
    public void brew(net.minecraftforge.event.brewing.PlayerBrewedPotionEvent e) {
        if (!PridePrism.logItems || !real(e.getEntityPlayer())) return;
        Db.log(Action.at("brew", e.getEntityPlayer().world, e.getEntityPlayer().getPosition(), null, e.getEntityPlayer()).item(e.getStack(), e.getStack().getCount()));
    }

    @SubscribeEvent
    public void anvil(AnvilRepairEvent e) {
        if (!PridePrism.logItems || !real(e.getEntityPlayer())) return;
        Db.log(Action.at("anvil", e.getEntityPlayer().world, e.getEntityPlayer().getPosition(), null, e.getEntityPlayer()).item(e.getItemResult(), 1)
            .extra(e.getItemInput().getDisplayName() + " + " + e.getIngredientInput().getDisplayName() + " -> " + e.getItemResult().getDisplayName()));
    }

    // ---------------- movement trail ----------------
    @SubscribeEvent
    public void tick(TickEvent.PlayerTickEvent e) {
        if (e.phase == TickEvent.Phase.END && PridePrism.logPlayer && real(e.player) && e.player.ticksExisted % 20 == 0) {
            Integer was = lastLevel.put(e.player.getUniqueID(), e.player.experienceLevel);
            if (was != null && was != e.player.experienceLevel)
                log("level", e.player, e.player.getPosition(), (e.player.experienceLevel > was ? "+" : "") + (e.player.experienceLevel - was) + " levels (now " + e.player.experienceLevel + ")");
        }
        if (e.phase != TickEvent.Phase.END || !PridePrism.logMovement || !real(e.player) || e.player.ticksExisted % (PridePrism.moveSeconds * 20) != 0) return;
        BlockPos now = e.player.getPosition(), was = lastPos.put(e.player.getUniqueID(), now);
        if (was != null && was.distanceSq(now) < 4) return; // stood still
        log("move", e.player, now, e.player.isRiding() ? "riding " + name(e.player.getRidingEntity()) : e.player.isElytraFlying() ? "flying" : null);
    }

    @SubscribeEvent
    public void left(PlayerEvent.PlayerLoggedOutEvent e) { lastPos.remove(e.player.getUniqueID()); lastLevel.remove(e.player.getUniqueID()); budget.remove(e.player.getUniqueID()); }

    // ---------------- menus (reported by the player's game; see ClientSpy) ----------------
    private static final Map<UUID, long[]> budget = new HashMap<>();   // {second, count}

    /** at most 40 menu events a second per player, so a macro or a bad client can't flood the database */
    static void fromClient(EntityPlayerMP p, String kind, String text) {
        if (!PridePrism.logMenus) return;
        long sec = System.currentTimeMillis() / 1000;
        long[] b = budget.computeIfAbsent(p.getUniqueID(), k -> new long[2]);
        if (b[0] != sec) { b[0] = sec; b[1] = 0; }
        if (++b[1] > 40) return;
        String action = kind.equals("open") ? "menu-open" : kind.equals("close") ? "menu-close" : kind.equals("button") ? "menu-button"
            : kind.equals("slot") ? "menu-slot" : kind.equals("key") ? "key" : null;
        if (action == null) return;
        Db.log(Action.at(action, p.world, p.getPosition(), null, p).extra(text));
    }
}
