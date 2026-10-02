package com.dogpound.prideprism.cfgbridge;

import java.util.*;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;
import com.dogpound.prideprism.PrideFrame;

public class GuiConfigPanel extends GuiScreen {
    private final GuiScreen parent;
    private final String title;
    private PrideFrame f;
    private GuiTextField search;
    private String[] editing;
    private boolean onlyChanged;
    private int scroll;
    private final List<Object[]> lines = new ArrayList<>();
    private static final int ROW = 16;
    private String statusMessage;
    private long statusTime;
    private int lastRowsHash = -1;

    public GuiConfigPanel(GuiScreen parent, String title) {
        this.parent = parent;
        this.title = title;
    }

    @Override
    public void initGui() {
        f = PrideFrame.fit(width, height);
        String keep = search == null ? "" : search.getText();
        search = new GuiTextField(9000, fontRenderer, f.cx + 1, f.cy + 1, Math.min(220, f.cw / 3), 12);
        search.setText(keep);
        search.setFocused(true);
        search.setMaxStringLength(60);
        ConfigBridge.ask();
    }

    @Override
    public void updateScreen() {
        if (System.currentTimeMillis() - statusTime > 4000) statusMessage = null;
        if (lastRowsHash != ConfigBridge.rows.hashCode()) {
            lastRowsHash = ConfigBridge.rows.hashCode();
            scroll = 0;
        }
        if (System.currentTimeMillis() % 5000 < 100) ConfigBridge.ask();
    }

