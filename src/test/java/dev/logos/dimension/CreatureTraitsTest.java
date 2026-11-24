package dev.logos.dimension;

import com.google.gson.JsonParser;
import dev.logos.dimension.combat.CreatureTraits;
import dev.logos.lang.Element;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CreatureTraitsTest {
    private static CreatureTraits queen() throws Exception {
        try (var in = CreatureTraitsTest.class.getResourceAsStream("/data/logos_dimension/creature_traits/corrupted_queen.json")) {
            assertNotNull(in, "缺少腐化女王的抗性表");
            return CreatureTraits.parse(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
        }
    }

    @Test void queenResistancesMatchTheDesign() throws Exception {
        var t = queen();
        assertEquals(.5, t.attributes().get(Identifier.parse("logos:fire_spell_resistance")));
        assertEquals(.5, t.attributes().get(Identifier.parse("logos:arcane_spell_resistance")));
        for (String e : new String[]{"physical", "frost", "lightning"}) assertEquals(.85, t.attributes().get(Identifier.parse("logos:" + e + "_spell_resistance")), e);
        assertEquals(.3, t.attributes().get(Identifier.parse("logos:life_spell_vulnerability")));
        assertEquals(0, t.attributes().get(Identifier.parse("logos:spell_resistance")), "旧版 75% 总法术抗性要被覆盖成 0");
        var arcane = t.rules().stream().filter(r -> r.element() == Element.ARCANE).findFirst().orElseThrow();
        assertEquals(.3, arcane.multiply(), 1e-9);
        assertNotNull(arcane.unlessType(), "普通奥秘的额外倍数不能作用于狐火");
    }

    @Test void wardCapsAtSixtyPercent() throws Exception {
        var ward = queen().ward();
        assertNotNull(ward);
        assertEquals(64, ward.radius());
        assertEquals(.15, ward.reduction(5), 1e-9);
        assertEquals(.6, ward.reduction(20), 1e-9);
        assertEquals(.6, ward.reduction(50), 1e-9);
        assertEquals(0, ward.reduction(0), 1e-9);
    }
}
