package dev.logos.dimension.client;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.taixuan.TaixuanBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * 太玄深渊里的雾：镜头在渊雾里时只看得见几格；在雾层下面（深渊底的场地）时雾也很浓，看不远。
 *
 * <p>判断只看镜头那一格和正上方 {@value #CEILING_SCAN} 格里有没有渊雾，每刻一次；进出时平滑过渡。
 * 只改原版雾的远近和颜色，开光影时光影包可能用自己的雾。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class TaixuanMistFog {
    /** 往上找雾层顶多远：深渊底到雾层底约三十格。 */
    private static final int CEILING_SCAN = 48;
    /** 雾里、雾下面的远雾平面。 */
    private static final float INSIDE_FAR = 7f, UNDER_FAR = 24f, UNDER_NEAR = 2f;
    /** 雾色：偏冷的灰白，明暗跟着原版雾色走。 */
    private static final float RED = .82f, GREEN = .85f, BLUE = .88f;
    private static float amount, far = UNDER_FAR, near = UNDER_NEAR;

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        float target = 0, targetFar = UNDER_FAR, targetNear = UNDER_NEAR;
        if (mc.level != null && mc.player != null) {
            BlockPos eye = BlockPos.containing(mc.gameRenderer.getMainCamera().position());
            var mist = TaixuanBlocks.ABYSS_MIST.get();
            if (mc.level.getBlockState(eye).is(mist)) {
                target = 1;
                targetFar = INSIDE_FAR;
                targetNear = 0;
            } else {
                for (int dy = 1; dy <= CEILING_SCAN; dy++) {
                    if (mc.level.getBlockState(eye.above(dy)).is(mist)) {
                        target = 1;
                        break;
                    }
                }
            }
        }
        amount += (target - amount) * .12f;
        far += (targetFar - far) * .2f;
        near += (targetNear - near) * .2f;
        if (amount < .002f) amount = 0;
    }

    @SubscribeEvent
    static void fogColor(ViewportEvent.ComputeFogColor event) {
        if (amount <= 0) return;
        float lum = .2126f * event.getRed() + .7152f * event.getGreen() + .0722f * event.getBlue();
        float k = Mth.clamp(lum / .75f, .08f, 1.1f);
        event.setRed(Mth.lerp(amount, event.getRed(), RED * k));
        event.setGreen(Mth.lerp(amount, event.getGreen(), GREEN * k));
        event.setBlue(Mth.lerp(amount, event.getBlue(), BLUE * k));
    }

    @SubscribeEvent
    static void fogDistance(ViewportEvent.RenderFog event) {
        if (amount <= 0) return;
        event.setNearPlaneDistance(Mth.lerp(amount, event.getNearPlaneDistance(), Math.min(event.getNearPlaneDistance(), near)));
        event.setFarPlaneDistance(Mth.lerp(amount, event.getFarPlaneDistance(), Math.min(event.getFarPlaneDistance(), far)));
    }

    private TaixuanMistFog() {}
}
