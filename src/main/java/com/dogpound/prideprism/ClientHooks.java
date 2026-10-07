package com.dogpound.prideprism;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

/** Client side only: the Home key (free in the pack) opens the PridePrism screen. */
public final class ClientHooks {
    private static final KeyBinding OPEN = new KeyBinding("Open PridePrism", Keyboard.KEY_HOME, "PridePrism");

    private static final int BUTTON_ID = 0x50524953; // "PRIS"

    static void register() {
        ClientRegistry.registerKeyBinding(OPEN);
        MinecraftForge.EVENT_BUS.register(new ClientHooks());
        MinecraftForge.EVENT_BUS.register(new Preview());
    }

    /** Requested: a PridePrism button in the inventory, top-left, beside RealmCoin's column */
    @SubscribeEvent(priority = net.minecraftforge.fml.common.eventhandler.EventPriority.LOWEST)
    public void onInventory(net.minecraftforge.client.event.GuiScreenEvent.InitGuiEvent.Post e) {
        if (e.getGui() instanceof net.minecraft.client.gui.inventory.GuiInventory)
            e.getButtonList().add(new PrismButton(BUTTON_ID));
    }

    /** keep it beside RealmCoin's top button every frame (the recipe book slides the whole inventory) */
    @SubscribeEvent
    public void onDraw(net.minecraftforge.client.event.GuiScreenEvent.DrawScreenEvent.Pre e) {
        if (!(e.getGui() instanceof net.minecraft.client.gui.inventory.GuiInventory)) return;
        java.util.List<net.minecraft.client.gui.GuiButton> list = buttons(e.getGui());
        if (list == null) return;
        PrismButton ours = null;
        net.minecraft.client.gui.GuiButton top = null;
        for (net.minecraft.client.gui.GuiButton b : list) {
            if (b instanceof PrismButton) ours = (PrismButton) b;
            else if (b.visible && b.getClass().getName().startsWith("com.dogpound.realmcoin") && (top == null || b.y < top.y)) top = b;
        }
        if (ours == null) return;
        net.minecraft.client.gui.inventory.GuiContainer g = (net.minecraft.client.gui.inventory.GuiContainer) e.getGui();
        if (top != null) { ours.x = top.x - 18; ours.y = top.y; }
        else { ours.x = g.getGuiLeft() - 20; ours.y = g.getGuiTop() + 4; }
    }

    @SubscribeEvent
    public void onClick(net.minecraftforge.client.event.GuiScreenEvent.ActionPerformedEvent.Pre e) {
        if (e.getButton() instanceof PrismButton) { open(); e.setCanceled(true); }
    }

    private static java.lang.reflect.Field BUTTONS;

    @SuppressWarnings("unchecked")
    private static java.util.List<net.minecraft.client.gui.GuiButton> buttons(net.minecraft.client.gui.GuiScreen s) {
        try {
            if (BUTTONS == null) BUTTONS = net.minecraftforge.fml.common.ObfuscationReflectionHelper.findField(net.minecraft.client.gui.GuiScreen.class, "field_146292_n");
            return (java.util.List<net.minecraft.client.gui.GuiButton>) BUTTONS.get(s);
        } catch (Throwable t) { return null; }
    }

    /** 16x16 slot with a little rainbow prism, like the other inventory buttons */
    static final class PrismButton extends net.minecraft.client.gui.GuiButton {
        private static final int[] RAINBOW = {0xFFE40303, 0xFFFF8C00, 0xFFFFED00, 0xFF008026, 0xFF24408E, 0xFF732982};

        PrismButton(int id) { super(id, 0, 0, 16, 16, ""); }

        @Override
        public void drawButton(Minecraft mc, int mx, int my, float pt) {
            if (!visible) return;
            hovered = mx >= x && my >= y && mx < x + width && my < y + height;
            drawRect(x, y, x + 16, y + 16, 0xFF8B8B8B);                         // inventory-slot look
            drawRect(x, y, x + 16, y + 1, 0xFF373737); drawRect(x, y, x + 1, y + 16, 0xFF373737);
            drawRect(x + 15, y, x + 16, y + 16, 0xFFFFFFFF); drawRect(x, y + 15, x + 16, y + 16, 0xFFFFFFFF);
            for (int row = 0; row < 8; row++) {                                // a triangle prism...
                int half = row / 2 + 1;
                drawRect(x + 8 - half, y + 3 + row, x + 8 + half, y + 4 + row, 0xFFEEEEEE);
            }
            for (int i = 0; i < 6; i++) drawRect(x + 9 + (i > 2 ? 1 : 0), y + 7 + i, x + 15, y + 8 + i, RAINBOW[i]); // ...splitting light
            if (hovered) {
                drawRect(x, y, x + 16, y + 16, 0x40FFFFFF);
                net.minecraft.client.gui.GuiScreen s = mc.currentScreen;
                if (s != null) s.drawHoveringText("\u00a7dPridePrism \u00a77- what happened here", mx, my);
            }
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        Minecraft mc = Minecraft.getMinecraft();
        if (e.phase == TickEvent.Phase.END && mc.player != null && mc.currentScreen == null && OPEN.isPressed()) open();
    }

    static void open() { open(""); }

    /** words from /pp gui (the land map sends box:… / at:… plus t: / p:) preset the screen */
    static void open(String words) {
        if (words != null && !words.trim().isEmpty()) GuiPrism.preset(words.trim());
        Minecraft.getMinecraft().displayGuiScreen(new GuiPrism());
    }

    static void machines(java.util.List<Machines.Row> rows) {
        GuiMachines.rows = rows;
        Minecraft mc = Minecraft.getMinecraft();
        if (!(mc.currentScreen instanceof GuiMachines)) mc.displayGuiScreen(new GuiMachines());
    }

    static void results(Net.Results r) {
        GuiPrism.results(r);
    }
}
