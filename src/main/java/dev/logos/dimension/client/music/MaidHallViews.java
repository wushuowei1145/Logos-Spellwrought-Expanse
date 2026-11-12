package dev.logos.dimension.client.music;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.dimension.music.InstrumentKind;
import dev.logos.dimension.music.Instruments;
import dev.logos.dimension.music.orchestra.PodiumBlockEntity;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 车万女仆在音乐厅里（docs/ORCHESTRA.md 第 16 节；要用 -Ptlm 把女仆模组加进开发环境）：基岩版的默认模型坐在钢琴、
 * 空的小提琴凳（拿小提琴）、大提琴、低音提琴、钟琴、定音鼓前；GeckoLib 的酒狐坐在钢片琴前、到大提琴、管钟的工位上。
 */
@LDLRegisterClient(name = "orchestra_maids_ui", group = "instrument", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class MaidHallViews extends HallViews {
    private static final String WINEFOX = "geckolib:winefox";
    private static final BlockPos CELLO = at(5, 3, -7), GECKO_CELLO = at(7, 3, -7), BASS = at(12, 3, -8), CELESTA_BENCH = at(-8, 4, -14);
    private static int pianist = -1, violinist = -1, cellist = -1, bassist = -1, glockenist = -1, timpanist = -1;
    private static int geckoKeys = -1, geckoCello = -1, geckoBells = -1;

    @Override
    public void define(ScenarioBuilder s) {
        ScenarioBuilder b = conduct(stage(s)
            .check("Touhou Little Maid is installed (-Ptlm)", ctx -> net.neoforged.fml.ModList.get().isLoaded("touhou_little_maid"))
            .server("maids take over seats and posts", ctx -> {
                ServerLevel level = ctx.server().overworld();
                var me = ctx.player();
                for (BlockPos p : new BlockPos[]{PIANO_BENCH, CELLO, GECKO_CELLO, BASS, GLOCKENSPIEL, TUBULAR_BELLS, TIMPANI_BENCH_WEST, CELESTA_BENCH}) {
                    vacate(level, p);
                }
                pianist = seat(level, MaidMusicians.testMaid(level, null), PIANO_BENCH, null, me).getId();
                violinist = seat(level, MaidMusicians.testMaid(level, null), FREE_VIOLIN, Instruments.VIOLIN.get(), me).getId();
                cellist = post(level, MaidMusicians.testMaid(level, null), CELLO).getId();
                bassist = post(level, MaidMusicians.testMaid(level, null), BASS).getId();
                glockenist = post(level, MaidMusicians.testMaid(level, null), GLOCKENSPIEL).getId();
                timpanist = seat(level, MaidMusicians.testMaid(level, null), TIMPANI_BENCH_WEST, null, me).getId();
                geckoKeys = seat(level, MaidMusicians.testMaid(level, WINEFOX), CELESTA_BENCH, null, me).getId();
                geckoCello = post(level, MaidMusicians.testMaid(level, WINEFOX), GECKO_CELLO).getId();
                geckoBells = post(level, MaidMusicians.testMaid(level, WINEFOX), TUBULAR_BELLS).getId();
            })
            .waitUntil("the maids arrive", ctx -> musician(pianist) != null && musician(timpanist) != null && musician(glockenist) != null))
            .check("the maids play what they sit at", ctx -> {
                var p = musician(pianist);
                var v = musician(violinist);
                var c = musician(cellist);
                var bass = musician(bassist);
                var g = musician(glockenist);
                var t = musician(timpanist);
                return p != null && p.mode() == MusicianPoses.Mode.KEYS && v != null && v.mode() == MusicianPoses.Mode.HANDHELD
                    && v.kind() == InstrumentKind.VIOLIN && c != null && c.mode() == MusicianPoses.Mode.BOWED && !c.standing()
                    && bass != null && bass.standing() && g != null && g.mode() == MusicianPoses.Mode.MALLETS
                    && t != null && t.mode() == MusicianPoses.Mode.MALLETS && t.kind() == InstrumentKind.TIMPANI;
            })
            .check("standing posts are standing posts", ctx -> {
                var e = Minecraft.getInstance().level.getEntity(bassist);
                var c = Minecraft.getInstance().level.getEntity(cellist);
                return e != null && e.getVehicle() != null && e.getVehicle().getType() == Instruments.STANDING_POST.get()
                    && c != null && c.getVehicle() != null && c.getVehicle().getType() == Instruments.SEAT.get();
            })
            .step("count from here", ctx -> MaidMusicians.posed = 0)
            .waitUntil("the bedrock maids are posed by the bench pose", ctx -> MaidMusicians.posed > 20);
        b = shot(b, "maid_piano", point(-6.8, 5.2, -3.4), point(-5, 4.6, -6.2));
        b = shotOf(b, "maid_piano_side", () -> pianist, 2.8, 1.0, 1.4);
        b = shot(b, "maid_violin", point(-12.4, 5.0, -5.6), point(-13, 4.6, -8));
        b = shot(b, "maid_cellos_basses", point(4.0, 5.8, -3.6), point(8.5, 4.2, -8.6));
        b = shotOf(b, "maid_cello_side", () -> cellist, -2.8, 1.3, 1.4);
        b = shotOf(b, "maid_bass_side", () -> bassist, -2.8, 1.4, 1.4);
        b = shotOf(b, "maid_glockenspiel_side", () -> glockenist, 2.8, 1.3, 1.4);
        b = shot(b, "maid_glockenspiel", point(-3.9, 6.8, -11.0), point(-5.6, 5.0, -13.3));
        b = shot(b, "maid_timpani", point(-8.4, 8.8, -15.8), point(-8.4, 7.0, -19.8));
        b = shotOf(b, "maid_gecko_keys_side", () -> geckoKeys, 1.6, 1.8, 1.6);
        b = shotOf(b, "maid_gecko_cello_side", () -> geckoCello, -2.8, 1.3, 1.4);
        b = shotOf(b, "maid_gecko_bells_side", () -> geckoBells, -2.8, 1.4, 1.4);
        b.teardownServer("stop playing", ctx -> {
            ServerLevel level = ctx.server().overworld();
            if (level.getBlockEntity(PODIUM) instanceof PodiumBlockEntity be) be.stop();
        });
        b.teardown("my own eyes again", ctx -> {
            var mc = Minecraft.getInstance();
            mc.setScreen(null);
            if (mc.player != null) mc.setCameraEntity(mc.player);
            mc.options.hideGui = false;
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        });
    }
}
