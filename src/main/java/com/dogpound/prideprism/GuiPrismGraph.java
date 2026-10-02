package com.dogpound.prideprism;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

/**
 * The log as a galaxy ball (her ask 2026-09-28): a glowing centre with every category floating around it on a
 * sphere. Drag to spin (it keeps drifting), wheel to zoom, click a dot to fly into it (categories -> actions ->
 * players -> their entries), click an entry to read everything about it, right-click / Backspace to go back out.
 */
public class GuiPrismGraph extends GuiScreen {
    private static final int[] PRIDE = {0xE40303, 0xFF8C00, 0xFFED00, 0x008026, 0x24408E, 0x732982, 0x5BCEFA, 0xF5A9B8, 0xFFFFFF, 0x613915, 0x3AD27F, 0xB57EDC};
    private static GuiPrismGraph open;

    private final List<String> path = new ArrayList<>();
    private final List<Node> nodes = new ArrayList<>();
    private final float[][] stars = new float[160][3];
    private float yaw = 30, pitch = -15, vYaw = 0.25f, vPitch, zoom = 1f;
    private boolean dragging, moved;
    private int lastX, lastY, frame;
    private float appear = 1f;            // 0 -> 1 as a new level blooms out of the centre
    private Node flying;                  // the dot we're flying into
    private float fly;                    // 0 -> 1
    private boolean waiting = true;
    private String error = "";
    private Node selected, hovered;
    private int crumbHover = -1;
    private PrideFrame f;
    private Node storyOf;                 // which entry the story box is scrolled for
    private int storyScroll, sx0, sy0, sw0, sh0;   // story box scroll + where it was drawn (for the wheel)

    static final class Node {
        String key, label, detail;
        long count;
        float x, y, z;       // on the unit sphere
        float sx, sy, sz, r; // on screen this frame
        int color, i;
    }

    public GuiPrismGraph() {
        Random rnd = new Random(7);
        for (float[] s : stars) { s[0] = rnd.nextFloat(); s[1] = rnd.nextFloat(); s[2] = 0.3f + rnd.nextFloat() * 0.7f; }
    }

    @Override
    public void initGui() {
        open = this;
        f = PrideFrame.fit(width, height);
        Keyboard.enableRepeatEvents(true);
        if (nodes.isEmpty()) ask();
    }

    @Override
    public void onGuiClosed() {
        if (open == this) open = null;
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }

    private void ask() {
        waiting = true;
        Net.CH.sendToServer(new Net.GraphAsk(path));
    }

    /** the server's answer: lay the dots out evenly on a sphere (golden-angle spiral) */
    static void receive(List<String> forPath, List<String[]> data, String err) {
        GuiPrismGraph g = open;
        if (g == null || !forPath.equals(g.path)) return;
        g.waiting = false;
        g.error = err == null ? "" : err;
        g.nodes.clear();
        g.selected = null;
        int n = data.size();
        int base = g.path.isEmpty() ? -1 : Math.floorMod(g.path.get(0).hashCode(), PRIDE.length);
        for (int i = 0; i < n; i++) {
            String[] d = data.get(i);
            Node k = new Node();
            k.key = d[0]; k.label = d[1]; k.detail = d[3]; k.i = i;
            try { k.count = Long.parseLong(d[2]); } catch (NumberFormatException e) { k.count = 1; }
            double y = n == 1 ? 0 : 1 - 2.0 * i / (n - 1), rad = Math.sqrt(1 - y * y), th = i * Math.PI * (3 - Math.sqrt(5));
            k.x = (float) (Math.cos(th) * rad); k.y = (float) y; k.z = (float) (Math.sin(th) * rad);
            k.color = base < 0 ? PRIDE[i % PRIDE.length] : shade(PRIDE[base], i);
            g.nodes.add(k);
        }
        g.appear = 0;
        g.fly = 0;
        g.flying = null;
    }

    private static int shade(int c, int i) {
        float f = 0.65f + 0.35f * ((i * 37) % 100) / 100f;
        int r = Math.min(255, (int) (((c >> 16) & 255) * f + 40)), gg = Math.min(255, (int) (((c >> 8) & 255) * f + 40)), b = Math.min(255, (int) ((c & 255) * f + 40));
        return (r << 16) | (gg << 8) | b;
    }

    private void goInto(Node n) {
        if (path.size() >= 3) { selected = n; return; }   // entries: show the story
        flying = n;
        fly = 0.001f;
        path.add(n.key);
        ask();
    }

    private void back(int toDepth) {
        if (path.size() <= toDepth) { if (toDepth == 0 && path.isEmpty()) mc.displayGuiScreen(new GuiPrism()); return; }
        while (path.size() > toDepth) path.remove(path.size() - 1);
        selected = null;
        ask();
    }

