package com.dogpound.prideprism;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

/**
 * The one look every DogPound menu shares (her ask 2026-09-28: "all the menus consistent, look alike… centered…
 * even if you had to add a scroll bar" — modelled on PrideGuard's hub, the one she liked: dark panel, rainbow bar,
 * square tiles). Copied into each mod (same file, own package) so no mod needs another to run.
 *
 * Use: PrideFrame f = PrideFrame.fit(width, height);  f.draw(screen, "Title", "right side text");
 * then lay content out inside f.cx, f.cy, f.cw, f.ch (the area under the title bar).
 */
public final class PrideFrame {
    public static final int[] RAINBOW = {0xFFE40303, 0xFFFF8C00, 0xFFFFED00, 0xFF008026, 0xFF24408E, 0xFF732982, 0xFF5BCEFA, 0xFFF5A9B8};
    public static final int PINK = 0xFFF5A9B8, BLUE = 0xFF5BCEFA, DIM = 0xFF8A8499, TILE = 0xFF1C1530, TILE_ON = 0xFF6A3FA0, BUTTON = 0xFF2A2238;
    public static final int HEADER = 30;                 // title bar height inside the panel

    public final int x, y, w, h;                         // the panel
    public final int cx, cy, cw, ch;                     // content area (inside the padding, under the title)

    private PrideFrame(int x, int y, int w, int h) {
        this.x = x; this.y = y; this.w = w; this.h = h;
        cx = x + 10; cy = y + HEADER + 4; cw = w - 20; ch = h - HEADER - 12;
    }

    /** the standard panel: grows with the window up to 1100×620, at least 480×290 (or the whole screen if smaller) */
    public static PrideFrame fit(int sw, int sh) { return sized(sw, sh, 1100, 620); }

    /** a smaller dialog: centred, at most maxW×maxH, shrinks to fit small windows */
    public static PrideFrame sized(int sw, int sh, int maxW, int maxH) {
        int w = Math.max(Math.min(sw - 16, Math.min(480, maxW)), Math.min(sw - 24, maxW));
        int h = Math.max(Math.min(sh - 16, Math.min(290, maxH)), Math.min(sh - 24, maxH));
        return new PrideFrame((sw - w) / 2, (sh - h) / 2, w, h);
    }

    /** dimmed world, the panel, the rainbow bar, "✦ title" on the left, `right` on the right, a hairline under them */
    public void draw(GuiScreen screen, String title, String right) {
        FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
        screen.drawDefaultBackground();
        gradient(x, y, x + w, y + h, 0xF2140E22, 0xF2080510);
        int sw = w / RAINBOW.length;
        for (int i = 0; i < RAINBOW.length; i++) Gui.drawRect(x + i * sw, y, i == RAINBOW.length - 1 ? x + w : x + (i + 1) * sw, y + 3, RAINBOW[i]);
        fr.drawStringWithShadow("§l✦ " + title, x + 10, y + 11, 0xFFFFFF);
        if (right != null && !right.isEmpty()) fr.drawStringWithShadow(right, x + w - 10 - fr.getStringWidth(right), y + 11, 0xFFFFFF);
        Gui.drawRect(x + 6, y + HEADER - 3, x + w - 6, y + HEADER - 2, 0x40FFFFFF);
    }

    /** a square tile like PrideGuard's hub: coloured top edge, lighter when hovered */
    public static void tile(int x, int y, int w, int h, int accent, boolean hover, boolean on) {
        Gui.drawRect(x, y, x + w, y + h, on ? TILE_ON : hover ? 0xFF2A2140 : TILE);
        Gui.drawRect(x, y, x + w, y + 2, accent);
    }

    /** a flat button in the house style; returns true when the mouse is on it */
    public static boolean button(int x, int y, int w, int h, String label, int color, int mx, int my) {
        boolean over = mx >= x && my >= y && mx < x + w && my < y + h;
        Gui.drawRect(x, y, x + w, y + h, over ? brighten(color) : color);
        Gui.drawRect(x, y + h - 1, x + w, y + h, 0x60000000);
        if (over) Gui.drawRect(x, y, x + w, y + 1, 0x80FFFFFF);
        FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
        fr.drawStringWithShadow(label, x + (w - fr.getStringWidth(label)) / 2f, y + (h - 8) / 2f, 0xFFFFFF);
        return over;
    }

    /** a dark card with a coloured top line */
    public static void card(int x, int y, int w, int h, int edge) {
        Gui.drawRect(x, y, x + w, y + h, 0x70000000);
        Gui.drawRect(x, y, x + w, y + 1, edge);
    }

    /** a thin scroll bar on the right of an area of height h showing `visible` of `total` at `scroll` */
    public static void scrollbar(int x, int y, int h, int scroll, int visible, int total) {
        if (total <= visible) return;
        int knob = Math.max(10, h * visible / total), ky = y + (h - knob) * scroll / Math.max(1, total - visible);
        Gui.drawRect(x, y, x + 3, y + h, 0x30FFFFFF);
        Gui.drawRect(x, ky, x + 3, ky + knob, PINK);
    }

    /** only draw inside this GUI rectangle until unclip() (content that scrolls) */
    public static void clip(int x, int y, int w, int h) {
        Minecraft mc = Minecraft.getMinecraft();
        int f = new ScaledResolution(mc).getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x * f, mc.displayHeight - (y + h) * f, Math.max(0, w * f), Math.max(0, h * f));
    }

    public static void unclip() { GL11.glDisable(GL11.GL_SCISSOR_TEST); }

    public static int brighten(int c) {
        int r = Math.min(255, ((c >> 16) & 255) + 40), g = Math.min(255, ((c >> 8) & 255) + 40), b = Math.min(255, (c & 255) + 40);
        return (c & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    public static void gradient(int left, int top, int right, int bottom, int from, int to) {
        float a1 = (from >> 24 & 255) / 255f, r1 = (from >> 16 & 255) / 255f, g1 = (from >> 8 & 255) / 255f, b1 = (from & 255) / 255f;
        float a2 = (to >> 24 & 255) / 255f, r2 = (to >> 16 & 255) / 255f, g2 = (to >> 8 & 255) / 255f, b2 = (to & 255) / 255f;
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        Tessellator t = Tessellator.getInstance();
        BufferBuilder b = t.getBuffer();
        b.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        b.pos(right, top, 0).color(r1, g1, b1, a1).endVertex();
        b.pos(left, top, 0).color(r1, g1, b1, a1).endVertex();
        b.pos(left, bottom, 0).color(r2, g2, b2, a2).endVertex();
        b.pos(right, bottom, 0).color(r2, g2, b2, a2).endVertex();
        t.draw();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
    }
}
