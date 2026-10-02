package com.dogpound.prideprism;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

/**
 * The rollback planner: what exactly will happen, before anything does. Switch kinds on/off (the plan is
 * re-worked on the server each time), look at it in the world, then go. Everything run here is a batch that
 * /pp batch undo <n> can reverse.
 */
public class GuiRollback extends GuiScreen {
    private static GuiRollback open;
    private static final String[] NAMES = {"Blocks", "Chests", "Death items", "Pets & mobs", "Take back from thieves", "Force over changes"};
    private static final String[] TIPS = {
        "blocks broken, placed, blown up, trampled — and chest/machine contents in them",
        "items taken out of / put into chests and machines",
        "give a dead player back everything they had (they must be online)",
        "bring back killed pets, named mobs, villagers and animals",
        "stolen items go back in the chest AND out of the thief's pockets",
        "also change spots someone has changed since (otherwise they're skipped)"};
    private static int opts = 1 | 2 | 4 | 8 | 16;           // remembered; force is never on by default

    private final GuiScreen back;
    private final long[] ids;
    private final String words;
    private final boolean rollback;
    private List<String> lines = new ArrayList<>();
    private int todo = -1;
    private int[][] spots = new int[0][];
    private PrideFrame f;
    private int planY, planH, lineScroll;                 // the plan text scrolls inside the panel

    public GuiRollback(GuiScreen back, long[] ids, String words, boolean rollback) {
        this.back = back; this.ids = ids; this.words = words; this.rollback = rollback;
    }

    static void receive(List<String> l, int todo, int[][] spots) {
        GuiRollback g = open;
        if (g == null) return;
        g.lines = l; g.todo = todo; g.spots = spots;
        g.initGui();
    }

    private void ask() {
        todo = -1;
        Net.CH.sendToServer(new Net.PlanAsk(ids, words, rollback, opts));
    }

    @Override
    public void initGui() {
        boolean first = open != this;
        open = this;
        f = PrideFrame.sized(width, height, 520, 340);
        buttonList.clear();
        int y = f.cy, cw = (f.cw - 8) / 2;
        for (int i = 0; i < NAMES.length; i++) {
            boolean on = (opts & (1 << i)) != 0;
            buttonList.add(new GuiPrism.Flat(i, f.cx + (i % 2) * (cw + 8), y + (i / 2) * 18, cw, 15, (on ? "☑ " : "☐ ") + NAMES[i],
                on ? (i == 5 ? 0xFFC0392B : 0xFF5B3A8E) : PrideFrame.BUTTON));
        }
        int by = f.cy + f.ch - 16;
        planY = y + ((NAMES.length + 1) / 2) * 18 + 4;
        planH = by - 4 - planY;
        buttonList.add(new GuiPrism.Flat(20, f.cx, by, 70, 16, "◀ Back", PrideFrame.BUTTON));
        buttonList.add(new GuiPrism.Flat(21, f.cx + 74, by, 120, 16, "👁 Preview in world", 0xFF24408E));
        GuiButton go = new GuiPrism.Flat(22, f.cx + f.cw - 150, by, 150, 16,
            todo < 0 ? "working it out…" : (rollback ? "✦ Roll back " : "✦ Restore ") + todo, rollback ? 0xFFC0392B : 0xFF008026);
        go.enabled = todo > 0;
        buttonList.add(go);
        if (first) ask();
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        if (b.id < NAMES.length) { opts ^= 1 << b.id; ask(); initGui(); return; }
        switch (b.id) {
            case 20: mc.displayGuiScreen(back); break;
            case 21: Preview.show(spots, 60); mc.displayGuiScreen(null);
                mc.player.sendMessage(new net.minecraft.util.text.TextComponentString("§d✦ Preview for 60 s: §agreen§d = comes back, §cred§d = goes away, §eyellow§d = changed since. Open PridePrism again to go on."));
                break;
            case 22: Net.CH.sendToServer(new Net.PlanGo()); Preview.show(new int[0][], 0); mc.displayGuiScreen(null); break;
            default: break;
        }
    }

    private List<String> wrapped() {
        List<String> out = new ArrayList<>();
        for (String l : lines) out.addAll(fontRenderer.listFormattedStringToWidth(l, f.cw - 14));
        return out;
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        int d = org.lwjgl.input.Mouse.getEventDWheel();
        if (d != 0) lineScroll = Math.max(0, lineScroll + (d > 0 ? -3 : 3));   // clamped when drawn
    }

    @Override public void onGuiClosed() { if (open == this) open = null; }
    @Override public boolean doesGuiPauseGame() { return false; }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        f.draw(this, rollback ? "Roll back" : "Restore", "§7" + (ids.length > 0 ? ids.length + " picked entries" : "everything the search found"));
        Gui.drawRect(f.x, f.y + 3, f.x + f.w, f.y + 4, rollback ? 0xFFC0392B : 0xFF008026);   // red = roll back, green = restore, under the rainbow
        PrideFrame.card(f.cx, planY, f.cw, planH, rollback ? 0xFFC0392B : 0xFF008026);
        if (todo < 0) drawString(fontRenderer, "§7checking every spot against the world…", f.cx + 4, planY + 5, 0xFFFFFF);
        else {
            List<String> wrapped = wrapped();
            int vis = Math.max(1, (planH - 6) / 11);
            lineScroll = Math.max(0, Math.min(lineScroll, wrapped.size() - vis));
            PrideFrame.clip(f.cx, planY + 1, f.cw, planH - 2);
            for (int i = 0; i < vis + 1 && lineScroll + i < wrapped.size(); i++) drawString(fontRenderer, wrapped.get(lineScroll + i), f.cx + 4, planY + 5 + i * 11, 0xFFFFFF);
            PrideFrame.unclip();
            PrideFrame.scrollbar(f.cx + f.cw - 4, planY + 2, planH - 4, lineScroll, vis, wrapped.size());
        }
        super.drawScreen(mx, my, pt);
        for (GuiButton b : buttonList)
            if (b.id < NAMES.length && b.isMouseOver()) drawHoveringText(java.util.Collections.singletonList("§7" + TIPS[b.id]), mx, my);
    }
}
