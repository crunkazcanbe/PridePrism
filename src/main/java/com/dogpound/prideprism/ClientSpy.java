package com.dogpound.prideprism;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * Client side of "log every damn thing": screens opened/closed, every button clicked (any mod's), every slot
 * clicked, and named keybinds pressed while playing. Only NAMES of keybinds — never typed text (chat/commands are
 * already logged server-side). Sent to the server, which writes it (rate-limited) to the same log.
 */
public final class ClientSpy {
    private static String open;

    private static void send(String kind, String text) {
        if (Minecraft.getMinecraft().getConnection() != null) Net.CH.sendToServer(new Net.ClientAct(kind, text));
    }

    private static String screen(GuiScreen g) {
        return g == null ? "none" : g.getClass().getName();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void opened(GuiOpenEvent e) {
        if (e.isCanceled() || Minecraft.getMinecraft().player == null) return;
        String now = e.getGui() == null ? null : screen(e.getGui());
        if (open != null && !open.equals(now)) send("close", open);
        if (now != null && !now.equals(open)) send("open", now);
        open = now;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void button(GuiScreenEvent.ActionPerformedEvent.Pre e) {
        if (e.isCanceled() || Minecraft.getMinecraft().player == null) return;
        String label = e.getButton().displayString == null || e.getButton().displayString.isEmpty() ? "(icon)" : e.getButton().displayString;
        send("button", "\"" + label + "\" id " + e.getButton().id + " [" + e.getButton().getClass().getName() + "] on " + screen(e.getGui()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void slot(GuiScreenEvent.MouseInputEvent.Pre e) {
        if (e.isCanceled() || !(e.getGui() instanceof GuiContainer) || !Mouse.getEventButtonState() || Mouse.getEventButton() < 0) return;
        Slot s = ((GuiContainer) e.getGui()).getSlotUnderMouse();
        if (s == null) return;
        ItemStack held = Minecraft.getMinecraft().player.inventory.getItemStack(), in = s.getStack();
        String btn = Mouse.getEventButton() == 0 ? "left" : Mouse.getEventButton() == 1 ? "right" : "middle";
        if (GuiScreen.isShiftKeyDown()) btn = "shift-" + btn;
        send("slot", btn + " slot " + s.slotNumber + " (" + s.inventory.getName() + "): " + desc(in) + (held.isEmpty() ? "" : ", holding " + desc(held)) + " on " + screen(e.getGui()));
    }

    @SubscribeEvent
    public void key(InputEvent.KeyInputEvent e) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.player == null || !Keyboard.getEventKeyState()) return;
        int k = Keyboard.getEventKey();
        for (KeyBinding kb : mc.gameSettings.keyBindings)
            if (kb.getKeyCode() == k && k != 0) { send("key", kb.getKeyDescription() + " [" + kb.getKeyCategory() + "]"); return; }
    }

    private static String desc(ItemStack s) {
        return s.isEmpty() ? "empty" : s.getCount() + "x " + s.getDisplayName() + " (" + s.getItem().getRegistryName() + ")";
    }
}
