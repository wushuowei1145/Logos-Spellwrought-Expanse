package dev.logos.dimension.client.shiraume;

import dev.logos.dimension.LogosDimension;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 开场标题（docs/SHIRAUME_BOSS.md 第 2 节）：镜头拉到玩家肩后时，画面正中出现「九尾妖狐」与大字「白梅」，
 * 中间一道细细的朱线，下面一行小字しらうめ。大字从疏到密地收拢、淡入，停两秒多，淡出。
 * 画在运镜的遮幅之上，运镜隐藏界面时照样画；标题出现时视角收窄一点，远处的她在画面里大一些。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class ShiraumeTitle {
    /** 淡入、开始淡出、完全消失（刻）。 */
    static final float IN = dev.logos.dimension.client.BossTitle.IN,
            OUT_AT = dev.logos.dimension.client.BossTitle.OUT_AT, GONE = dev.logos.dimension.client.BossTitle.GONE;

    /** 客户端场景用：停在第几刻上（小于 0 不停）。 */
    private static float held = -1;

    private ShiraumeTitle() {}

    /** 客户端场景用：让标题停在 {@code age} 刻的样子，传负数放开。 */
    public static void hold(float age) {
        held = age;
    }

    /** 运镜隐藏界面时会取消 {@code RenderGuiEvent.Pre}（之后的界面层都不画），所以收已取消的事件，排在遮幅之后画。 */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    static void hud(RenderGuiEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        float age = held >= 0 ? held : ShiraumeClient.titleAge(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        if (age < 0 || age > GONE) return;
        draw(event.getGuiGraphics(), mc.font, age);
    }

    static void draw(GuiGraphicsExtractor g, Font font, float age) {
        dev.logos.dimension.client.BossTitle.draw(g, font, age,
                Component.translatable("gui.logos_dimension.shiraume.title_name"),
                Component.translatable("gui.logos_dimension.shiraume.title_epithet"),
                Component.translatable("gui.logos_dimension.shiraume.title_kana"));
    }

    /** 标题出现时视角从原来的收到 {@link #TITLE_FOV} 度，淡出时放回去。 */
    static final float TITLE_FOV = 44;

    @SubscribeEvent
    static void fov(net.neoforged.neoforge.client.event.ViewportEvent.ComputeFov event) {
        float age = held >= 0 ? held : ShiraumeClient.titleAge((float) event.getPartialTick());
        if (age < 0 || age > GONE + 10) return;
        float in = Mth.clamp(age / IN, 0, 1), out = 1 - Mth.clamp((age - OUT_AT) / (GONE + 10 - OUT_AT), 0, 1);
        float k = in * in * (3 - 2 * in) * out;
        event.setFOV(Mth.lerp(k, event.getFOV(), Math.min(event.getFOV(), TITLE_FOV)));
    }

}
