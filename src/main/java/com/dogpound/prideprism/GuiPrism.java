package com.dogpound.prideprism;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** The PridePrism screen: filters on the left, a timeline of everything that happened on the right. */
public class GuiPrism extends GuiScreen {
    private static final int[] RAINBOW = {0xFFE40303, 0xFFFF8C00, 0xFFFFED00, 0xFF008026, 0xFF24408E, 0xFF732982};
    /** chip -> the logged actions it stands for */
    private static final Map<String, String[]> CHIPS = Kinds.ALL;
    private static final String[] TIMES = {"all time", "1h", "1d", "1w"};
    private static final String[] RADII = {"20", "100", "500", "everywhere"};

    // remembered between openings
    private static final boolean[] on = new boolean[CHIPS.size()];
    private static int time = 1, radius = 0, page, selected = -1, scroll;
    private static String player = "", thing = "";
    static String area = "";                                                   // "box:…" / "at:… r:…" from the land map ("" = use the radius)

    /** the land map opened us on an area: keep its box/at words, and take its time and player */
    static void preset(String words) {
        StringBuilder a = new StringBuilder();
        for (String w : words.split("\\s+")) {
            if (w.startsWith("box:") || w.startsWith("at:") || w.startsWith("r:")) a.append(w).append(' ');
            else if (w.startsWith("p:")) player = w.substring(2);
            else if (w.startsWith("t:")) { for (int i = 0; i < TIMES.length; i++) if (TIMES[i].equals(w.substring(2))) time = i; }
        }
        area = a.toString().trim();
        rows = new ArrayList<>();
        picked.clear();
    }
    static List<Action> rows = new ArrayList<>();
    static final java.util.Set<Long> picked = new java.util.LinkedHashSet<>();   // ticked rows, for the rollback planner
    private static int anchor = -1;                                                   // shift-click ranges from here
    private static String lastWords = "";
    static boolean more, waiting;
    static String status = "";

    private GuiTextField playerBox, thingBox;
    private PrideFrame f;
    private int lx, lw, listX, listY, listW, listH, by, leftScroll, leftH;
    private final List<GuiButton> left = new ArrayList<>();          // the filter column: scrolls on its own if the window is short
    private final List<Integer> leftY = new ArrayList<>();           // their unscrolled y
    private static final int ROW = 18;