    private void build() {
        lines.clear();
        String q = search == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        String lastHead = null;
        for (String[] o : ConfigBridge.rows) {
            if (onlyChanged && o[5].equals(o[6])) continue;
            if (!q.isEmpty() && !(o[3] + " " + o[10] + " " + o[1] + " " + o[2]).toLowerCase(Locale.ROOT).contains(q)) continue;
            String head = o[2];
            if (!head.equals(lastHead)) { lines.add(new Object[]{"group", head}); lastHead = head; }
            lines.add(new Object[]{"opt", o});
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        f.draw(this, title, statusMessage != null ? statusMessage : "");
        if (ConfigBridge.rows.isEmpty()) {
            drawCenteredString(fontRenderer, "§7loading settings…", f.cx + f.cw / 2, f.cy + 40, 0xFFFFFF);
            if (ConfigBridge.lastResult != null && !ConfigBridge.lastResult.isEmpty()) {
                drawCenteredString(fontRenderer, "§c" + ConfigBridge.lastResult, f.cx + f.cw / 2, f.cy + 60, 0xFFFFFF);
            }
            return;
        }
        build();
        search.drawTextBox();
        if (search.getText().isEmpty() && !search.isFocused()) drawString(fontRenderer, "§8🔍 search settings…", f.cx + 5, f.cy + 3, 0xFFFFFF);
        int changed = 0, total = 0;
        for (String[] o : ConfigBridge.rows) { total++; if (!o[5].equals(o[6])) changed++; }
        drawString(fontRenderer, "§7" + total + " settings · §d" + changed + " changed", search.x + search.width + 8, f.cy + 3, 0xFFFFFF);

        int vis = (f.cy + f.ch - (f.cy + 18)) / ROW;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, lines.size() - vis)));
        int y0 = f.cy + 18;
        drawRect(f.cx, y0, f.cx + f.cw, f.cy + f.ch, 0x50000000);
        
        for (int i = 0; i < vis && scroll + i < lines.size(); i++) {
            Object[] l = lines.get(scroll + i);
            int y = y0 + i * ROW;
            if (l[0].equals("group")) {
                drawRect(f.cx, y + ROW - 2, f.cx + f.cw, y + ROW - 1, 0x40F5A9B8);
                drawString(fontRenderer, "§d§l" + l[1], f.cx + 4, y + 5, 0xFFFFFF);
                continue;
            }
            String[] o = (String[]) l[1];
            boolean hover = mouseX >= f.cx && mouseX < f.cx + f.cw && mouseY >= y && mouseY < y + ROW;
            if (hover) drawRect(f.cx, y, f.cx + f.cw, y + ROW, 0x20FFFFFF);
            boolean ch = !o[5].equals(o[6]);
            drawString(fontRenderer, (ch ? "§d● " : "§8○ ") + "§f" + fontRenderer.trimStringToWidth(o[3], f.cw - 250), f.cx + 8, y + 4, 0xFFFFFF);
            drawControl(o, y, mouseX, mouseY);
        }
        
        if (lines.size() > vis) {
            int track = f.cy + f.ch - y0, knob = Math.max(10, track * vis / lines.size());
            int ky = y0 + (track - knob) * scroll / Math.max(1, lines.size() - vis);
            drawRect(f.cx + f.cw - 3, y0, f.cx + f.cw, f.cy + f.ch, 0x30FFFFFF);
            drawRect(f.cx + f.cw - 3, ky, f.cx + f.cw, ky + knob, PrideFrame.PINK);
        }
        
        if (lines.isEmpty()) drawCenteredString(fontRenderer, "§8no setting matches", f.cx + f.cw / 2, y0 + 20, 0xFFFFFF);
        if (editing != null) editor.drawTextBox();
        
        // Back button
        PrideFrame.button(f.cx + f.cw - 60, f.cy + 1, 56, 14, "◀ Back", PrideFrame.BUTTON, mouseX, mouseY);
    }

    private GuiTextField editor;

    private void drawControl(String[] o, int y, int mouseX, int mouseY) {
        int x = f.cx + f.cw - 226;
        int w = 200;
        boolean ch = !o[5].equals(o[6]);
        PrideFrame.button(x + w + 4, y + 2, 14, 12, "↺", ch ? 0xFF8E2424 : 0xFF222222, mouseX, mouseY);
        switch (o[4]) {
            case "BOOL": {
                boolean on = Boolean.parseBoolean(o[5]);
                PrideFrame.button(x + w - 60, y + 2, 60, 12, on ? "ON" : "OFF", on ? 0xFF008026 : 0xFF444444, mouseX, mouseY);
                break;
            }
            case "INT": case "NUM": {
                double v = Double.parseDouble(o[5]), mn = Double.parseDouble(o[7]), mxv = Double.parseDouble(o[8]);
                PrideFrame.button(x, y + 2, 14, 12, "−", 0xFF2A2238, mouseX, mouseY);
                PrideFrame.button(x + w - 14, y + 2, 14, 12, "+", 0xFF2A2238, mouseX, mouseY);
                int bx = x + 18, bw = w - 36;
                drawRect(bx, y + 11, bx + bw, y + 13, 0x40FFFFFF);
                double frac = mxv > mn ? (v - mn) / (mxv - mn) : 0;
                drawRect(bx, y + 11, bx + (int) (bw * Math.max(0, Math.min(1, frac))), y + 13, PrideFrame.PINK);
                drawCenteredString(fontRenderer, "§f" + num(v), bx + bw / 2, y + 2, 0xFFFFFF);
                break;
            }
            case "CHOICE":
                PrideFrame.button(x, y + 2, w, 12, o[5] + "  ▸", 0xFF24408E, mouseX, mouseY);
                break;
            default:
                if (editing != null && editing[1].equals(o[1])) {
                    editor.x = x; editor.y = y + 2; editor.width = w;
                } else PrideFrame.button(x, y + 2, w, 12, fontRenderer.trimStringToWidth(o[5].isEmpty() ? "§8(empty) click to type" : o[5], w - 8), 0xFF2A2238, mouseX, mouseY);
        }
    }

    private String num(double v) {
        String s = String.format(Locale.ROOT, "%.6f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton == 0) {
            search.mouseClicked(mouseX, mouseY, 0);
            if (editing != null) {
                if (mouseX >= editor.x && mouseX < editor.x + editor.width && mouseY >= editor.y && mouseY < editor.y + editor.height) {
                    editor.mouseClicked(mouseX, mouseY, 0);
                    return;
                }
                commit();
            }
            int y0 = f.cy + 18;
            if (mouseY < y0 || mouseY >= f.cy + f.ch) return;
            int i = scroll + (mouseY - y0) / ROW;
            if (i >= lines.size() || !lines.get(i)[0].equals("opt")) return;
            String[] o = (String[]) lines.get(i)[1];
            int y = y0 + (i - scroll) * ROW, x = f.cx + f.cw - 226;
            int w = 200;
            if (mouseY < y + 2 || mouseY >= y + 14) return;
            if (mouseX >= x + w + 4 && mouseX < x + w + 18) {
                ConfigBridge.set(o[1], "*default");
                return;
            }
            boolean shift = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
            switch (o[4]) {
                case "BOOL":
                    if (mouseX >= x + w - 60 && mouseX < x + w) {
                        String newValue = String.valueOf(!Boolean.parseBoolean(o[5]));
                        ConfigBridge.set(o[1], newValue);
                        return;
                    }
                    break;
                case "INT": case "NUM": {
                    double v = Double.parseDouble(o[5]), st = o[9].isEmpty() ? (o[4].equals("INT") ? 1.0 : 0.05) : Double.parseDouble(o[9]);
                    double mn = Double.parseDouble(o[7]), mxv = Double.parseDouble(o[8]);
                    Double nv = null;
                    if (mouseX >= x && mouseX < x + 14) nv = v - st;
                    else if (mouseX >= x + w - 14 && mouseX < x + w) nv = v + st;
                    else if (mouseX >= x + 18 && mouseX < x + w - 18) {
                        double frac = (mouseX - (x + 18)) / (double) (w - 36);
                        nv = mn + Math.round(frac * (mxv - mn) / st) * st;
                    }
                    if (nv != null) {
                        nv = Math.max(mn, Math.min(mxv, nv));
                        String out = o[4].equals("INT") ? String.valueOf(Math.round(nv)) : num(nv);
                        ConfigBridge.set(o[1], out);
                    }
                    break;
                }
                case "CHOICE":
                    if (mouseX >= x && mouseX < x + w) {
                        String[] cs = o[11].split("\\|");
                        int k = Arrays.asList(cs).indexOf(o[5]);
                        String nv = cs[(k + (shift ? cs.length - 1 : 1)) % cs.length];
                        ConfigBridge.set(o[1], nv);
                    }
                    break;
                default:
                    if (mouseX >= x && mouseX < x + w) {
                        editing = o;
                        editor = new GuiTextField(9003, fontRenderer, x, y + 2, w, 12);
                        editor.setMaxStringLength(200);
                        editor.setText(o[5]);
                        editor.setFocused(true);
                        search.setFocused(false);
                    }
            }
            if (mouseX >= f.cx + f.cw - 60 && mouseX < f.cx + f.cw - 4 && mouseY >= f.cy + 1 && mouseY < f.cy + 15) {
                mc.displayGuiScreen(parent);
            }
        }
    }

    private void commit() {
        if (editing == null) return;
        if (!editor.getText().equals(editing[5])) {
            ConfigBridge.set(editing[1], editor.getText());
        }
        editing = null;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (editing != null) {
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                commit();
                return;
            }
            if (keyCode == Keyboard.KEY_ESCAPE) {
                editing = null;
                return;
            }
            editor.textboxKeyTyped(typedChar, keyCode);
            return;
        }
        if (search.isFocused()) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                search.setFocused(false);
                return;
            }
            search.textboxKeyTyped(typedChar, keyCode);
            scroll = 0;
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    public void onGuiClosed() {
        if (editing != null) {
            commit();
        }
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        int d = org.lwjgl.input.Mouse.getEventDWheel();
        if (d != 0 && f != null) {
            int vis = (f.cy + f.ch - (f.cy + 18)) / ROW;
            scroll = Math.max(0, Math.min(scroll + (d > 0 ? -3 : 3), Math.max(0, lines.size() - vis)));
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
