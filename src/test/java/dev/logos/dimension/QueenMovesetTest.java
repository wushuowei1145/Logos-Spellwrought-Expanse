package dev.logos.dimension;

import com.google.gson.*;
import dev.logos.dimension.combat.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QueenMovesetTest {
    private JsonObject resource(String path) throws IOException {
        try (var in = Objects.requireNonNull(getClass().getResourceAsStream(path)); var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) { return JsonParser.parseReader(reader).getAsJsonObject(); }
    }
    private Moveset body() throws IOException { return Moveset.parse(resource("/data/logos_dimension/combat/movesets/corrupted_queen.json")); }
    private static final class Actor implements MoveRunner.Actor {
        final List<String> hits = new ArrayList<>();
        @Override public double attackSpeed() { return 1; }
        @Override public void moveStarted(MoveRunner r) {}
        @Override public void contact(MoveRunner r) { hits.add(r.move().name() + ":" + r.contactIndex()); }
    }
    @Test void contactTimesMatchTheActualAnimationMarkers() throws IOException {
        var clips = resource("/assets/logos_dimension/geckolib/animations/entity/corrupted_elf_queen.animation.json").getAsJsonObject("animations");
        for (var move : body().moves().values()) if (move.hasContact()) {
            var animation = clips.getAsJsonObject(move.clip());
            var markers = animation.getAsJsonObject("timeline").entrySet().stream().filter(e -> e.getValue().getAsString().equals("contact"))
                    .map(e -> Double.parseDouble(e.getKey())).sorted().toList();
            assertEquals(move.contacts(), markers, move.name());
            assertEquals(move.length(), animation.get("animation_length").getAsDouble(), move.name());
        }
    }
    /** 战斗里的公主用同伴精灵公主的模型：招式文件里的动画都在她的动画文件里，防御脉冲出手的那刻对得上荆棘的 root 标记。 */
    @Test void princessMovesPlayHerOwnClips() throws IOException {
        var clips = resource("/assets/logos_dimension/geckolib/animations/entity/elf_princess_companion.animation.json").getAsJsonObject("animations");
        var set = Moveset.parse(resource("/data/logos_dimension/combat/movesets/elf_princess.json"));
        for (var move : set.moves().values()) {
            var animation = clips.getAsJsonObject(move.clip());
            assertNotNull(animation, move.name() + " 的动画 " + move.clip() + " 不在她的动画文件里");
            if (move.playback() == Moveset.Playback.LOOP) assertEquals(move.length(), animation.get("animation_length").getAsDouble(), move.name());
            if (move.hasContact()) {
                var markers = animation.getAsJsonObject("timeline").entrySet().stream().filter(e -> e.getValue().getAsString().equals("root"))
                        .map(e -> Double.parseDouble(e.getKey())).sorted().toList();
                assertEquals(move.contacts(), markers, move.name());
            }
        }
    }
    @Test void runnerOwnsContactAndCompletion() throws IOException {
        var actor = new Actor(); var r = new MoveRunner(body(), actor, 0);
        r.force("queen_reap", 100);
        for (int t = 100; t < 124; t++) r.tick(t);
        assertTrue(actor.hits.isEmpty()); r.tick(124); r.tick(125);
        assertEquals(List.of("queen_reap:0"), actor.hits);
        r.tick(148); assertEquals("queen_idle", r.move().name());
    }
    @Test void resistanceInterruptsOldContactsAndRunnerEndsTheWindow() throws IOException {
        var actor = new Actor(); var r = new MoveRunner(body(), actor, 0);
        r.force("queen_thorns", 0); r.tick(20); r.force("queen_resist", 20);
        r.tick(100); assertTrue(actor.hits.isEmpty()); assertEquals("queen_resist", r.move().name());
        r.tick(140); assertEquals("queen_idle", r.move().name());
    }
    @Test void kneelHoldsThroughTheFrenzy() throws IOException {
        var set = body(); var actor = new Actor(); var r = new MoveRunner(set, actor, 0);
        // 第五刀之后她跪着，直到狂乱快结束时被叫起来。
        r.force("queen_kneel", 0);
        for (int t = 0; t <= 300; t++) r.tick(t);
        assertEquals("queen_kneel", r.move().name());
        r.force("queen_rise", 300); r.tick(341);
        assertEquals("queen_idle", r.move().name());
        assertTrue(actor.hits.isEmpty());
    }
    @Test void coreUsesTheSameRunnerAndRepeatsItsDataDefinedChain() throws IOException {
        var set = Moveset.parse(resource("/data/logos_dimension/combat/movesets/queen_core.json"));
        var actor = new Actor(); var r = new MoveRunner(set, actor, 0);
        for (int t = 0; t <= 200; t++) r.tick(t);
        // 阶段二：3.5 秒一下，1.1 秒前摇。
        assertEquals(3, actor.hits.stream().filter(s -> s.startsWith("core_pillar")).count());
        actor.hits.clear();
        r.force("core_frenzy", 200);
        for (int t = 200; t <= 270; t++) r.tick(t);
        // 狂乱：一秒三下。
        assertEquals(10, actor.hits.stream().filter(s -> s.startsWith("core_frenzy")).count(), 1);
    }
}
