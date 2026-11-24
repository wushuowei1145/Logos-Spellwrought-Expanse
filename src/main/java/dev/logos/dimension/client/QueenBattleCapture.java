package dev.logos.dimension.client;

import dev.logos.dimension.elven.blight.CorruptedElfQueen;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/** 仅开发场景注册：按游戏刻抓取阶段结束与净化的真实渲染帧，不在正式玩法中启动。 */
public final class QueenBattleCapture {
    private final Path folder;
    private final UUID queen;
    private final Set<String> saved = new HashSet<>();
    private boolean ending;
    private QueenBattleCapture(Path folder, UUID queen) { this.folder = folder; this.queen = queen; }
    public static void start(UUID queen) {
        var folder = Minecraft.getInstance().gameDirectory.toPath().resolve("queen-battle-frames");
        try { Files.createDirectories(folder); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        NeoForge.EVENT_BUS.register(new QueenBattleCapture(folder, queen));
    }
    @SubscribeEvent public void frame(RenderFrameEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) { NeoForge.EVENT_BUS.unregister(this); return; }
        String key = null;
        if (QueenEndingClient.active()) {
            ending = true; key = String.format("ending_%03d", (int) (QueenEndingClient.age() / 3));
        } else if (ending) { NeoForge.EVENT_BUS.unregister(this); return; }
        else for (var e : mc.level.entitiesForRendering()) if (e instanceof CorruptedElfQueen q && q.getUUID().equals(queen) && q.transitioning()) {
            var move = q.npcCombatMove();
            if (move != null) key = String.format("%s_%03d", move.animation(), (mc.level.getGameTime() - move.start()) / 2);
            break;
        }
        if (key == null || !saved.add(key)) return;
        var file = folder.resolve(key + ".png");
        Screenshot.takeScreenshot(mc.getMainRenderTarget(), 2, image -> java.util.concurrent.CompletableFuture.runAsync(() -> {
            try (image) { image.writeToFile(file); } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        }));
    }
}
