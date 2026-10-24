package dev.logos.dimension.client;

import dev.logos.dimension.LogosDimension;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * 少数几个森林群系的雾气。
 *
 * <p>只改「雾」，不动天空：进入 {@link #MISTS} 里列出的群系时，把雾的颜色与远近朝该群系的目标值
 * 平滑收拢，离开后平滑恢复。四个群系的权重与浓度各不相同，其余群系完全没有这个效果，
 * 避免整个远行大陆到处都在变色。
 *
 * <p>只影响客户端画面，不参与服务端判定，也不写任何存档数据。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class ForestMist {
    /** 单个群系的雾：颜色与远近平面。 */
    private record Mist(float red, float green, float blue, float near, float far) {}

    /** 雾的目标值；只列这四个群系，其余群系保持原版雾。 */
    private static final Map<ResourceKey<Biome>, Mist> MISTS = Map.of(
        // 翳蕨密林：偏绿的薄雾，能见度略降。
        biome("cloudsea_fernwood"), new Mist(0.62f, 0.70f, 0.64f, 12f, 88f),
        // 雾苔溪林：最浓的乳白水汽。
        biome("cloudsea_moss_vale"), new Mist(0.78f, 0.82f, 0.79f, 6f, 56f),
        // 杉影深林：冷调的青灰雾。
        biome("cloudsea_fir_heart"), new Mist(0.60f, 0.66f, 0.73f, 14f, 96f),
        // 精灵森林：淡萤绿的光雾。
        biome("elven_forest"), new Mist(0.63f, 0.77f, 0.67f, 10f, 82f));

    /** 当前生效的雾（null 表示不在这些群系里）。 */
    private static Mist target;
    /** 平滑后的强度 0–1。 */
    private static float strength;

    private static ResourceKey<Biome> biome(String path) {
        return ResourceKey.create(Registries.BIOME, LogosDimension.id(path));
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        Mist found = null;
        if (mc.level != null && mc.player != null && mc.level.dimension().equals(LogosDimension.CLOUDSEA)) {
            BlockPos pos = mc.player.blockPosition();
            found = mc.level.getBiome(pos).unwrapKey().map(MISTS::get).orElse(null);
        }
        target = found;
        // 每刻只走一小步：进入/离开群系时雾是渐变的，不会一格一格地跳。
        strength += ((found == null ? 0f : 1f) - strength) * .08f;
        if (strength < .002f) strength = 0f;
    }

    @SubscribeEvent
    static void fogColor(ViewportEvent.ComputeFogColor event) {
        if (target == null || strength <= 0f) return;
        float blend = strength * .55f;
        event.setRed(event.getRed() * (1 - blend) + target.red() * blend);
        event.setGreen(event.getGreen() * (1 - blend) + target.green() * blend);
        event.setBlue(event.getBlue() * (1 - blend) + target.blue() * blend);
    }

    @SubscribeEvent
    static void fogDistance(ViewportEvent.RenderFog event) {
        if (target == null || strength <= 0f) return;
        float near = event.getNearPlaneDistance(), far = event.getFarPlaneDistance();
        // 只收拢，不把雾推远：远处先蒙上，近处仍看得清脚下。
        event.setNearPlaneDistance(near + (Math.min(near, target.near()) - near) * strength);
        event.setFarPlaneDistance(far + (Math.min(far, target.far()) - far) * strength);
    }

    private ForestMist() {}
}
