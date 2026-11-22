package dev.logos.dimension.client;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.elven.blight.CorruptedElfQueen;
import dev.logos.dimension.elven.blight.HeartPurification;
import dev.logos.dimension.elven.blight.QueenBattleRules;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;

/** 只按女王 UUID 替换她自己的血条（名字、血量、阶段），不写别的状态。 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class QueenBossHud {
    private static final Identifier FRAME = LogosDimension.id("textures/gui/queen_boss_frame.png"), FILL = LogosDimension.id("textures/gui/queen_boss_fill.png");
    private static final Map<UUID, Echo> ECHOES = new HashMap<>();
    private static Object level;
    private static long frames;
    private static final class Echo { int phase; float health; double time; }
    private QueenBossHud() {}
    public static long frames() { return frames; }

    @SubscribeEvent static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post e) {
        if (level != Minecraft.getInstance().level) { level = null; ECHOES.clear(); }
    }
    @SubscribeEvent static void draw(CustomizeGuiOverlayEvent.BossEventProgress event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (level != mc.level) { level = mc.level; ECHOES.clear(); }
        CorruptedElfQueen queen = null;
        for (var e : mc.level.entitiesForRendering()) if (e instanceof CorruptedElfQueen q && q.getUUID().equals(event.getBossEvent().getId())) { queen = q; break; }
        // 缺少对应实体或资源时仍由原版画条，不吞掉其他 Boss 的界面。
        if (queen == null || queen.phase() > 2 || !mc.getResourceManager().getResource(FRAME).isPresent()) return;
        event.setCanceled(true); frames++;
        var g = event.getGuiGraphics();
        int w = Math.min(320, g.guiWidth() - 24), h = Math.round(w * 60f / 352), x = (g.guiWidth() - w) / 2, y = event.getY() - 6;
        float scale = w / 352f, health = event.getBossEvent().getProgress();
        double now = mc.level.getGameTime() + (double) mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        var echo = ECHOES.computeIfAbsent(queen.getUUID(), ignored -> new Echo());
        if (echo.phase != queen.phase()) { echo.phase = queen.phase(); echo.health = health; }
        float dt = (float) Math.clamp(now - echo.time, 0, 2); echo.time = now;
        echo.health = Math.max(health, echo.health + (health - echo.health) * (1 - (float) Math.exp(-dt * .12)));
        g.blit(FRAME, x, y, x + w, y + h, 0, 1, 0, 1);
        int left = x + Math.round(33 * scale), top = y + Math.round(25 * scale), right = x + Math.round(320 * scale), bottom = y + Math.round(32 * scale);
        // 暗金滞后层让一次斩断造成的大段失血有短暂余量，阶段换血时立即重置。
        g.fill(left, top, left + Math.round((right - left) * echo.health), bottom, 0xFFAD7C62);
        if (health > 0) {
            g.enableScissor(left, top, left + Math.round((right - left) * health), bottom);
            g.blit(FILL, x, y, x + w, y + h, 0, 1, 0, 1); g.disableScissor();
        }
        var font = mc.font;
        var title = event.getBossEvent().getName();
        text(g, title, g.guiWidth() / 2f, y + 8 * scale, Math.min(.88f, (w - 56f) / Math.max(1, font.width(title))), 0xFFF5DCCB);
        text(g, Component.translatable("gui.logos_dimension.queen.title_en"), g.guiWidth() / 2f, y + 18 * scale, .55f, 0xFFBA9691);
        // 阶段一是女王的生命；阶段二是腐败之心的五段生命（每段 200，多人按人数加），一刀斩掉一段。
        float max = queen.phase() == 1 ? queen.getMaxHealth() : HeartPurification.SEGMENT * HeartPurification.SEGMENTS * Math.max(1, queen.getMaxHealth() / QueenBattleRules.HEALTH);
        text(g, Component.literal(Math.round(health * max) + " / " + Math.round(max)), g.guiWidth() / 2f, y + 25 * scale, .52f, 0xFFFFE6DC);
        for (int i = 0; i < 2; i++) {
            int cx = x + Math.round((142 + i * 20) * scale), cy = y + Math.round(43 * scale);
            int color = i + 1 < queen.phase() ? 0xFF665245 : i + 1 == queen.phase() ? 0xFFFFC394 : 0xFF4E3740;
            g.fill(cx - 2, cy - 1, cx + 3, cy + 2, color); g.fill(cx - 1, cy - 2, cx + 2, cy + 3, color);
        }
        // 只画血条：净化到哪儿、血管和核心还剩几个，玩家看画面或听公主说，不写在这里。
        event.setIncrement(Math.max(event.getIncrement(), h + 9));
    }
    private static void text(GuiGraphicsExtractor g, Component text, float center, float y, float scale, int color) {
        var font = Minecraft.getInstance().font; var pose = g.pose(); pose.pushMatrix();
        pose.translate(center - font.width(text) * scale / 2, y); pose.scale(scale, scale);
        g.text(font, text, 0, 0, color, false); pose.popMatrix();
    }
}
