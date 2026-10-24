package dev.logos.dimension;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.logos.dimension.npc.NpcAnimations;
import dev.logos.dimension.npc.NpcAnimations.HandItem;
import dev.logos.dimension.npc.NpcAnimations.Snapshot;

class NpcAnimationsTest {
    private static final Set<String> ALL = Set.of("death", "sleep", "swim", "ladder_up", "ladder_stillness", "ladder_down",
            "boat", "chair", "sit", "swim_stand", "attacked", "jump", "run", "walk", "idle");

    /** 站在地上不动。 */
    private static Snapshot still() {
        return new Snapshot(false, false, false, false, 0, false, false, false, false, false, true, false, 0);
    }

    private static Snapshot with(Snapshot s, String field) {
        return switch (field) {
            case "dead" -> new Snapshot(true, s.sleeping(), s.swimming(), s.climbing(), s.verticalSpeed(), s.inBoat(), s.passenger(), s.sitting(), s.inWater(), s.hurt(), s.onGround(), s.sprinting(), s.walkSpeed());
            case "hurt" -> new Snapshot(s.dead(), s.sleeping(), s.swimming(), s.climbing(), s.verticalSpeed(), s.inBoat(), s.passenger(), s.sitting(), s.inWater(), true, s.onGround(), s.sprinting(), s.walkSpeed());
            case "water" -> new Snapshot(s.dead(), s.sleeping(), s.swimming(), s.climbing(), s.verticalSpeed(), s.inBoat(), s.passenger(), s.sitting(), true, s.hurt(), s.onGround(), s.sprinting(), s.walkSpeed());
            case "sprint" -> new Snapshot(s.dead(), s.sleeping(), s.swimming(), s.climbing(), s.verticalSpeed(), s.inBoat(), s.passenger(), s.sitting(), s.inWater(), s.hurt(), s.onGround(), true, 0.6f);
            case "walk" -> new Snapshot(s.dead(), s.sleeping(), s.swimming(), s.climbing(), s.verticalSpeed(), s.inBoat(), s.passenger(), s.sitting(), s.inWater(), s.hurt(), s.onGround(), s.sprinting(), 0.3f);
            case "air" -> new Snapshot(s.dead(), s.sleeping(), s.swimming(), s.climbing(), s.verticalSpeed(), s.inBoat(), s.passenger(), s.sitting(), s.inWater(), s.hurt(), false, s.sprinting(), s.walkSpeed());
            case "climbUp" -> new Snapshot(s.dead(), s.sleeping(), s.swimming(), true, 2, s.inBoat(), s.passenger(), s.sitting(), s.inWater(), s.hurt(), s.onGround(), s.sprinting(), s.walkSpeed());
            case "sit" -> new Snapshot(s.dead(), s.sleeping(), s.swimming(), s.climbing(), s.verticalSpeed(), s.inBoat(), s.passenger(), true, s.inWater(), s.hurt(), s.onGround(), s.sprinting(), s.walkSpeed());
            default -> throw new IllegalArgumentException(field);
        };
    }

    @Test void mainStateFollowsTlmPriority() {
        assertEquals("idle", NpcAnimations.mainState(still(), ALL::contains));
        assertEquals("walk", NpcAnimations.mainState(with(still(), "walk"), ALL::contains));
        assertEquals("run", NpcAnimations.mainState(with(still(), "sprint"), ALL::contains));
        assertEquals("jump", NpcAnimations.mainState(with(still(), "air"), ALL::contains));
        assertEquals("ladder_up", NpcAnimations.mainState(with(with(still(), "air"), "climbUp"), ALL::contains));
        assertEquals("sit", NpcAnimations.mainState(with(with(still(), "walk"), "sit"), ALL::contains));
        // 同级按注册顺序：水中受伤显示 swim_stand（与 TLM 相同）
        assertEquals("swim_stand", NpcAnimations.mainState(with(with(still(), "water"), "hurt"), ALL::contains));
        assertEquals("attacked", NpcAnimations.mainState(with(with(still(), "walk"), "hurt"), ALL::contains));
        assertEquals("death", NpcAnimations.mainState(with(with(still(), "hurt"), "dead"), ALL::contains));
    }

