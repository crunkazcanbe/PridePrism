package com.dogpound.prideprism;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** see-through boxes where a planned rollback will change things: green comes back, red goes away, yellow = changed since */
public final class Preview {
    private static int[][] spots = new int[0][];
    private static long until;

    static void show(int[][] s, int seconds) { spots = s; until = System.currentTimeMillis() + seconds * 1000L; }

    @SubscribeEvent
    public void render(RenderWorldLastEvent e) {
        if (spots.length == 0 || System.currentTimeMillis() > until) return;
        Minecraft mc = Minecraft.getMinecraft();
        Entity v = mc.getRenderViewEntity();
        if (v == null) return;
        double cx = v.lastTickPosX + (v.posX - v.lastTickPosX) * e.getPartialTicks();
        double cy = v.lastTickPosY + (v.posY - v.lastTickPosY) * e.getPartialTicks();
        double cz = v.lastTickPosZ + (v.posZ - v.lastTickPosZ) * e.getPartialTicks();
        int dim = mc.world.provider.getDimension();
        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.disableDepth();                       // see them through walls
        GlStateManager.enableBlend();
        GlStateManager.glLineWidth(2f);
        float pulse = 0.55f + 0.35f * (float) Math.sin(System.currentTimeMillis() / 250.0);
        for (int[] s : spots) {
            if (s[3] != dim) continue;
            if ((s[0] - cx) * (s[0] - cx) + (s[2] - cz) * (s[2] - cz) > 160 * 160) continue;
            float r = s[4] == 1 ? 1f : s[4] == 2 ? 1f : 0.2f, g = s[4] == 0 ? 1f : s[4] == 2 ? 0.85f : 0.25f, b = 0.3f;
            AxisAlignedBB box = new AxisAlignedBB(s[0], s[1], s[2], s[0] + 1, s[1] + 1, s[2] + 1).grow(0.004).offset(-cx, -cy, -cz);
            RenderGlobal.drawSelectionBoundingBox(box, r, g, b, pulse);
            RenderGlobal.renderFilledBox(box, r, g, b, 0.12f);
        }
        GlStateManager.enableDepth();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }
}
