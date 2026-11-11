package dev.logos.dimension.client.music;

import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.mixin.SoundEngineAccess;
import dev.logos.dimension.mixin.SoundManagerAccess;
import dev.logos.dimension.music.InstrumentKind;
import dev.logos.dimension.music.SampleMap;
import java.io.Reader;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * 各乐器的采样表（{@link SampleMap}），随资源包重新加载。采样文件不写进 sounds.json：
 * 按表里的文件名直接播放（{@link VoiceBank}），第一次用到时才解码，所以不会在启动时把几百个采样全读进内存。
 * 有循环点的文件登记在这里，声音引擎读出它的缓冲时设上循环点（见 SoundBufferLoopMixin）。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class SampleMaps {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<InstrumentKind, SampleMap> MAPS = new EnumMap<>(InstrumentKind.class);
    /** 声音文件的完整资源名（logos_dimension:sounds/….ogg）→ [循环起点, 循环终点)。 */
    private static final Map<Identifier, int[]> LOOPS = new ConcurrentHashMap<>();
    /** 已经预读过的（乐器, 力度层的上限；-1 表示整件）。 */
    private static final java.util.Set<String> WARM = new java.util.HashSet<>();

    private SampleMaps() {}

    @SubscribeEvent
    static void reload(AddClientReloadListenersEvent event) {
        event.addListener(LogosDimension.id("instrument_samples"), (ResourceManagerReloadListener) SampleMaps::load);
    }

    static void load(ResourceManager resources) {
        MAPS.clear();
        LOOPS.clear();
        WARM.clear();
        for (InstrumentKind kind : InstrumentKind.values()) {
            if (kind.synthesized()) continue;
            Identifier id = LogosDimension.id("instruments/" + kind.id() + ".json");
            var resource = resources.getResource(id);
            if (resource.isEmpty()) {
                LOGGER.warn("No sample map for instrument {} ({})", kind.id(), id);
                continue;
            }
            try (Reader reader = resource.get().openAsReader()) {
                SampleMap map = SampleMap.parse(JsonParser.parseReader(reader).getAsJsonObject());
                MAPS.put(kind, map);
                for (SampleMap.Zone z : map.zones()) {
                    if (z.loops()) LOOPS.put(path(z.file()), new int[]{z.loopStart(), z.loopEnd()});
                }
            } catch (Exception e) {
                LOGGER.error("Could not read sample map {}", id, e);
            }
        }
    }

    static @Nullable SampleMap get(InstrumentKind kind) {
        return MAPS.get(kind);
    }

    /** 声音实例用的名字（声音引擎去读 sounds/&lt;名字&gt;.ogg）。 */
    static Identifier sound(String file) {
        return LogosDimension.id(file);
    }

    private static Identifier path(String file) {
        return LogosDimension.id("sounds/" + file + ".ogg");
    }

    /** 这个声音文件的循环点（没有就是 null）。声音引擎读缓冲时问。 */
    public static int @Nullable [] loop(Identifier location) {
        return LOOPS.get(location);
    }

    /**
     * 先把一件乐器的采样解码进声音引擎的缓存，免得第一次弹某个音时慢半拍。
     * velocity 为 -1 时整件乐器都读（手持乐器，采样少）；否则只读这个力度所在的那一层（钢琴一层 30 个）。
     */
    static void prewarm(InstrumentKind kind, int velocity) {
        SampleMap map = MAPS.get(kind);
        if (map == null) return;
        int layer = -1;
        if (velocity >= 0) {
            SampleMap.Zone z = map.pick(60, velocity);
            layer = z == null ? -1 : z.vhi();
        }
        if (!WARM.add(kind.id() + "/" + layer)) return;
        var engine = ((SoundManagerAccess) Minecraft.getInstance().getSoundManager()).logos_dimension$engine();
        var buffers = ((SoundEngineAccess) engine).logos_dimension$buffers();
        for (SampleMap.Zone z : map.zones()) {
            if (velocity >= 0 && (velocity < z.vlo() || velocity > z.vhi())) continue;
            buffers.getCompleteBuffer(path(z.file()));
            if (z.releaseSound() != null) buffers.getCompleteBuffer(path(z.releaseSound()));
        }
    }
}