    // ---------------- input ----------------
    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        int w = Mouse.getEventDWheel();
        if (w == 0) return;
        int mx = Mouse.getEventX() * width / mc.displayWidth, my = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (selected != null && mx >= sx0 && mx < sx0 + sw0 && my >= sy0 && my < sy0 + sh0) { storyScroll += w > 0 ? -2 : 2; return; }   // wheel over the story scrolls it
        zoom = Math.max(0.45f, Math.min(3.2f, zoom * (w > 0 ? 1.12f : 1 / 1.12f)));
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) throws java.io.IOException {
        if (button == 1) { back(path.size() - 1); return; }
        if (crumbHover >= 0) { back(crumbHover); return; }
        super.mouseClicked(mx, my, button);
        dragging = true; moved = false;
        lastX = mx; lastY = my;
    }

    @Override
    protected void mouseClickMove(int mx, int my, int button, long held) {
        if (!dragging) return;
        int dx = mx - lastX, dy = my - lastY;
        if (Math.abs(dx) + Math.abs(dy) > 0) moved = true;
        vYaw = dx * 0.6f; vPitch = dy * 0.6f;
        yaw += vYaw; pitch = Math.max(-85, Math.min(85, pitch + vPitch));
        lastX = mx; lastY = my;
    }

    @Override
    protected void mouseReleased(int mx, int my, int state) {
        if (dragging && !moved && hovered != null && flying == null) goInto(hovered);
        dragging = false;
    }

    @Override
    protected void keyTyped(char c, int key) throws java.io.IOException {
        if (key == Keyboard.KEY_BACK) back(path.size() - 1);
        else super.keyTyped(c, key);
    }

    @Override
    protected void actionPerformed(GuiButton b) {}

    // ---------------- drawing ----------------
    @Override
    public void drawScreen(int mx, int my, float pt) {
        frame++;
        f.draw(this, "Log Galaxy", "§7" + nodes.size() + (path.size() >= 3 ? " entries" : " dots") + " §8| §7zoom " + Math.round(zoom * 100) + "%");
        PrideFrame.gradient(f.cx, f.cy, f.cx + f.cw, f.cy + f.ch, 0xFF0B0716, 0xFF1A0F2E);
        PrideFrame.clip(f.cx, f.cy, f.cw, f.ch);           // the whole galaxy stays inside the panel, however far it's zoomed
        for (float[] s : stars) {
            int x = f.cx + (int) (s[0] * f.cw), y = f.cy + (int) (s[1] * f.ch);
            int a = (int) (s[2] * (150 + 100 * Math.sin(frame * 0.03 + s[0] * 50)));
            drawRect(x, y, x + 1, y + 1, (Math.max(0, Math.min(255, a)) << 24) | 0xFFFFFF);
        }

        if (!dragging) {                                   // drift, easing back to a slow spin
            yaw += vYaw; pitch = Math.max(-85, Math.min(85, pitch + vPitch));
            vYaw = vYaw * 0.95f + 0.25f * 0.05f; vPitch *= 0.9f;
        }
        if (appear < 1) appear = Math.min(1, appear + 0.06f);
        if (fly > 0 && fly < 1) fly = Math.min(1, fly + 0.07f);

        float cx = f.cx + f.cw / 2f, cy = f.cy + f.ch / 2f + 6;
        float R = Math.min(f.cw, f.ch) * 0.33f * zoom;
        boolean inside = mx >= f.cx && mx < f.cx + f.cw && my >= f.cy && my < f.cy + f.ch;
        float flyZoom = flying != null ? 1 + fly * fly * 4 : 1;       // rush toward the clicked dot
        float bloom = ease(appear);

        double cyw = Math.cos(Math.toRadians(yaw)), syw = Math.sin(Math.toRadians(yaw));
        double cp = Math.cos(Math.toRadians(pitch)), sp = Math.sin(Math.toRadians(pitch));
        float offX = 0, offY = 0;
        long max = 1;
        for (Node n : nodes) max = Math.max(max, n.count);
        for (Node n : nodes) {
            float bob = 1 + 0.05f * (float) Math.sin(frame * 0.04 + n.i * 1.7);
            double x = n.x * bob, y = n.y * bob, z = n.z * bob;
            double x1 = x * cyw + z * syw, z1 = -x * syw + z * cyw;   // yaw
            double y2 = y * cp - z1 * sp, z2 = y * sp + z1 * cp;      // pitch
            double s = 3.0 / (3.0 - z2);
            n.sx = (float) (x1 * s); n.sy = (float) (y2 * s); n.sz = (float) z2;
            n.r = (float) ((2.5 + 6.5 * Math.log10(n.count + 1) / Math.log10(max + 1)) * s * Math.min(1.6, zoom));
        }
        if (flying != null) { offX = -flying.sx * R * fly; offY = -flying.sy * R * fly; }
        for (Node n : nodes) {
            n.sx = cx + offX + n.sx * R * bloom * flyZoom;
            n.sy = cy + offY + n.sy * R * bloom * flyZoom;
        }
        float alpha = flying != null ? 1 - fly : 1;

        // faint globe
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        for (int ring = 0; ring < 3; ring++) globe(cx + offX, cy + offY, R * bloom * flyZoom, ring, cyw, syw, cp, sp, 0.10f * alpha);

        List<Node> order = new ArrayList<>(nodes);
        order.sort(Comparator.comparingDouble(n -> n.sz));
        // spokes from the centre
        Tessellator t = Tessellator.getInstance();
        BufferBuilder b = t.getBuffer();
        GL11.glLineWidth(1.2f);
        b.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        for (Node n : order) {
            float a = (0.10f + 0.25f * (n.sz + 1) / 2) * alpha;
            b.pos(cx + offX, cy + offY, 0).color(1f, 1f, 1f, a * 0.5f).endVertex();
            b.pos(n.sx, n.sy, 0).color(r(n.color), g(n.color), bl(n.color), a).endVertex();
        }
        t.draw();

        // the centre sun
        int pulse = (int) (2 * Math.sin(frame * 0.08));
        circle(cx + offX, cy + offY, (14 + pulse) * Math.min(1.5f, zoom) * flyZoom, 0xF5A9B8, 0.18f * alpha);
        circle(cx + offX, cy + offY, 8 * Math.min(1.5f, zoom) * flyZoom, 0xFFFFFF, 0.9f * alpha);

        // dots back to front, with a glow
        hovered = null;
        for (Node n : order) {
            float depth = 0.45f + 0.55f * (n.sz + 1) / 2;
            circle(n.sx, n.sy, n.r * 2.2f, n.color, 0.16f * depth * alpha);
            circle(n.sx, n.sy, n.r, n.color, depth * alpha);
            if (n == selected) ring(n.sx, n.sy, n.r + 3, 0xFFFFFF, alpha);
            if (flying == null && inside && (mx - n.sx) * (mx - n.sx) + (my - n.sy) * (my - n.sy) <= (n.r + 3) * (n.r + 3)) hovered = n; // front-most wins
        }
        if (hovered != null) ring(hovered.sx, hovered.sy, hovered.r + 2 + (frame % 20) / 10f, 0xFFFFFF, 0.8f);
        GlStateManager.enableTexture2D();

        // labels on the near side
        if (flying == null && appear > 0.7f)
            for (Node n : order) {
                if (n.sz < -0.25f && n != hovered) continue;
                String s = n.label + " §7" + n.count;
                int w = fontRenderer.getStringWidth(s);
                int a = (int) (255 * Math.min(1, (n.sz + 0.35f) * 1.5f));
                if (a > 8) fontRenderer.drawStringWithShadow(s, n.sx - w / 2f, n.sy + n.r + 3, (Math.max(8, Math.min(255, n == hovered ? 255 : a)) << 24) | 0xFFFFFF);
            }
        String centre = path.isEmpty() ? "✦ Everything" : path.get(path.size() - 1);
        fontRenderer.drawStringWithShadow(centre, cx + offX - fontRenderer.getStringWidth(centre) / 2f, cy + offY - 22 * Math.min(1.5f, zoom), 0xFFFFFFFF);
        PrideFrame.unclip();

        drawCrumbs(mx, my);
        int mid = f.cx + f.cw / 2, bottom = f.cy + f.ch;
        if (waiting) drawCenteredString(fontRenderer, "§7reaching into the log…", mid, bottom - 20, 0xFFFFFF);
        else if (!error.isEmpty()) drawCenteredString(fontRenderer, fontRenderer.trimStringToWidth("§c" + error, f.cw - 8), mid, bottom - 20, 0xFFFFFF);
        else if (nodes.isEmpty()) drawCenteredString(fontRenderer, "§7nothing logged here yet", mid, bottom - 20, 0xFFFFFF);
        else drawCenteredString(fontRenderer, fontRenderer.trimStringToWidth("§8drag to spin · wheel to zoom · click a dot to dive in · right-click / Backspace to go back", f.cw - 8), mid, bottom - 10, 0xFFFFFF);

        if (selected != null) drawStory(selected);
        if (hovered != null && flying == null) {
            List<String> tip = new ArrayList<>();
            tip.add("§f" + hovered.label + " §d" + hovered.count);
            for (String line : hovered.detail.split("\n")) { tip.add("§7" + line); if (tip.size() > 6) break; }
            tip.add(path.size() >= 3 ? "§bclick to read it" : "§bclick to dive in");
            drawHoveringText(tip, mx, my);
        }
        super.drawScreen(mx, my, pt);
    }

    private void drawCrumbs(int mx, int my) {
        crumbHover = -1;
        int x = f.cx + 4, y = f.cy + 4;
        List<String> parts = new ArrayList<>();
        parts.add("✦ Everything");
        parts.addAll(path);
        for (int i = 0; i < parts.size(); i++) {
            String s = parts.get(i);
            int w = fontRenderer.getStringWidth(s);
            boolean over = i < parts.size() - 1 && mx >= x - 2 && mx < x + w + 2 && my >= y - 2 && my < y + 10;
            if (over) crumbHover = i;
            fontRenderer.drawStringWithShadow(s, x, y, i == parts.size() - 1 ? 0xFFF5A9B8 : over ? 0xFFFFFFFF : 0xFF5BCEFA);
            x += w + 4;
            if (i < parts.size() - 1) { fontRenderer.drawStringWithShadow("›", x, y, 0xFF777777); x += 10; }
        }
    }

    private void drawStory(Node n) {
        String[] lines = n.detail.split("\n");
        int w = 0;
        for (String l : lines) w = Math.max(w, fontRenderer.getStringWidth(l));
        w = Math.min(w + 16, f.cw / 2);
        int y = f.cy + 18, maxH = f.cy + f.ch - 24 - y;         // stays inside the panel; long stories scroll
        int vis = Math.max(1, Math.min(lines.length, (maxH - 10) / 11));
        int h = vis * 11 + 10, x = f.cx + f.cw - w - 4;
        if (n != storyOf) { storyOf = n; storyScroll = 0; }
        storyScroll = Math.max(0, Math.min(storyScroll, lines.length - vis));
        sx0 = x; sy0 = y; sw0 = w; sh0 = h;
        Gui.drawRect(x, y, x + w, y + h, 0xE01C1530);
        PrideFrame.card(x, y, w, h, PrideFrame.BLUE);
        Gui.drawRect(x, y + h - 1, x + w, y + h, PrideFrame.PINK);
        for (int i = 0; i < vis && storyScroll + i < lines.length; i++)
            fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth(lines[storyScroll + i], w - 16), x + 6, y + 6 + i * 11, storyScroll + i == 0 ? 0xFFFFFFFF : 0xFFCCCCDD);
        PrideFrame.scrollbar(x + w - 5, y + 3, h - 6, storyScroll, vis, lines.length);
    }

    // ---------------- little GL helpers ----------------
    private static float ease(float t) { return 1 - (1 - t) * (1 - t) * (1 - t); }
    private static float r(int c) { return ((c >> 16) & 255) / 255f; }
    private static float g(int c) { return ((c >> 8) & 255) / 255f; }
    private static float bl(int c) { return (c & 255) / 255f; }

    private static void circle(float x, float y, float rad, int c, float a) {
        Tessellator t = Tessellator.getInstance();
        BufferBuilder b = t.getBuffer();
        b.begin(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_COLOR);
        b.pos(x, y, 0).color(r(c), g(c), bl(c), a).endVertex();
        for (int i = 0; i <= 18; i++) {
            double th = i * Math.PI * 2 / 18;
            b.pos(x + Math.cos(th) * rad, y + Math.sin(th) * rad, 0).color(r(c), g(c), bl(c), a * 0.85f).endVertex();
        }
        t.draw();
    }

    private static void ring(float x, float y, float rad, int c, float a) {
        Tessellator t = Tessellator.getInstance();
        BufferBuilder b = t.getBuffer();
        b.begin(GL11.GL_LINE_LOOP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < 24; i++) {
            double th = i * Math.PI * 2 / 24;
            b.pos(x + Math.cos(th) * rad, y + Math.sin(th) * rad, 0).color(r(c), g(c), bl(c), a).endVertex();
        }
        t.draw();
    }

    /** one great circle of the globe (equator, and two through the poles), spun like the dots */
    private static void globe(float cx, float cy, float R, int ring, double cyw, double syw, double cp, double sp, float a) {
        Tessellator t = Tessellator.getInstance();
        BufferBuilder b = t.getBuffer();
        b.begin(GL11.GL_LINE_LOOP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < 48; i++) {
            double th = i * Math.PI * 2 / 48, x, y, z;
            if (ring == 0) { x = Math.cos(th); y = 0; z = Math.sin(th); }
            else if (ring == 1) { x = Math.cos(th); y = Math.sin(th); z = 0; }
            else { x = 0; y = Math.sin(th); z = Math.cos(th); }
            double x1 = x * cyw + z * syw, z1 = -x * syw + z * cyw, y2 = y * cp - z1 * sp, z2 = y * sp + z1 * cp, s = 3.0 / (3.0 - z2);
            b.pos(cx + x1 * s * R, cy + y2 * s * R, 0).color(0.36f, 0.81f, 0.98f, (float) (a * (0.5 + 0.5 * (z2 + 1) / 2))).endVertex();
        }
        t.draw();
    }
}