    static { Arrays.fill(on, true); }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        f = PrideFrame.fit(width, height);
        lx = f.cx; lw = 124;
        listX = lx + lw + 10; listY = f.cy; listW = f.cx + f.cw - 6 - listX; listH = f.ch - 60;   // 6 px on the right for the scroll bar
        by = f.cy + f.ch - 16;
        buttonList.clear();
        left.clear(); leftY.clear();
        int y = f.cy;
        playerBox = new GuiTextField(0, fontRenderer, lx, y + 9, lw, 13);
        playerBox.setText(player);
        y += 26;
        thingBox = new GuiTextField(1, fontRenderer, lx, y + 9, lw, 13);
        thingBox.setText(thing);
        y += 28;
        int i = 0, cw = (lw - 4) / 2;
        for (String name : CHIPS.keySet()) {
            addLeft(new Chip(100 + i, lx + (i % 2) * (cw + 4), y + (i / 2) * 16, cw, 14, name, i));
            i++;
        }
        y += ((CHIPS.size() + 1) / 2) * 16 + 4;
        addLeft(new Flat(2, lx, y, lw, 14, "", 0xFF5B3A8E)); y += 16;
        addLeft(new Flat(3, lx, y, lw, 14, "", 0xFF24408E)); y += 18;
        addLeft(new Flat(4, lx, y, lw, 16, "✦ Search", 0xFF008026));
        addLeft(new Flat(14, lx, y + 20, lw, 16, "✦ Log Galaxy", 0xFF732982));   // the graph ball
        addLeft(new Flat(16, lx, y + 40, lw, 16, "⚙ Settings", 0xFF2A2238));      // every config option (ops)
        leftH = y + 56 - f.cy;
        int bx = listX;
        buttonList.add(new Flat(10, bx, by, 62, 16, "Teleport", 0xFF24408E)); bx += 66;
        buttonList.add(new Flat(11, bx, by, 72, 16, "Roll back…", 0xFFC0392B)); bx += 76;
        buttonList.add(new Flat(12, bx, by, 60, 16, "Restore…", 0xFFD68910)); bx += 64;
        buttonList.add(new Flat(15, bx, by, 40, 16, "☑ All", PrideFrame.BUTTON));
        buttonList.add(new Flat(13, listX + listW - 56, by, 56, 16, "More ▶", 0xFF5B3A8E));
        layoutLeft();
        refresh();
        if (rows.isEmpty() && !waiting) search(0);
    }

    private void addLeft(GuiButton b) { buttonList.add(b); left.add(b); leftY.add(b.y); }

    /** place the filter column at its scroll; anything not fully inside the panel is hidden (and so not clickable) */
    private void layoutLeft() {
        int top = f.cy, bot = f.cy + f.ch;
        leftScroll = Math.max(0, Math.min(leftScroll, leftH - f.ch));
        for (int i = 0; i < left.size(); i++) {
            GuiButton b = left.get(i);
            b.y = leftY.get(i) - leftScroll;
            b.visible = b.y >= top && b.y + b.height <= bot;
        }
        playerBox.y = top + 9 - leftScroll;
        playerBox.setVisible(playerBox.y >= top && playerBox.y + 13 <= bot);
        thingBox.y = top + 35 - leftScroll;
        thingBox.setVisible(thingBox.y >= top && thingBox.y + 13 <= bot);
    }

    private void refresh() {
        buttonList.get(CHIPS.size()).displayString = "Time: " + TIMES[time];
        buttonList.get(CHIPS.size() + 1).displayString = area.isEmpty() ? "Radius: " + RADII[radius] : "Area: from the map ✕";
    }

    /** turn the filters into Prism words and ask the server */
    private void search(int p) {
        player = playerBox.getText().trim();
        thing = thingBox.getText().trim();
        StringBuilder w = new StringBuilder();
        if (!player.isEmpty()) w.append("p:").append(player.split("\\s+")[0]).append(' ');
        if (!thing.isEmpty()) w.append("b:").append(thing.split("\\s+")[0]).append(' ').append("i:").append(thing.split("\\s+")[0]).append(' ');
        if (time > 0) w.append("t:").append(TIMES[time]).append(' ');
        if (!area.isEmpty()) w.append(area).append(' ');                  // the land map's selection
        else if (radius < 3) w.append("r:").append(RADII[radius]).append(' ');
        else if (player.isEmpty() && time == 0) w.append("t:4w "); // "everywhere, all time" = the last 4 weeks, keeps it quick
        boolean all = true;
        List<String> acts = new ArrayList<>();
        int i = 0;
        for (String[] a : CHIPS.values()) { if (on[i++]) acts.addAll(Arrays.asList(a)); else all = false; }
        if (!all && !acts.isEmpty()) w.append("a:").append(String.join(",", acts));
        page = p;
        waiting = true;
        if (p == 0) { selected = -1; scroll = 0; }
        lastWords = w.toString().trim();
        if (p == 0) picked.clear();
        Net.CH.sendToServer(new Net.Query(w.toString().trim(), p));
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        if (b instanceof Chip) { on[((Chip) b).index] = !on[((Chip) b).index]; return; }
        switch (b.id) {
            case 2: time = (time + 1) % TIMES.length; refresh(); break;
            case 3: if (!area.isEmpty()) area = ""; else radius = (radius + 1) % RADII.length; refresh(); break;
            case 4: search(0); break;
            case 13: if (more) search(page + 1); break;
            case 16: mc.displayGuiScreen(new com.dogpound.prideprism.cfgbridge.GuiConfigPanel(this, "PridePrism settings")); break;
            case 14: mc.displayGuiScreen(new GuiPrismGraph()); break;
            case 10:
                if (selected >= 0 && selected < rows.size()) Net.CH.sendToServer(new Net.RowAction(rows.get(selected).id, 0));
                mc.displayGuiScreen(null);
                break;
            case 11: case 12: {
                // ticked rows; else the clicked row; else EVERYTHING the search matches
                java.util.Set<Long> ids = new java.util.LinkedHashSet<>(picked);
                if (ids.isEmpty() && selected >= 0 && selected < rows.size()) ids.add(rows.get(selected).id);
                long[] arr = new long[ids.size()];
                int k = 0;
                for (long id : ids) arr[k++] = id;
                mc.displayGuiScreen(new GuiRollback(this, arr, lastWords, b.id == 11));
                break;
            }
            case 15:
                if (picked.size() >= rows.size()) picked.clear(); else for (Action a : rows) picked.add(a.id);
                break;
            default: break;
        }
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (key == Keyboard.KEY_RETURN) { search(0); return; }
        if (playerBox.textboxKeyTyped(c, key) || thingBox.textboxKeyTyped(c, key)) return;
        super.keyTyped(c, key);
    }

    @Override
    protected void mouseClicked(int x, int y, int button) throws IOException {
        super.mouseClicked(x, y, button);
        if (playerBox.getVisible()) playerBox.mouseClicked(x, y, button); else playerBox.setFocused(false);
        if (thingBox.getVisible()) thingBox.mouseClicked(x, y, button); else thingBox.setFocused(false);
        if (x >= listX && x < listX + listW && y >= listY && y < listY + listH) {
            int i = scroll + (y - listY) / ROW;
            if (i < rows.size()) {
                boolean box = x >= listX + listW - 14;
                if (isShiftKeyDown() && anchor >= 0) {                     // shift: tick the whole range
                    for (int k = Math.min(anchor, i); k <= Math.max(anchor, i) && k < rows.size(); k++) picked.add(rows.get(k).id);
                } else if (box || isCtrlKeyDown()) {                        // the box or ctrl: tick / untick one
                    long id = rows.get(i).id;
                    if (!picked.remove(id)) picked.add(id);
                    anchor = i;
                } else anchor = i;
                selected = i;
            }
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d == 0) return;
        int mx = Mouse.getEventX() * width / mc.displayWidth;
        if (leftH > f.ch && mx < listX - 2) { leftScroll += d > 0 ? -16 : 16; layoutLeft(); }   // over the filters (only when they don't fit)
        else scroll =Math.max(0, Math.min(Math.max(0, rows.size() - listH / ROW), scroll + (d > 0 ? -3 : 3)));
    }

    @Override public void updateScreen() { playerBox.updateCursorCounter(); thingBox.updateCursorCounter(); }
    @Override public void onGuiClosed() { Keyboard.enableRepeatEvents(false); player = playerBox.getText(); thing = thingBox.getText(); }
    @Override public boolean doesGuiPauseGame() { return false; }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        String st = waiting ? "§esearching…" : "§7" + rows.size() + (more ? "+" : "") + " actions" + (picked.isEmpty() ? "" : " §d" + picked.size() + " ticked") + " §8| " + status;
        f.draw(this, "PridePrism", fontRenderer.trimStringToWidth(st, f.w - 40 - fontRenderer.getStringWidth("§l✦ PridePrism")));

        // the filter column (clipped + its own scroll bar when the window is too short for it)
        PrideFrame.clip(lx - 1, f.cy, lw + 2, f.ch);
        drawString(fontRenderer, "§7Player", lx, f.cy - leftScroll, 0xFFFFFF);
        drawString(fontRenderer, "§7Block or item", lx, f.cy + 26 - leftScroll, 0xFFFFFF);
        playerBox.drawTextBox();
        thingBox.drawTextBox();
        for (GuiButton b : left) b.drawButton(mc, mx, my, pt);
        PrideFrame.unclip();
        PrideFrame.scrollbar(lx + lw + 3, f.cy, f.ch, leftScroll, f.ch, leftH);
        for (GuiButton b : buttonList) if (!left.contains(b)) b.drawButton(mc, mx, my, pt);

        // the timeline
        PrideFrame.card(listX, listY, listW, listH, PrideFrame.BLUE);
        int visible = listH / ROW;
        PrideFrame.scrollbar(listX + listW + 3, listY, listH, scroll, visible, rows.size());
        PrideFrame.clip(listX, listY, listW, listH);
        for (int i = 0; i < visible && scroll + i < rows.size(); i++) {
            int idx = scroll + i;
            Action a = rows.get(idx);
            int ry = listY + i * ROW;
            boolean hover = mx >= listX && mx < listX + listW && my >= ry && my < ry + ROW;
            if (idx == selected) drawRect(listX, ry, listX + listW, ry + ROW, 0x5060408E);
            else if (hover) drawRect(listX, ry, listX + listW, ry + ROW, 0x25FFFFFF);
            drawRect(listX, ry, listX + 2, ry + ROW, color(a.action));
            icon(stackOf(a), listX + 4, ry + 1);
            String what = shortName(a);
            String line = "§8" + Inspect.ago(a.time) + " §f" + a.who + " " + tint(a.action) + a.action + " §7" + what;
            if (a.rolledBack) line += " §8(undone)";
            drawString(fontRenderer, fontRenderer.trimStringToWidth(line, listW - 40), listX + 23, ry + 5, 0xFFFFFF);
            boolean tick = picked.contains(a.id);
            drawRect(listX + listW - 12, ry + 4, listX + listW - 3, ry + 13, tick ? 0xFFF5A9B8 : 0x60FFFFFF);
            if (tick) drawString(fontRenderer, "✔", listX + listW - 11, ry + 5, 0xFF1C1530);
        }
        PrideFrame.unclip();
        if (rows.isEmpty()) drawCenteredString(fontRenderer, waiting ? "Looking…" : "Nothing logged here yet", listX + listW / 2, listY + listH / 2 - 4, 0x999999);

        // detail card for the picked row
        int dy = listY + listH + 4;
        PrideFrame.card(listX, dy, listW, by - 4 - dy, PrideFrame.PINK);
        if (selected >= 0 && selected < rows.size()) {
            Action a = rows.get(selected);
            ItemStack before = stack(a.blockOld, a.metaOld), after = stack(a.blockNew, a.metaNew);
            icon(before, listX + 4, dy + 3);
            drawString(fontRenderer, "§7→", listX + 23, dy + 7, 0xFFFFFF);
            icon(after.isEmpty() ? stackOf(a) : after, listX + 32, dy + 3);
            String where = a.x + " " + a.y + " " + a.z + (a.dim != 0 ? " (dim " + a.dim + ")" : "");
            drawString(fontRenderer, "§f" + a.who + " " + tint(a.action) + a.action + " §7at " + where, listX + 52, dy + 3, 0xFFFFFF);
            String info = (a.amount > 0 && a.item != null ? a.amount + "x " : "") + (a.extra != null ? a.extra : shortName(a));
            drawString(fontRenderer, fontRenderer.trimStringToWidth("§8" + info, listW - 56), listX + 52, dy + 13, 0xFFFFFF);
        } else {
            drawString(fontRenderer, "§8Click a row to see it here", listX + 6, dy + 8, 0xFFFFFF);
        }
    }

    static void results(Net.Results r) {
        waiting = false;
        status = r.status;
        more = r.more;
        if (r.page == 0) rows = new ArrayList<>(r.rows);
        else rows.addAll(r.rows);
    }

    private static ItemStack stackOf(Action a) {
        if (a.item != null) { ItemStack s = itemStack(a.item, a.itemMeta); if (!s.isEmpty()) return s; }
        ItemStack s = stack(a.action.equals("place") ? a.blockNew : a.blockOld, a.action.equals("place") ? a.metaNew : a.metaOld);
        return s.isEmpty() ? stack(a.blockNew, a.metaNew) : s;
    }

    @SuppressWarnings("deprecation")
    private static ItemStack stack(String blockId, int meta) {
        if (blockId == null) return ItemStack.EMPTY;
        Block b = Block.getBlockFromName(blockId);
        if (b == null) return itemStack(blockId, meta);
        Item it = Item.getItemFromBlock(b);
        if (it == null || it == net.minecraft.init.Items.AIR) return itemStack(blockId, meta); // doors, crops: their item has the same id
        return new ItemStack(it, 1, b.damageDropped(b.getStateFromMeta(meta)));
    }

    private static ItemStack itemStack(String id, int meta) {
        Item it = Item.REGISTRY.getObject(new ResourceLocation(id));
        return it == null ? ItemStack.EMPTY : new ItemStack(it, 1, meta);
    }

    private void icon(ItemStack s, int x, int y) {
        if (s.isEmpty()) return;
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableDepth();
        try { itemRender.renderItemAndEffectIntoGUI(s, x, y); } catch (Throwable ignored) { } // a broken mod model won't take the screen down
        GlStateManager.disableDepth();
        RenderHelper.disableStandardItemLighting();
    }

    private static String shortName(Action a) {
        String id = a.item != null ? a.item : "place".equals(a.action) ? a.blockNew : a.blockOld;
        if (id == null) return a.extra == null ? "" : a.extra;
        ItemStack s = stackOf(a);
        return s.isEmpty() ? id.replace("minecraft:", "") : s.getDisplayName();
    }

    private static int color(String action) {
        switch (action) {
            case "break": case "explode": case "item-take": case "death": return 0xFFE40303;
            case "place": case "item-put": case "craft": return 0xFF008026;
            case "door": case "trapdoor": case "gate": case "button": case "lever": case "use": return 0xFFFFED00;
            case "money": case "web-order": case "web-reward": return 0xFFFF8C00;
            case "chat": case "command": return 0xFF24408E;
            default: return 0xFF732982;
        }
    }

    private static String tint(String action) {
        int c = color(action);
        return c == 0xFFE40303 ? "§c" : c == 0xFF008026 ? "§a" : c == 0xFFFFED00 ? "§e" : c == 0xFFFF8C00 ? "§6" : c == 0xFF24408E ? "§9" : "§d";
    }

    /** flat coloured button that lights up under the mouse */
    static class Flat extends GuiButton {
        final int color;
        Flat(int id, int x, int y, int w, int h, String s, int color) { super(id, x, y, w, h, s); this.color = color; }

        @Override
        public void drawButton(Minecraft mc, int mx, int my, float pt) {
            if (!visible) return;
            hovered = PrideFrame.button(x, y, width, height, displayString, enabled ? color : PrideFrame.BUTTON, mx, my);
        }
    }

    /** an on/off filter chip, coloured like the actions it shows */
    static class Chip extends Flat {
        final int index;
        Chip(int id, int x, int y, int w, int h, String s, int index) { super(id, x, y, w, h, s, 0); this.index = index; }

        @Override
        public void drawButton(Minecraft mc, int mx, int my, float pt) {
            if (!visible) return;
            hovered = mx >= x && my >= y && mx < x + width && my < y + height;
            int c = color(CHIPS.values().toArray(new String[0][])[index][0]);
            PrideFrame.tile(x, y, width, height, on[index] ? c : 0xFF444444, hovered, false);
            if (on[index]) drawRect(x, y + 2, x + width, y + height, (c & 0x00FFFFFF) | 0x60000000);
            drawCenteredString(mc.fontRenderer, (on[index] ? "" : "§8") + displayString, x + width / 2, y + (height - 8) / 2, 0xFFFFFF);
        }
    }
}
