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
import net.neoforged.neoforge.client.event.sound.SoundEngineLoadEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * 各乐器的采样表（{@link SampleMap}），随资源包重新加载。模组本体带合成音源；装了「音乐厅音源」资源包时，
 * 包里同名的表在上层，换成录音。表的格式比这个版本新（资源包比模组新）时跳过它，用下一层。采样文件不写进 sounds.json：
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

    /**
     * 声音引擎重新启动（换资源包、F3+T、换了音频设备都会）：OpenAL 的上下文是新的，旧的缓冲全都作废。
     * 管风琴自己留着的缓冲丢掉重做（不然按过的键交给引擎的是旧上下文的缓冲号：不出声，甚至响成别的声音）；
     * 预读、纸带的采样重新解码；声音的记录清掉。
     */
    @SubscribeEvent
    static void engineLoaded(SoundEngineLoadEvent event) {
        WARM.clear();
        OrganMixer.engineReloaded();
        VoiceBank.engineReloaded();
        MusicClient.engineReloaded();
    }

    static void load(ResourceManager resources) {
        MAPS.clear();
        LOOPS.clear();
        WARM.clear();
        for (InstrumentKind kind : InstrumentKind.values()) {
            if (kind.synthesized()) continue;
            Identifier id = LogosDimension.id("instruments/" + kind.id() + ".json");
            SampleMap map = topReadable(resources, id);
            if (map == null) {
                LOGGER.warn("No sample map for instrument {} ({})", kind.id(), id);
                continue;
            }
            MAPS.put(kind, map);
            for (SampleMap.Zone z : map.zones()) {
                if (z.loops()) LOOPS.put(path(z.file()), new int[]{z.loopStart(), z.loopEnd()});
            }
        }
    }

    /** 从最上层的资源包往下找第一张读得懂的表（getResourceStack 从低到高排）。 */
    private static @Nullable SampleMap topReadable(ResourceManager resources, Identifier id) {
        var stack = resources.getResourceStack(id);
        for (int i = stack.size() - 1; i >= 0; i--) {
            var resource = stack.get(i);
            try (Reader reader = resource.openAsReader()) {
                SampleMap map = SampleMap.parse(JsonParser.parseReader(reader).getAsJsonObject());
                if (map.format() > SampleMap.FORMAT) {
                    LOGGER.warn("Sample map {} in {} has format {}, newer than this mod reads ({}); using the layer below",
                        id, resource.sourcePackId(), map.format(), SampleMap.FORMAT);
                    continue;
                }
                return map;
            } catch (Exception e) {
                LOGGER.error("Could not read sample map {} in {}", id, resource.sourcePackId(), e);
            }
        }
        return null;
    }

    /** 界面上显示的音源：录音（资源包）或合成（本体）；管风琴没有采样表，返回 null。 */
    static net.minecraft.network.chat.@Nullable Component sourceLabel(InstrumentKind kind) {
        SampleMap map = MAPS.get(kind);
        if (map == null) return null;
        return net.minecraft.network.chat.Component.translatable(
            "screen.logos_dimension.instrument." + (map.recorded() ? "source_recorded" : "source_synth"));
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
     * 放纸带前先把这卷纸带要用到的采样解码好（按各声部的移调、力度挑区）：合奏一开始十几件乐器同时要读采样，
     * 等到弹的那一刻才解码，短音会晚到、甚至松开时还没响。
     */
    static void prewarm(InstrumentKind kind, dev.logos.dimension.music.MusicRoll roll, int[] shifts) {
        SampleMap map = MAPS.get(kind);
        if (map == null) return;
        var engine = ((SoundManagerAccess) Minecraft.getInstance().getSoundManager()).logos_dimension$engine();
        var buffers = ((SoundEngineAccess) engine).logos_dimension$buffers();
        java.util.Set<String> files = new java.util.HashSet<>();
        for (var e : roll.events()) {
            if (e.type() != dev.logos.dimension.music.MusicRoll.NOTE) continue;
            int pitch = kind.fold(e.pitch() + shifts[e.part()]);
            SampleMap.Zone z = map.pick(pitch, kind.velocitySensitive() ? e.velocity() : 100);
            if (z == null || !files.add(z.file())) continue;
            buffers.getCompleteBuffer(path(z.file()));
            if (z.releaseSound() != null) buffers.getCompleteBuffer(path(z.releaseSound()));
        }
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