    @Test void missingAnimationsFallThroughInsteadOfRequestingThem() {
        Set<String> few = Set.of("idle", "walk");
        assertEquals("walk", NpcAnimations.mainState(with(still(), "sprint"), few::contains));
        assertEquals("idle", NpcAnimations.mainState(with(still(), "air"), few::contains));
        assertEquals("idle", NpcAnimations.mainState(with(still(), "hurt"), few::contains));
        assertNull(NpcAnimations.mainState(still(), Set.<String>of()::contains));
    }

    private static HandItem item(String id, String classify, String use, String... tags) {
        Set<String> t = Set.of(tags);
        return new HandItem(false, id, classify, use, false, t::contains);
    }

    @Test void handNamesMatchInTlmOrder() {
        HandItem sword = item("minecraft:iron_sword", "sword", "none", "minecraft:swords");
        Set<String> names = Set.of("hold_mainhand$minecraft:iron_sword", "hold_mainhand#minecraft:swords", "hold_mainhand:sword");
        assertEquals("hold_mainhand$minecraft:iron_sword", NpcAnimations.holdAnimation(true, sword, names));
        assertEquals("hold_mainhand#minecraft:swords", NpcAnimations.holdAnimation(true, sword, Set.of("hold_mainhand#minecraft:swords", "hold_mainhand:sword")));
        assertEquals("hold_mainhand:sword", NpcAnimations.holdAnimation(true, sword, Set.of("hold_mainhand:sword")));
        HandItem bow = item("minecraft:bow", null, "bow");
        assertEquals("hold_mainhand:bow", NpcAnimations.holdAnimation(true, bow, Set.of("hold_mainhand:bow")));
        // TLM 的物品 ID 写法用 $，带冒号的 ID 不是使用动画类型
        assertNull(NpcAnimations.holdAnimation(true, bow, Set.of("hold_mainhand:minecraft:bow")));
        assertNull(NpcAnimations.holdAnimation(false, bow, Set.of("hold_mainhand:bow")));
    }

    @Test void emptyHandsAndDefaults() {
        HandItem empty = new HandItem(true, "", null, "none", false, t -> false);
        assertEquals("hold_mainhand:empty", NpcAnimations.holdAnimation(true, empty, Set.of("hold_mainhand:empty")));
        assertNull(NpcAnimations.holdAnimation(true, empty, Set.of()));
        assertEquals("swing_hand", NpcAnimations.swingAnimation(true, empty, Set.of("swing_hand")));
        assertEquals("swing_offhand", NpcAnimations.swingAnimation(false, empty, Set.of("swing_offhand")));
        HandItem sword = item("minecraft:iron_sword", "sword", "none");
        assertEquals("swing:sword", NpcAnimations.swingAnimation(true, sword, Set.of("swing_hand", "swing:sword")));
        HandItem food = item("minecraft:bread", null, "eat");
        assertEquals("use_mainhand:eat", NpcAnimations.useAnimation(true, food, Set.of("use_mainhand", "use_mainhand:eat")));
        assertEquals("use_offhand", NpcAnimations.useAnimation(false, food, Set.of("use_offhand")));
    }

    @Test void chargedCrossbowComesFirst() {
        HandItem crossbow = new HandItem(false, "minecraft:crossbow", null, "crossbow", true, t -> false);
        assertEquals("hold_mainhand:charged_crossbow", NpcAnimations.holdAnimation(true, crossbow,
                Set.of("hold_mainhand:charged_crossbow", "hold_mainhand:crossbow")));
        assertEquals("hold_mainhand:crossbow", NpcAnimations.holdAnimation(true, crossbow, Set.of("hold_mainhand:crossbow")));
    }
}
