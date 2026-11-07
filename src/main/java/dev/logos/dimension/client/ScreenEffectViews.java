package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import com.mojang.logging.LogUtils;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.camera.CameraPoint;
import dev.logos.dimension.camera.CameraShot;
import dev.logos.dimension.camera.CameraShots;
import dev.logos.dimension.camera.CameraTestBlock;
import dev.logos.dimension.client.camera.CameraDirector;
import dev.logos.dimension.client.screenfx.ScreenEffectRenderer;
import dev.logos.dimension.screenfx.ScreenEffects;
import dev.logos.dimension.screenfx.ScreenFlash;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * 全屏后期的实际客户端检查，分组 {@code screen_fx}。同一组场景要分别在不开光影、只装 Sodium、Iris 加两种光影包下跑，
 * 因为这里最要紧的就是和它们不冲突。黑白闪只有两拍，截图要停在某一拍上；时长另外按帧记录来查。
 */
public abstract class ScreenEffectViews implements UIScenario {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int GROUND = 100;
    private static final Vec3 FEET = new Vec3(.5, GROUND + 1, .5);
    private static final BlockPos TARGET = new BlockPos(0, GROUND + 1, 4);
    private static final float START_PITCH = 8;

    @Override public void configure(ScenarioOptions o) {
        o.tags("screen_fx").defaultTimeoutMs(30000).scenarioTimeoutMs(120000);
    }

    /** 草地上一座深色的房子、几根柱子和一只铁傀儡，亮处暗处和轮廓都有。 */
    private static void stage(ServerLevel level) {
        for (int x = -24; x <= 24; x++) for (int z = -14; z <= 34; z++) {
            level.setBlockAndUpdate(new BlockPos(x, GROUND, z), Blocks.GRASS_BLOCK.defaultBlockState());
            for (int y = 1; y <= 16; y++) level.setBlockAndUpdate(new BlockPos(x, GROUND + y, z), Blocks.AIR.defaultBlockState());
        }
        for (int x = -12; x <= -5; x++) for (int z = 12; z <= 20; z++) for (int y = 1; y <= 6; y++) {
            boolean shell = x == -12 || x == -5 || z == 12 || z == 20 || y == 6;
            boolean door = z == 12 && x >= -9 && x <= -8 && y <= 3;
            if (shell && !door) level.setBlockAndUpdate(new BlockPos(x, GROUND + y, z), Blocks.DEEPSLATE_BRICKS.defaultBlockState());
        }
        for (int i = 0; i < 4; i++) for (int y = 1; y <= 5; y++) {
            level.setBlockAndUpdate(new BlockPos(4 + i * 4, GROUND + y, 16 - i), Blocks.DARK_OAK_LOG.defaultBlockState());
        }
        level.setBlockAndUpdate(TARGET, CameraTestBlock.BLOCK.get().defaultBlockState());
        var golem = EntityType.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
        golem.setNoAi(true);
        golem.setPos(3.5, GROUND + 1, 10.5);
        level.addFreshEntity(golem);
    }

