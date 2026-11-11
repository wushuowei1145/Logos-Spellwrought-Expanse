package dev.logos.dimension.music;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 两层音源的采样表：模组本体的合成音源（每件采样乐器都有，tools/instrument_samples.py）与「音乐厅音源」资源包的录音
 * （resourcepacks/concert_hall，tools/instrument_sources.py）。两层都要覆盖每件乐器音域内每个音、每个力度，文件、循环点都在。
 */
class SampleMapTest {
    /** 仓库里的资源包目录（build.gradle 的 test 任务传进来；测试 JVM 的工作目录不是项目根目录）。 */
    private static final Path PACK = Path.of(System.getProperty("logos_dimension.concert_hall_pack", "resourcepacks/concert_hall"));

    /** 一层音源：表从哪读、声音文件在不在。 */
    private interface Layer {
        InputStream open(String path) throws Exception;

        boolean has(String path);
    }

    private static final Layer BASE = new Layer() {
        @Override
        public InputStream open(String path) {
            return SampleMapTest.class.getResourceAsStream("/" + path);
        }

        @Override
        public boolean has(String path) {
            return SampleMapTest.class.getResource("/" + path) != null;
        }
    };

    private static final Layer RECORDED = new Layer() {
        @Override
        public InputStream open(String path) throws Exception {
            Path p = PACK.resolve(path);
            return Files.exists(p) ? Files.newInputStream(p) : null;
        }

        @Override
        public boolean has(String path) {
            return Files.exists(PACK.resolve(path));
        }
    };

