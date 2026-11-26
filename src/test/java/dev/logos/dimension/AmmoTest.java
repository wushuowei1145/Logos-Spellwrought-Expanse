package dev.logos.dimension;

import dev.logos.dimension.firearm.Buckshot;
import dev.logos.dimension.firearm.Rifle;
import dev.logos.dimension.firearm.RoundMetal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 普通弹的数值分工、压制配方和铸弹台模型（docs/FIREARMS.md 第 5、6 节）。 */
class AmmoTest {
    private static final List<String> ROUNDS = List.of("copper_buckshot_shell", "buckshot_shell", "netherite_buckshot_shell",
            "copper_rifle_round", "iron_rifle_round", "netherite_rifle_round", "blank_round");

    @Test void metalsGetHeavierAndOnlyNetheritePierces() {
        assertTrue(RoundMetal.COPPER.damage < RoundMetal.IRON.damage && RoundMetal.IRON.damage < RoundMetal.NETHERITE.damage);
        assertEquals(1, RoundMetal.IRON.damage, 1e-9, "铁弹头是基准，霰弹的原数值不变");
        assertEquals(0, RoundMetal.COPPER.pierce);
        assertEquals(0, RoundMetal.IRON.pierce);
        assertEquals(1, RoundMetal.NETHERITE.pierce);
    }

    @Test void rifleLosesToBuckshotUpCloseAndWinsFarOut() {
        assertEquals(1, Rifle.falloff(Rifle.FULL), 1e-9);
        assertEquals(Rifle.FAR, Rifle.falloff(Rifle.RANGE), 1e-9);
        assertTrue(Rifle.RANGE > Buckshot.RANGE && Rifle.SPREAD < Buckshot.SPREAD);
        // 贴脸：霰弹八颗全中远胜一颗步枪弹。
        assertTrue(Buckshot.PELLETS * Buckshot.PELLET_DAMAGE > Rifle.DAMAGE * 1.5);
        // 二十格：霰弹就算全中也不如一颗步枪弹；过了霰弹射程只有步枪弹打得到。
        assertTrue(Buckshot.PELLETS * Buckshot.PELLET_DAMAGE * Buckshot.falloff(20) < Rifle.DAMAGE * Rifle.falloff(20));
        assertTrue(Rifle.DAMAGE * Rifle.falloff(Rifle.RANGE) > 6, "射程尽头还有铁剑一击的伤害");
    }

    @Test void everyRoundIsPressedEightAtATimeFromItsCasingAndHead() throws Exception {
        Map<String, String> casings = Map.of("buckshot_shell", "minecraft:paper", "rifle_round", "minecraft:copper_ingot",
                "blank_round", "minecraft:copper_ingot");
        for (var round : ROUNDS) {
            var r = LongswordMotionTest.read("/data/logos_dimension/recipe/ammo_pressing/" + round + ".json");
            assertEquals("logos_dimension:ammo_pressing", r.get("type").getAsString(), round);
            assertEquals("minecraft:gunpowder", r.get("powder").getAsString(), round);
            var result = r.getAsJsonObject("result");
            assertEquals("logos_dimension:" + round, result.get("id").getAsString());
            assertEquals(8, result.get("count").getAsInt(), round + " 一批八发");
            String shape = round.endsWith("buckshot_shell") ? "buckshot_shell" : round.endsWith("rifle_round") ? "rifle_round" : "blank_round";
            assertEquals(casings.get(shape), r.get("casing").getAsString(), round + " 的弹壳定弹种");
            String head = r.get("head").getAsString();
            if (round.startsWith("copper")) assertEquals("minecraft:copper_ingot", head);
            else if (round.startsWith("netherite")) assertEquals("minecraft:netherite_scrap", head);
            else if (round.equals("blank_round")) assertEquals("minecraft:amethyst_shard", head);
            else assertEquals("minecraft:iron_ingot", head);
            assertNotNull(AmmoTest.class.getResource("/assets/logos_dimension/textures/item/" + round + ".png"), round);
            assertNotNull(AmmoTest.class.getResource("/assets/logos_dimension/items/" + round + ".json"), round);
        }
    }

    @Test void theBenchModelStaysInsideItsBlock() throws Exception {
        var model = LongswordMotionTest.read("/assets/logos_dimension/models/block/ammo_bench.json");
        var elements = model.getAsJsonArray("elements");
        assertTrue(elements.size() > 10);
        for (var e : elements) {
            var from = e.getAsJsonObject().getAsJsonArray("from");
            var to = e.getAsJsonObject().getAsJsonArray("to");
            for (int i = 0; i < 3; i++) {
                assertTrue(from.get(i).getAsDouble() >= 0 && to.get(i).getAsDouble() <= 16, "铸弹台不伸出自己那一格：" + e);
                assertTrue(from.get(i).getAsDouble() < to.get(i).getAsDouble(), "方块有厚度：" + e);
            }
        }
        var states = LongswordMotionTest.read("/assets/logos_dimension/blockstates/ammo_bench.json").getAsJsonObject("variants");
        for (var facing : List.of("north", "east", "south", "west")) assertTrue(states.has("facing=" + facing), facing);
    }
}