    protected void arrive(ScenarioBuilder s, String time) {
        s.setGameMode(GameType.SURVIVAL).runCommand("time set " + time).runCommand("weather clear").runCommand("difficulty peaceful")
                .step("设置视口", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.hideGui = false;
                    mc.player.getInventory().setSelectedSlot(0);
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                    mc.options.fov().set(70);
                    mc.options.hideLightningFlash().set(false);
                    var w = mc.getWindow(); w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
                })
                .server("搭场地", ctx -> {
                    stage(ctx.server().overworld());
                    DimensionTravel.teleport(ctx.player(), ctx.server().overworld(), FEET, 0, START_PITCH);
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
                })
                .waitUntil("到位", ctx -> {
                    var mc = Minecraft.getInstance();
                    return mc.player.position().distanceTo(FEET) < .01 && mc.player.onGround() && mc.level.getBlockState(TARGET).is(CameraTestBlock.BLOCK.get());
                })
                .ticks(30);
    }

    protected void cleanup(ScenarioBuilder s) {
        s.teardown("恢复", ctx -> {
            var mc = Minecraft.getInstance();
            ScreenEffectRenderer.hold(null, Vec3.ZERO);
            mc.options.hideLightningFlash().set(false);
            mc.options.keyShift.setDown(false);
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        });
    }

    /** 停在白底黑、黑底白两拍上各截一张，再放开，画面要完全恢复。 */
    protected void stills(ScenarioBuilder s, String prefix) {
        s.screenshot(prefix + "_normal");
        s.step("停在白底黑", ctx -> ScreenEffectRenderer.hold(ScreenFlash.Frame.WHITE, TARGET.getCenter())).ticks(3).screenshot(prefix + "_white");
        s.step("停在黑底白", ctx -> ScreenEffectRenderer.hold(ScreenFlash.Frame.BLACK, TARGET.getCenter())).ticks(3).screenshot(prefix + "_black");
        s.step("放开", ctx -> ScreenEffectRenderer.hold(null, Vec3.ZERO)).ticks(3).screenshot(prefix + "_restored");
    }

    @LDLRegisterClient(name = "screen_fx_day", group = "screen_fx", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Day extends ScreenEffectViews {
        @Override public void define(ScenarioBuilder s) {
            arrive(s, "6000");
            stills(s, "day");
            cleanup(s);
        }
    }

    /** 夜里画面整体很暗，分界要跟着平均明度走，否则就是一片全黑。 */
    @LDLRegisterClient(name = "screen_fx_night", group = "screen_fx", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Night extends ScreenEffectViews {
        @Override public void define(ScenarioBuilder s) {
            arrive(s, "18000");
            stills(s, "night");
            cleanup(s);
        }
    }

    /** 真放一次：先白后黑各一拍，合计约 1/12 秒，放完回到原画；隐藏闪烁的选项和两次之间的间隔都要生效。 */
    @LDLRegisterClient(name = "screen_fx_timing", group = "screen_fx", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Timing extends ScreenEffectViews {
        /** 每个渲染帧的时间和当时放的那一拍。 */
        public static final class Frames {
            final List<Long> at = new ArrayList<>();
            final List<ScreenFlash.Frame> frames = new ArrayList<>();

            @SubscribeEvent public void frame(RenderFrameEvent.Post event) {
                at.add(Util.getNanos());
                frames.add(ScreenEffectRenderer.currentFrame());
            }

            /** 去掉连续重复之后的序列。 */
            List<ScreenFlash.Frame> sequence() {
                var out = new ArrayList<ScreenFlash.Frame>();
                for (var frame : frames) if (frame != null && (out.isEmpty() || out.getLast() != frame)) out.add(frame);
                return out;
            }

            double millis() {
                long first = -1, last = -1;
                for (int i = 0; i < frames.size(); i++) if (frames.get(i) != null) {
                    if (first < 0) first = at.get(i);
                    last = at.get(i);
                }
                return (last - first) / 1e6;
            }
        }

        private final Frames log = new Frames();

        @Override public void define(ScenarioBuilder s) {
            arrive(s, "6000");
            s.step("开始记录", ctx -> NeoForge.EVENT_BUS.register(log));
            s.server("闪一次", ctx -> ScreenEffects.flash(ctx.player(), ScreenFlash.at(CameraPoint.world(TARGET.getCenter()))));
            s.ticks(20);
            s.step("检查这一次", ctx -> {
                NeoForge.EVENT_BUS.unregister(log);
                LOG.info("[screen fx] sequence {} over {} ms across {} frames", log.sequence(), log.millis(), log.frames.size());
                ctx.check("先白后黑", log.sequence().equals(List.of(ScreenFlash.Frame.WHITE, ScreenFlash.Frame.BLACK)));
                ctx.check("两拍约 1/12 秒", log.millis() > 30 && log.millis() < 200);
                ctx.check("放完回到原画", ScreenEffectRenderer.currentFrame() == null);
            });
            s.step("打开隐藏闪烁的选项", ctx -> {
                Minecraft.getInstance().options.hideLightningFlash().set(true);
                ScreenEffectRenderer.play(ScreenFlash.at(CameraPoint.world(TARGET.getCenter())));
            }).ticks(1).step("不该闪", ctx -> {
                ctx.check("隐藏闪烁时不闪", ScreenEffectRenderer.currentFrame() == null);
                Minecraft.getInstance().options.hideLightningFlash().set(false);
            });
            s.ticks(20);
            s.step("连续两次", ctx -> {
                log.frames.clear();
                log.at.clear();
                NeoForge.EVENT_BUS.register(log);
                var flash = ScreenFlash.at(CameraPoint.world(TARGET.getCenter()));
                ScreenEffectRenderer.play(flash);
                ScreenEffectRenderer.play(flash);
            }).ticks(5).step("第二次被挡下", ctx -> {
                ScreenEffectRenderer.play(ScreenFlash.at(CameraPoint.world(TARGET.getCenter())));
            }).ticks(5).step("检查间隔", ctx -> {
                NeoForge.EVENT_BUS.unregister(log);
                ctx.check("短时间内只闪一次", log.sequence().equals(List.of(ScreenFlash.Frame.WHITE, ScreenFlash.Frame.BLACK)));
            });
            cleanup(s);
        }
    }

    /** 走真实的挖掘流程：潜行挖掉测试方块，闪一下，镜头升起，带遮幅和蒙版，结束后全部退掉。 */
    @LDLRegisterClient(name = "screen_fx_camera", group = "screen_fx", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Camera extends ScreenEffectViews {
        private Runnable mining = () -> {};

        @SubscribeEvent public void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
            mining.run();
        }

        @Override public void define(ScenarioBuilder s) {
            arrive(s, "6000");
            s.step("按住潜行", ctx -> {
                Minecraft.getInstance().options.keyShift.setDown(true);
                NeoForge.EVENT_BUS.register(this);
            });
            s.waitUntil("蹲下了", ctx -> Minecraft.getInstance().player.isShiftKeyDown()).ticks(5);
            s.step("开始挖", ctx -> {
                var mc = Minecraft.getInstance();
                mc.gameMode.startDestroyBlock(TARGET, Direction.NORTH);
                mining = () -> mc.gameMode.continueDestroyBlock(TARGET, Direction.NORTH);
            });
            s.waitUntil("挖掉后开始运镜", ctx -> CameraDirector.active()).step("停止挖", ctx -> mining = () -> {});
            s.waitUntil("镜头停稳", ctx -> CameraDirector.age() >= 40);
            s.screenshot("cinema_hold");
            s.step("蒙版和遮幅完全显现", ctx -> {
                ctx.check("蒙版完全显现", CameraDirector.grade() == 1);
                ctx.check("遮幅完全进来", CameraDirector.bars() == 1);
            });
            s.waitUntil("运镜结束", ctx -> !CameraDirector.active()).ticks(5);
            s.screenshot("cinema_after");
            s.step("全部退掉", ctx -> {
                NeoForge.EVENT_BUS.unregister(this);
                ctx.check("蒙版退掉了", CameraDirector.grade() == 0);
                ctx.check("遮幅退掉了", CameraDirector.bars() == 0);
            });
            cleanup(s);
        }
    }

    /** 不带蒙版的运镜不该有任何后期：确认默认效果没有被这次改动碰到。 */
    @LDLRegisterClient(name = "screen_fx_default_off", group = "screen_fx", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class DefaultOff extends ScreenEffectViews {
        @Override public void define(ScenarioBuilder s) {
            arrive(s, "6000");
            s.server("普通运镜", ctx -> CameraShots.play(ctx.player(), CameraShot.of(CameraPoint.world(FEET.add(0, 8, 0)), CameraPoint.world(TARGET.getCenter())).timing(8, 20, 8)));
            s.waitUntil("开始", ctx -> CameraDirector.active()).waitUntil("停稳", ctx -> CameraDirector.age() >= 14);
            s.step("没有后期", ctx -> {
                ctx.check("默认没有蒙版", CameraDirector.grade() == 0);
                ctx.check("默认不闪", ScreenEffectRenderer.currentFrame() == null);
            });
            s.waitUntil("结束", ctx -> !CameraDirector.active());
            cleanup(s);
        }
    }
}