    private static SampleMap load(Layer layer, InstrumentKind kind) throws Exception {
        try (InputStream in = layer.open("assets/logos_dimension/instruments/" + kind.id() + ".json")) {
            if (in == null) return null;
            return SampleMap.parse(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
        }
    }

    private static List<InstrumentKind> sampled() {
        List<InstrumentKind> out = new ArrayList<>();
        for (InstrumentKind kind : InstrumentKind.values()) if (!kind.synthesized()) out.add(kind);
        return out;
    }

    private static void coversEverything(InstrumentKind kind, SampleMap map, String layer) {
        assertFalse(map.zones().isEmpty(), layer + " " + kind + " has zones");
        assertEquals(SampleMap.FORMAT, map.format(), layer + " " + kind + " map format");
        for (int p = kind.low(); p <= kind.high(); p++) {
            for (int v = 1; v <= 127; v++) {
                SampleMap.Zone z = map.pick(p, v);
                assertNotNull(z, layer + " " + kind + " " + p + " v" + v);
                assertTrue(z.lo() <= p && p <= z.hi() && z.vlo() <= v && v <= z.vhi(), layer + " " + kind + " " + p + " v" + v + " not covered exactly");
                // 变调不超过 4 个半音
                assertTrue(Math.abs(p - z.key()) <= 4.01, layer + " " + kind + " " + p + " shifted from " + z.key());
                // 各采样存的电平不同（各自放大到峰值 -1 dBFS），换层时响度是否接得上由生成脚本按响度核对；
                // 这里只核对音量在 (0, 1] 内、同一区里力度越大越响
                float vol = z.volume(v);
                assertTrue(vol > 0 && vol <= 1, layer + " " + kind + " " + p + " v" + v + " volume " + vol);
                if (v > z.vlo()) assertTrue(vol >= z.volume(v - 1), layer + " " + kind + " " + p + " v" + v + " louder with velocity");
            }
        }
    }

    private static void filesExist(Layer layer, InstrumentKind kind, SampleMap map, String name) {
        for (SampleMap.Zone z : map.zones()) {
            assertTrue(layer.has("assets/logos_dimension/sounds/" + z.file() + ".ogg"), name + " missing " + z.file());
            assertTrue(z.frames() > 0, z.file());
            if (z.loops()) {
                assertTrue(z.loopStart() > 0 && z.loopEnd() <= z.frames(), z.file() + " loop inside the sample");
                assertTrue(z.loopEnd() - z.loopStart() >= 44100 / 2, z.file() + " loop at least half a second");
            }
            if (z.releaseSound() != null) assertTrue(layer.has("assets/logos_dimension/sounds/" + z.releaseSound() + ".ogg"), name + " missing " + z.releaseSound());
            assertEquals(kind.handheld() || kind.seated(), z.loops(), name + " " + kind + " " + z.file() + " loops only for bowed and blown notes");
            if (kind.dampsOnRelease()) assertTrue(z.release() > 0, name + " " + kind + " fades on key up");
        }
    }

    @Test void theModItselfSynthesizesEverySampledInstrument() throws Exception {
        for (InstrumentKind kind : sampled()) {
            SampleMap map = load(BASE, kind);
            assertNotNull(map, "the mod ships a synthesized map for " + kind);
            assertEquals("synth", map.quality(), kind + " in the mod is synthesized");
            assertFalse(map.recorded());
            coversEverything(kind, map, "mod");
            filesExist(BASE, kind, map, "mod");
        }
    }

    @Test void theConcertHallPackRecordsItsInstruments() throws Exception {
        int found = 0;
        for (InstrumentKind kind : sampled()) {
            SampleMap map = load(RECORDED, kind);
            if (map == null) continue;
            found++;
            assertTrue(map.recorded(), kind + " in the pack is recorded");
            coversEverything(kind, map, "pack");
            filesExist(RECORDED, kind, map, "pack");
        }
        assertTrue(found >= 14, "the pack records the piano, harp, mallets, harpsichord, strings, winds, brass and timpani (" + found + ")");
        assertTrue(load(RECORDED, InstrumentKind.HARPSICHORD).zones().stream().allMatch(z -> z.releaseSound() != null),
            "every recorded harpsichord note has its jack falling back");
        assertTrue(load(RECORDED, InstrumentKind.GRAND_PIANO).zones().size() >= 5 * 29, "five velocity layers of the piano");
        assertTrue(load(RECORDED, InstrumentKind.CELESTA) == null, "no library records a celesta");
        JsonObject meta;
        try (var in = Files.newBufferedReader(PACK.resolve("pack.mcmeta"))) {
            meta = JsonParser.parseReader(in).getAsJsonObject().getAsJsonObject("pack");
        }
        assertEquals(84, meta.get("min_format").getAsInt(), "resource pack format of Minecraft 26.1");
        assertTrue(Files.exists(PACK.resolve("NOTICE.txt")), "the pack carries the Salamander attribution");
    }

    @Test void instrumentFlags() {
        assertFalse(InstrumentKind.HARPSICHORD.velocitySensitive(), "harpsichords do not respond to touch");
        assertTrue(InstrumentKind.CELESTA.usesPedal() && !InstrumentKind.GLOCKENSPIEL.usesPedal());
        assertTrue(InstrumentKind.PIPE_ORGAN.synthesized() && !InstrumentKind.VIOLIN.synthesized());
        assertTrue(InstrumentKind.CELLO.seated() && InstrumentKind.DOUBLE_BASS.seated() && !InstrumentKind.VIOLA.seated());
        assertTrue(InstrumentKind.VIOLA.handheld() && InstrumentKind.OBOE.handheld() && InstrumentKind.BASSOON.handheld());
        assertFalse(InstrumentKind.TIMPANI.dampsOnRelease(), "timpani ring on");
    }

    @Test void picksTheCoveringZoneOrTheNearestOne() {
        SampleMap.Zone soft = new SampleMap.Zone("a", 60f, 55, 65, 1, 80, 0.5f, 1.5f, 1000, -1, -1, 0.2f, null, 0f);
        SampleMap.Zone loud = new SampleMap.Zone("b", 60.2f, 55, 65, 81, 127, 0.9f, 1.5f, 1000, 10, 900, 0.2f, null, 0f);
        SampleMap map = new SampleMap(SampleMap.FORMAT, "synth", "test", 24, List.of(soft, loud));
        assertSame(soft, map.pick(60, 40));
        assertSame(loud, map.pick(64, 100));
        assertSame(loud, map.pick(70, 120), "outside every zone: nearest velocity layer, then nearest pitch");
        assertEquals(0.5f, soft.volume(80), 1e-6);
        assertEquals(0.5f * Math.pow(0.5, 1.5), soft.volume(40), 1e-6);
        assertEquals(Math.pow(2, -0.2 / 12), loud.ratio(60), 1e-6);
        assertTrue(loud.loops() && !soft.loops());
    }
}
