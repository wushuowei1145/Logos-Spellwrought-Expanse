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
    @Test void phaseEndingsFinishBeforeOpeningTheNextWindow() throws IOException {
        var set = body(); var actor = new Actor(); var r = new MoveRunner(set, actor, 0);
        for (var entry : Map.of("queen_chain_break", "queen_aid", "queen_core_fall", "queen_bind").entrySet()) {
            r.force(entry.getKey(), 0); var move = r.move();
            assertFalse(move.hasContact()); r.tick(move.length() * 20 - 1);
            assertEquals(entry.getKey(), r.move().name()); r.tick(move.length() * 20);
            assertEquals(entry.getValue(), r.move().name());
        }
        assertTrue(actor.hits.isEmpty());
    }
    @Test void coreUsesTheSameRunnerAndRepeatsItsDataDefinedChain() throws IOException {
        var set = Moveset.parse(resource("/data/logos_dimension/combat/movesets/queen_core.json"));
        var actor = new Actor(); var r = new MoveRunner(set, actor, 0);
        for (int t = 0; t <= 200; t++) r.tick(t);
        assertEquals(3, actor.hits.stream().filter(s -> s.startsWith("core_thorns")).count());
        assertEquals(24, actor.hits.stream().filter(s -> s.startsWith("core_wave")).count());
    }
}
