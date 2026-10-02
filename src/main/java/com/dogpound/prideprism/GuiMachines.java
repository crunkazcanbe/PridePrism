package com.dogpound.prideprism;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Mouse;

/** The Machine Tablet screen: every wireless machine as a card with its energy, tank, slots and an on/off switch. */
public class GuiMachines extends net.minecraft.client.gui.GuiScreen {
    static List<Machines.Row> rows = new ArrayList<>();
    private PrideFrame f;
    private int scroll, refresh;
    private static final int CARD = 34;

    @Override
    public void initGui() {
        f = PrideFrame.sized(width, height, 640, 420);
    }

    private int visible() { return Math.max(1, f.ch / CARD); }
    private int cardW() { return f.cw - 6; }                 // room for the scroll bar
    private int toggleX() { return f.cx + cardW() - 56; }

    @Override
    public void updateScreen() {
        if (++refresh % 40 == 0) Net.CH.sendToServer(new Net.MachineToggle(-1)); // live: fresh numbers every 2 s
    }

    @Override public boolean doesGuiPauseGame() { return false; }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        int visible = visible();
        if (d != 0) scroll = Math.max(0, Math.min(Math.max(0, rows.size() - visible), scroll + (d > 0 ? -1 : 1)));
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) throws IOException {
        super.mouseClicked(mx, my, button);
        int visible = visible();
        for (int i = 0; i < visible && scroll + i < rows.size(); i++) {
            Machines.Row r = rows.get(scroll + i);
            int cy = f.cy + i * CARD, bx = toggleX();
            if (r.control == 1 && mx >= bx && mx < bx + 52 && my >= cy + 9 && my < cy + 25) {
                mc.getSoundHandler().playSound(net.minecraft.client.audio.PositionedSoundRecord.getMasterRecord(net.minecraft.init.SoundEvents.UI_BUTTON_CLICK, 1f));
                Net.CH.sendToServer(new Net.MachineToggle(r.index));
            }
        }
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        String count = "§7" + rows.size() + " wireless machine" + (rows.size() == 1 ? "" : "s");
        f.draw(this, "Machine Tablet", count);
        if (rows.isEmpty())
            drawCenteredString(fontRenderer, fontRenderer.trimStringToWidth("§7No wireless machines yet - right-click one with a Wireless Transmitter", f.cw), f.x + f.w / 2, f.y + f.h / 2, 0xFFFFFF);
        int visible = visible();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visible)));   // the list can shrink while open
        PrideFrame.clip(f.cx, f.cy, f.cw, f.ch);
        for (int i = 0; i < visible && scroll + i < rows.size(); i++) card(rows.get(scroll + i), f.cy + i * CARD, mx, my);
        PrideFrame.unclip();
        PrideFrame.scrollbar(f.cx + f.cw - 3, f.cy, f.ch, scroll, visible, rows.size());
        super.drawScreen(mx, my, pt);
    }

    private void card(Machines.Row r, int y, int mx, int my) {
        int x = f.cx, w = cardW();
        PrideFrame.card(x, y, w, CARD - 3, PrideFrame.BLUE);
        int dot = !r.loaded ? 0xFF555555 : r.state.equals("running") ? 0xFF2ECC71 : r.state.equals("switched off") ? 0xFFE40303 : 0xFFFFED00;
        drawRect(x, y, x + 3, y + CARD - 3, dot);
        icon(stack(r.block), x + 6, y + 7);
        drawString(fontRenderer, "§f" + r.name, x + 26, y + 3, 0xFFFFFF);
        String where = "§8" + r.x + " " + r.y + " " + r.z + (r.dim != 0 ? " dim " + r.dim : "") + "  §7" + r.state;
        drawString(fontRenderer, fontRenderer.trimStringToWidth(where, w - 120), x + 26, y + 13, 0xFFFFFF);
        int barX = x + 26, barW = w - 26 - 72, barY = y + 23;
        if (r.energy >= 0 && r.energyMax > 0) bar(barX, barY, barW / 2 - 2, r.energy / (double) r.energyMax, 0xFFFF8C00, fmt(r.energy) + " FE");
        if (r.fluid >= 0 && r.fluidMax > 0) bar(barX + barW / 2 + 2, barY, barW / 2 - 2, r.fluid / (double) r.fluidMax, 0xFF24408E, r.fluidName.isEmpty() ? r.fluid + " mB" : r.fluidName);
        else if (r.slotsUsed >= 0) bar(barX + barW / 2 + 2, barY, barW / 2 - 2, r.slots == 0 ? 0 : r.slotsUsed / (double) r.slots, 0xFF732982, r.slotsUsed + "/" + r.slots + " slots");
        if (r.control == 1 && r.loaded) {
            PrideFrame.button(toggleX(), y + 9, 52, 16, r.on ? "ON" : "OFF", r.on ? 0xFF008026 : 0xFFB02020, mx, my);
        } else if (r.loaded) {
            drawString(fontRenderer, "§8watch only", toggleX(), y + 13, 0xFFFFFF);
        }
    }

    private void bar(int x, int y, int w, double frac, int color, String label) {
        drawRect(x, y, x + w, y + 7, 0xFF222222);
        drawRect(x, y, x + (int) (w * Math.max(0, Math.min(1, frac))), y + 7, color);
        GlStateManager.pushMatrix();
        GlStateManager.scale(0.5F, 0.5F, 1F);
        drawString(fontRenderer, label, (x + 2) * 2, (y + 1) * 2 + 1, 0xFFFFFF);
        GlStateManager.popMatrix();
    }

    private static String fmt(long v) {
        return v >= 1_000_000_000 ? String.format("%.1fG", v / 1e9) : v >= 1_000_000 ? String.format("%.1fM", v / 1e6) : v >= 1_000 ? String.format("%.1fk", v / 1e3) : String.valueOf(v);
    }

    private static ItemStack stack(String id) {
        Block b = id == null ? null : Block.getBlockFromName(id);
        if (b != null) { Item it = Item.getItemFromBlock(b); if (it != net.minecraft.init.Items.AIR) return new ItemStack(it); }
        Item it = id == null ? null : Item.REGISTRY.getObject(new ResourceLocation(id));
        return it == null ? ItemStack.EMPTY : new ItemStack(it);
    }

    private void icon(ItemStack s, int x, int y) {
        if (s.isEmpty()) return;
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableDepth();
        try { itemRender.renderItemAndEffectIntoGUI(s, x, y); } catch (Throwable ignored) { }
        GlStateManager.disableDepth();
        RenderHelper.disableStandardItemLighting();
    }
}
