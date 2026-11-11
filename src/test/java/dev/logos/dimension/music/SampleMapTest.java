package dev.logos.dimension.music;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 采样表（tools/instrument_sources.py 生成）：每件采样乐器音域内每个音、每个力度都有采样，文件、循环点、松键声都在。 */
class SampleMapTest {
    private static SampleMap load(InstrumentKind kind) throws Exception {
        try (var in = SampleMapTest.class.getResourceAsStream("/assets/logos_dimension/instruments/" + kind.id() + ".json")) {
            assertNotNull(in, "sample map for " + kind);
            return SampleMap.parse(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
        }
    }

    @Test void everyInstrumentCoversItsRangeAtEveryVelocity() throws Exception {
        for (InstrumentKind kind : InstrumentKind.values()) {
            if (kind.synthesized()) continue;
            SampleMap map = load(kind);
            assertFalse(map.zones().isEmpty(), kind + " has zones");
            for (int p = kind.low(); p <= kind.high(); p++) {
                for (int v = 1; v <= 127; v++) {
                    SampleMap.Zone z = map.pick(p, v);
                    assertNotNull(z, kind + " " + p + " v" + v);
                    assertTrue(z.lo() <= p && p <= z.hi() && z.vlo() <= v && v <= z.vhi(), kind + " " + p + " v" + v + " not covered exactly");
                    // 变调不超过 4 个半音（采样每隔三度左右一个）
                    assertTrue(Math.abs(p - z.key()) <= 4.01, kind + " " + p + " shifted from " + z.key());
                    // 各采样存的电平不同（各自放大到峰值 -1 dBFS），换层时响度是否接得上由生成脚本按响度核对（见 docs/INSTRUMENT_SAMPLES.md）；
                    // 这里只核对音量在 (0, 1] 内、同一区里力度越大越响
                    float vol = z.volume(v);
                    assertTrue(vol > 0 && vol <= 1, kind + " " + p + " v" + v + " volume " + vol);
                    if (v > z.vlo()) assertTrue(vol >= z.volume(v - 1), kind + " " + p + " v" + v + " louder with velocity");
                }
            }
        }
    }

    @Test void samplesLoopsAndReleasesExist() throws Exception {
        for (InstrumentKind kind : InstrumentKind.values()) {
            if (kind.synthesized()) continue;
            for (SampleMap.Zone z : load(kind).zones()) {
                assertNotNull(SampleMapTest.class.getResource("/assets/logos_dimension/sounds/" + z.file() + ".ogg"), "missing " + z.file());
                assertTrue(z.frames() > 0, z.file());
                if (z.loops()) {
                    assertTrue(z.loopStart() > 0 && z.loopEnd() <= z.frames(), z.file() + " loop inside the sample");
                    assertTrue(z.loopEnd() - z.loopStart() >= 44100 / 2, z.file() + " loop at least half a second");
                }
                if (z.releaseSound() != null) {
                    assertNotNull(SampleMapTest.class.getResource("/assets/logos_dimension/sounds/" + z.releaseSound() + ".ogg"), "missing " + z.releaseSound());
                }
            }
        }
    }

    @Test void heldInstrumentsSustainAndTheRestRingOut() throws Exception {
        for (InstrumentKind kind : InstrumentKind.values()) {
            if (kind.synthesized()) continue;
            for (SampleMap.Zone z : load(kind).zones()) {
                assertEquals(kind.handheld(), z.loops(), kind + " " + z.file() + " loops only for bowed and blown notes");
                if (kind.dampsOnRelease()) assertTrue(z.release() > 0, kind + " fades on key up");
            }
        }
        assertTrue(load(InstrumentKind.HARPSICHORD).zones().stream().allMatch(z -> z.releaseSound() != null), "every harpsichord note has its jack falling back");
        assertTrue(load(InstrumentKind.GRAND_PIANO).zones().size() >= 5 * 29, "five velocity layers of the piano");
        assertFalse(InstrumentKind.HARPSICHORD.velocitySensitive(), "harpsichords do not respond to touch");
        assertTrue(InstrumentKind.CELESTA.usesPedal() && !InstrumentKind.GLOCKENSPIEL.usesPedal());
        assertTrue(InstrumentKind.PIPE_ORGAN.synthesized() && !InstrumentKind.VIOLIN.synthesized());
    }

    @Test void picksTheCoveringZoneOrTheNearestOne() {
        SampleMap.Zone soft = new SampleMap.Zone("a", 60f, 55, 65, 1, 80, 0.5f, 1.5f, 1000, -1, -1, 0.2f, null, 0f);
        SampleMap.Zone loud = new SampleMap.Zone("b", 60.2f, 55, 65, 81, 127, 0.9f, 1.5f, 1000, 10, 900, 0.2f, null, 0f);
        SampleMap map = new SampleMap("test", 24, List.of(soft, loud));
        assertSame(soft, map.pick(60, 40));
        assertSame(loud, map.pick(64, 100));
        assertSame(loud, map.pick(70, 120), "outside every zone: nearest velocity layer, then nearest pitch");
        assertEquals(0.5f, soft.volume(80), 1e-6);
        assertEquals(0.5f * Math.pow(0.5, 1.5), soft.volume(40), 1e-6);
        assertEquals(Math.pow(2, -0.2 / 12), loud.ratio(60), 1e-6);
        assertTrue(loud.loops() && !soft.loops());
    }
}
