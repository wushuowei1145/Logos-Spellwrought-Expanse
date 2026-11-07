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
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Quaternionf;
import org.slf4j.Logger;

/**
 * 运镜的实际客户端检查，分组 {@code camera_shot}。测试方块走真实的挖掘流程（客户端发包、服务端校验进度），
 * 其余场景直接调 {@link CameraShots#play}，覆盖 Boss 会用到的跟随实体、打断和提前结束。
 * 每帧记下镜头位姿，按缓动曲线的最大速度检查有没有跳变，和帧率无关。
 */
public abstract class CameraShotViews implements UIScenario {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int GROUND = 100;
    private static final Vec3 FEET = new Vec3(.5, GROUND + 1, .5);
    /** 站在 {@link #FEET} 朝南（偏航 0）俯视 20 度时，准星正好落在它上面。 */
    private static final BlockPos TARGET = new BlockPos(0, GROUND + 1, 3);
    private static final float START_PITCH = 20;
    /** 五次缓动的最大斜率：实际速度最多是平均速度的这么多倍。 */
    private static final double PEAK = 1.875;

    protected final FrameLog log = new FrameLog();
    protected Vec3 eye = Vec3.ZERO;

    @Override public void configure(ScenarioOptions o) {
        o.tags("camera_shot").defaultTimeoutMs(60000).scenarioTimeoutMs(180000);
    }

    /** 按帧记下镜头位姿；顺带每刻跑一下场景要做的事（比如继续挖方块）。 */
    public static final class FrameLog {
        private record Frame(double time, Vec3 pos, Quaternionf rotation, float bars) {}
        private final List<Frame> frames = new ArrayList<>();
        private int ticks;
        public Runnable onTick = () -> {};

        void start() {
            frames.clear();
            NeoForge.EVENT_BUS.register(this);
        }

        void stop() {
            NeoForge.EVENT_BUS.unregister(this);
        }

        @SubscribeEvent public void tick(ClientTickEvent.Post event) {
            ticks++;
            onTick.run();
        }

        @SubscribeEvent public void frame(RenderFrameEvent.Post event) {
            Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
            frames.add(new Frame(ticks + event.getPartialTick().getGameTimeDeltaPartialTick(true), camera.position(), new Quaternionf(camera.rotation()), CameraDirector.bars()));
        }

        /** 遮幅不跳变，相邻两帧的变化不超过每刻上限。 */
        boolean barsSmooth(String name, double perTick) {
            boolean ok = true;
            for (int i = 1; i < frames.size(); i++) {
                Frame a = frames.get(i - 1), b = frames.get(i);
                if (Math.abs(b.bars - a.bars) > perTick * Math.max(0, b.time - a.time) + .01) {
                    ok = false;
                    LOG.warn("[camera shot] {} bars jump at t={}: {} to {}", name, b.time, a.bars, b.bars);
                }
            }
            return ok;
        }

        /** 遮幅从屏幕外滑到完全进来，再滑回屏幕外，中途不回头。 */
        boolean barsInThenOut() {
            int peak = -1;
            for (int i = 0; i < frames.size() && peak < 0; i++) if (frames.get(i).bars >= 1) peak = i;
            if (peak < 0 || frames.getFirst().bars != 0 || frames.getLast().bars != 0) return false;
            for (int i = 1; i < frames.size(); i++) {
                float change = frames.get(i).bars - frames.get(i - 1).bars;
                if (i <= peak ? change < 0 : change > 0) return false;
            }
            return true;
        }

        /** 任意相邻两帧的位移和转角都不超过给定的每刻上限（再留一点浮点和帧内插值的余量）。 */
        boolean smooth(String name, double blocksPerTick, double degreesPerTick) {
            double worstMove = 0, worstTurn = 0;
            boolean ok = true;
            for (int i = 1; i < frames.size(); i++) {
                Frame a = frames.get(i - 1), b = frames.get(i);
                double dt = Math.max(0, b.time - a.time);
                double move = a.pos.distanceTo(b.pos);
                double turn = Math.toDegrees(2 * Math.acos(Math.min(1, Math.abs(a.rotation.dot(b.rotation)))));
                worstMove = Math.max(worstMove, move);
                worstTurn = Math.max(worstTurn, turn);
                if (move > blocksPerTick * dt + .02 || turn > degreesPerTick * dt + 1) {
                    ok = false;
                    LOG.warn("[camera shot] {} jump at t={}: move {} turn {} in {} ticks", name, b.time, move, turn, dt);
                }
            }
            LOG.info("[camera shot] {}: {} frames, largest step {} blocks, {} degrees", name, frames.size(), worstMove, worstTurn);
            return ok && frames.size() > 20;
        }

        /** 镜头真正停在 {@code at} 上的时长，按刻。容差要很小：缓动收尾很慢，放宽到 0.01 格头尾会各多算一刻多。 */
        double timeAt(Vec3 at) {
            double first = -1, last = -1;
            for (Frame frame : frames) {
                if (frame.pos.distanceTo(at) > 1e-4) continue;
                if (first < 0) first = frame.time;
                last = frame.time;
            }
            return last - first;
        }
    }

    /** 一片草地，四周竖几根彩色柱子当参照；{@code ceiling} 大于 0 时在离地这么高处盖顶。 */
    private static void stage(ServerLevel level, int ceiling) {
        for (int x = -14; x <= 14; x++) for (int z = -14; z <= 14; z++) {
            level.setBlockAndUpdate(new BlockPos(x, GROUND, z), Blocks.GRASS_BLOCK.defaultBlockState());
            for (int y = 1; y <= 18; y++) level.setBlockAndUpdate(new BlockPos(x, GROUND + y, z), Blocks.AIR.defaultBlockState());
        }
        var pillars = new net.minecraft.world.level.block.Block[] {Blocks.RED_WOOL, Blocks.BLUE_WOOL, Blocks.YELLOW_WOOL, Blocks.LIME_WOOL};
        for (int i = 0; i < 4; i++) for (int y = 1; y <= 3; y++) {
            level.setBlockAndUpdate(new BlockPos(i % 2 == 0 ? -6 : 6, GROUND + y, i < 2 ? -6 : 9), pillars[i].defaultBlockState());
        }
        if (ceiling > 0) for (int x = -5; x <= 5; x++) for (int z = -5; z <= 7; z++) {
            level.setBlockAndUpdate(new BlockPos(x, GROUND + 1 + ceiling, z), Blocks.STONE_BRICKS.defaultBlockState());
        }
        level.setBlockAndUpdate(TARGET, CameraTestBlock.BLOCK.get().defaultBlockState());
    }

    protected void arrive(ScenarioBuilder s, int ceiling, CameraType view) {
        s.setGameMode(GameType.SURVIVAL).runCommand("time set 6000").runCommand("weather clear").runCommand("difficulty peaceful")
                .step("设置视口", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.hideGui = false;
                    mc.player.getInventory().setSelectedSlot(0);
                    mc.options.setCameraType(view);
                    mc.options.fov().set(70);
                    var w = mc.getWindow(); w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
                })
                .server("搭场地", ctx -> {
                    stage(ctx.server().overworld(), ceiling);
                    DimensionTravel.teleport(ctx.player(), ctx.server().overworld(), FEET, 0, START_PITCH);
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
                })
                .waitUntil("到位", ctx -> {
                    var mc = Minecraft.getInstance();
                    return mc.player.position().distanceTo(FEET) < .01 && mc.player.onGround() && mc.level.getBlockState(TARGET).is(CameraTestBlock.BLOCK.get());
                })
                .ticks(10)
                .step("记下起点", ctx -> {
                    eye = Minecraft.getInstance().player.getEyePosition();
                    log.start();
                });
    }

    /** 像真的按住左键那样挖，服务端会校验挖掘进度；挖掉时运镜应当自己开始。 */
    protected void mine(ScenarioBuilder s) {
        s.step("开始挖", ctx -> {
            var mc = Minecraft.getInstance();
            mc.gameMode.startDestroyBlock(TARGET, Direction.NORTH);
            log.onTick = () -> mc.gameMode.continueDestroyBlock(TARGET, Direction.NORTH);
        }).waitUntil("挖掉后开始运镜", ctx -> CameraDirector.active()).step("停止挖", ctx -> log.onTick = () -> {});
    }

    protected static void atAge(ScenarioBuilder s, int age) {
        s.waitUntil("运镜第 " + age + " 刻", ctx -> CameraDirector.age() >= age);
    }

    protected static void finished(ScenarioBuilder s) {
        s.waitUntil("运镜结束", ctx -> !CameraDirector.active()).ticks(5);
    }

    private static Camera camera() {
        return Minecraft.getInstance().gameRenderer.getMainCamera();
    }

    /** 镜头是否正对着 {@code at}。 */
    protected static boolean facing(Vec3 at) {
        var f = camera().forwardVector();
        return at.subtract(camera().position()).normalize().dot(new Vec3(f.x(), f.y(), f.z())) > .9995;
    }

    /** 运镜结束后一切都还给玩家了。 */
    protected void restored(TestContext ctx, CameraType view) {
        var mc = Minecraft.getInstance();
        ctx.check("视角设置恢复", mc.options.getCameraType() == view);
        ctx.check("操作已交还", !CameraDirector.locked());
        if (view.isFirstPerson()) {
            ctx.check("镜头回到玩家眼睛", camera().position().distanceTo(mc.player.getEyePosition()) < .01);
            ctx.check("朝向回到玩家朝向", Math.abs(camera().xRot() - mc.player.getXRot()) < .01 && Math.abs(camera().yRot() - mc.player.getYRot()) < .01);
        }
    }

    protected void cleanup(ScenarioBuilder s) {
        s.teardown("恢复", ctx -> {
            var mc = Minecraft.getInstance();
            log.stop();
            mc.options.keyUp.setDown(false);
            mc.options.keyShift.setDown(false);
            mc.options.hideGui = false;
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        });
    }

    /** 测试方块的完整一遍：挖掉、升到头顶十格看它、停三秒、回来；中途按键和鼠标都不起作用，回来后恢复。 */
    @LDLRegisterClient(name = "camera_shot_block", group = "camera_shot", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Block extends CameraShotViews {
        @Override public void define(ScenarioBuilder s) {
            arrive(s, 0, CameraType.FIRST_PERSON);
            s.screenshot("before");
            mine(s);
            s.step("方块已消失", ctx -> ctx.check("方块被挖掉了", Minecraft.getInstance().level.getBlockState(TARGET).isAir()));
            for (int age : new int[] {6, 12, 18}) { atAge(s, age); s.screenshot("rise_" + age); }
            atAge(s, 30);
            s.screenshot("hold");
            s.step("检查机位", ctx -> {
                var mc = Minecraft.getInstance();
                ctx.check("机位在玩家头顶十格", camera().position().distanceTo(eye.add(0, 10, 0)) < .01);
                ctx.check("看向方块原来的位置", facing(TARGET.getCenter()));
                ctx.check("镜头离开时切到第三人称", !mc.options.getCameraType().isFirstPerson() && camera().isDetached());
                ctx.check("操作被锁住", CameraDirector.locked());
                ctx.check("默认没有遮幅", CameraDirector.bars() == 0);
                var turn =ClientHooks.getTurnPlayerValues(mc.options.sensitivity().get(), false);
                ctx.check("鼠标转向归零", Math.abs(turn.getMouseSensitivity() * .6f + .2f) < 1e-6);
                mc.options.keyUp.setDown(true);
            });
            atAge(s, 45);
            s.step("按住前进也不动", ctx -> {
                var mc = Minecraft.getInstance();
                ctx.check("玩家没有走", mc.player.position().distanceTo(FEET) < .01);
                ctx.check("移动输入是空的", mc.player.input.getMoveVector().lengthSquared() == 0);
                mc.options.keyUp.setDown(false);
            });
            for (int age : new int[] {88, 94, 100, 104}) { atAge(s, age); s.screenshot("back_" + age); }
            finished(s);
            s.screenshot("after");
            s.step("检查收尾", ctx -> {
                restored(ctx, CameraType.FIRST_PERSON);
                ctx.check("没有跳变", log.smooth("block", PEAK * 10 / 24, PEAK * 180 / 24));
                double held = log.timeAt(eye.add(0, 10, 0));
                LOG.info("[camera shot] held at the spot for {} ticks", held);
                ctx.check("在机位上停了三秒", held >= 58 && held <= 61);
                Minecraft.getInstance().options.keyUp.setDown(true);
            }).ticks(5).step("前进又能用了", ctx -> {
                var mc = Minecraft.getInstance();
                ctx.check("交还后能走", mc.player.input.getMoveVector().lengthSquared() > 0);
                mc.options.keyUp.setDown(false);
            });
            cleanup(s);
        }
    }

    /** 头顶四格有方块：机位停在方块下面。 */
    @LDLRegisterClient(name = "camera_shot_ceiling", group = "camera_shot", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Ceiling extends CameraShotViews {
        @Override public void define(ScenarioBuilder s) {
            arrive(s, 4, CameraType.FIRST_PERSON);
            mine(s);
            atAge(s, 30);
            s.screenshot("hold");
            s.step("检查机位", ctx -> {
                ctx.check("被上方的方块挡住", Math.abs(camera().position().y - (GROUND + 1 + 4 - .35)) < .01);
                ctx.check("仍然在玩家正上方", Math.abs(camera().position().x - eye.x) < .01 && Math.abs(camera().position().z - eye.z) < .01);
                ctx.check("看向方块原来的位置", facing(TARGET.getCenter()));
            });
            finished(s);
            s.step("检查收尾", ctx -> {
                restored(ctx, CameraType.FIRST_PERSON);
                ctx.check("没有跳变", log.smooth("ceiling", PEAK * 3 / 24, PEAK * 180 / 24));
            });
            cleanup(s);
        }
    }

    /** 两格高的隧道：镜头升不起来，就留在头部转向，不切第三人称，也不会把身体画到眼前。 */
    @LDLRegisterClient(name = "camera_shot_tunnel", group = "camera_shot", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Tunnel extends CameraShotViews {
        @Override public void define(ScenarioBuilder s) {
            arrive(s, 2, CameraType.FIRST_PERSON);
            mine(s);
            atAge(s, 30);
            s.screenshot("hold");
            s.step("检查视角", ctx -> {
                ctx.check("仍是第一人称", Minecraft.getInstance().options.getCameraType().isFirstPerson() && !camera().isDetached());
                ctx.check("看向方块原来的位置", facing(TARGET.getCenter()));
            });
            finished(s);
            s.step("检查收尾", ctx -> restored(ctx, CameraType.FIRST_PERSON));
            cleanup(s);
        }
    }

    /** 玩家本来就是第三人称：回到第三人称的位置，视角设置不变。 */
    @LDLRegisterClient(name = "camera_shot_third_person", group = "camera_shot", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class ThirdPerson extends CameraShotViews {
        @Override public void define(ScenarioBuilder s) {
            arrive(s, 0, CameraType.THIRD_PERSON_BACK);
            s.server("播一段", ctx -> CameraShots.play(ctx.player(), CameraShot.of(CameraPoint.world(eye.add(6, 8, 0)), CameraPoint.world(TARGET.getCenter())).timing(12, 20, 12)));
            s.waitUntil("开始", ctx -> CameraDirector.active());
            atAge(s, 20);
            s.screenshot("hold");
            s.step("检查机位", ctx -> ctx.check("机位正确", camera().position().distanceTo(eye.add(6, 8, 0)) < .01));
            finished(s);
            s.screenshot("after");
            s.step("检查收尾", ctx -> {
                restored(ctx, CameraType.THIRD_PERSON_BACK);
                ctx.check("回到玩家身后", Math.abs(camera().position().distanceTo(Minecraft.getInstance().player.getEyePosition()) - 4) < .1);
                ctx.check("没有跳变", log.smooth("third person", PEAK * 15 / 12, PEAK * 180 / 12));
            });
            cleanup(s);
        }
    }

    /** Boss 的用法：机位和视线都跟着移动的实体走；实体中途消失时停在最后的位置，不跳。 */
    @LDLRegisterClient(name = "camera_shot_follow", group = "camera_shot", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Follow extends CameraShotViews {
        private static final Vec3 OFFSET = new Vec3(0, 3, -7);
        private int stand;
        private double firstX;

        private ArmorStand body() {
            return (ArmorStand) Minecraft.getInstance().level.getEntity(stand);
        }

        @Override public void define(ScenarioBuilder s) {
            arrive(s, 0, CameraType.FIRST_PERSON);
            s.server("放一个会动的目标", ctx -> {
                ServerLevel level = ctx.server().overworld();
                ArmorStand e = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
                e.setNoGravity(true);
                e.snapTo(-4.5, GROUND + 1, 7.5, 180, 0);
                level.addFreshEntity(e);
                stand = e.getId();
                var server = ctx.server();
                log.onTick = () -> server.execute(() -> { if (!e.isRemoved()) e.setPos(e.getX() + .1, e.getY(), e.getZ()); });
                CameraShots.play(ctx.player(), CameraShot.of(CameraPoint.on(e, OFFSET), CameraPoint.eyes(e)).timing(12, 50, 12));
            });
            s.waitUntil("开始", ctx -> CameraDirector.active() && body() != null);
            atAge(s, 20);
            s.screenshot("hold_1");
            s.step("跟上目标", ctx -> {
                firstX = camera().position().x;
                ctx.check("机位跟着目标", camera().position().distanceTo(body().position().add(OFFSET)) < .3);
                ctx.check("视线对着目标", facing(body().getEyePosition()));
            });
            atAge(s, 40);
            s.screenshot("hold_2");
            s.step("目标走远后仍跟着", ctx -> {
                ctx.check("目标移动时机位也移动", camera().position().x - firstX > 1);
                ctx.check("机位跟着目标", camera().position().distanceTo(body().position().add(OFFSET)) < .3);
            });
            s.server("目标消失", ctx -> ctx.server().overworld().getEntity(stand).discard());
            atAge(s, 58);
            s.step("停在最后的位置", ctx -> ctx.check("目标不在了", body() == null));
            finished(s);
            s.step("检查收尾", ctx -> {
                restored(ctx, CameraType.FIRST_PERSON);
                ctx.check("没有跳变", log.smooth("follow", PEAK * 14 / 12, PEAK * 180 / 12));
            });
            cleanup(s);
        }
    }

    /** 上一段没播完就来了下一段：从当时的画面接过去，不跳回玩家。 */
    @LDLRegisterClient(name = "camera_shot_interrupt", group = "camera_shot", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Interrupt extends CameraShotViews {
        @Override public void define(ScenarioBuilder s) {
            arrive(s, 0, CameraType.FIRST_PERSON);
            Vec3[] spots = new Vec3[2];
            s.server("第一段", ctx -> {
                spots[0] = eye.add(0, 10, 0);
                spots[1] = eye.add(10, 6, 2);
                CameraShots.play(ctx.player(), CameraShot.of(CameraPoint.world(spots[0]), CameraPoint.world(TARGET.getCenter())).timing(12, 40, 12));
            });
            s.waitUntil("开始", ctx -> CameraDirector.active());
            atAge(s, 18);
            s.server("第二段打断", ctx -> CameraShots.play(ctx.player(), CameraShot.of(CameraPoint.world(spots[1]), CameraPoint.world(TARGET.getCenter())).timing(12, 16, 12)));
            s.waitUntil("第二段开始", ctx -> CameraDirector.age() < 18);
            atAge(s, 20);
            s.screenshot("second");
            s.step("到第二个机位", ctx -> ctx.check("机位正确", camera().position().distanceTo(spots[1]) < .01));
            finished(s);
            s.step("检查收尾", ctx -> {
                restored(ctx, CameraType.FIRST_PERSON);
                ctx.check("没有跳变", log.smooth("interrupt", PEAK * 13 / 12, PEAK * 180 / 12));
            });
            cleanup(s);
        }
    }

    /** 运镜中途玩家被传走：画面不动，回程的终点是玩家现在的位置。 */
    @LDLRegisterClient(name = "camera_shot_moved", group = "camera_shot", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Moved extends CameraShotViews {
        @Override public void define(ScenarioBuilder s) {
            arrive(s, 0, CameraType.FIRST_PERSON);
            s.server("播一段", ctx -> CameraShots.play(ctx.player(), CameraShot.of(CameraPoint.world(eye.add(0, 10, 0)), CameraPoint.world(TARGET.getCenter())).timing(12, 30, 12)));
            s.waitUntil("开始", ctx -> CameraDirector.active());
            atAge(s, 20);
            s.server("把玩家传走", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(), FEET.add(5, 0, -3), 90, 0));
            finished(s);
            s.screenshot("after");
            s.step("检查收尾", ctx -> {
                var mc = Minecraft.getInstance();
                restored(ctx, CameraType.FIRST_PERSON);
                ctx.check("玩家确实被传走了", mc.player.position().distanceTo(FEET.add(5, 0, -3)) < .01);
                ctx.check("没有跳变", log.smooth("moved", PEAK * 13 / 12, PEAK * 180 / 12));
            });
            cleanup(s);
        }
    }

    /** {@code keepControl}：边看边走，界面照常的另一种用法。 */
    @LDLRegisterClient(name = "camera_shot_keep_control", group = "camera_shot", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class KeepControl extends CameraShotViews {
        @Override public void define(ScenarioBuilder s) {
            arrive(s, 0, CameraType.FIRST_PERSON);
            s.server("播一段", ctx -> CameraShots.play(ctx.player(), CameraShot.of(CameraPoint.world(eye.add(0, 10, 0)), CameraPoint.world(TARGET.getCenter())).timing(12, 30, 12).keepControl().keepHud()));
            s.waitUntil("开始", ctx -> CameraDirector.active());
            s.step("往前走", ctx -> Minecraft.getInstance().options.keyUp.setDown(true));
            atAge(s, 20);
            s.screenshot("hold");
            s.step("能走", ctx -> {
                var mc = Minecraft.getInstance();
                ctx.check("没有锁操作", !CameraDirector.locked());
                ctx.check("玩家走动了", mc.player.position().distanceTo(FEET) > .1);
                mc.options.keyUp.setDown(false);
            });
            finished(s);
            s.step("检查收尾", ctx -> restored(ctx, CameraType.FIRST_PERSON));
            cleanup(s);
        }
    }

    /** 潜行时挖掉测试方块：遮幅从屏幕外滑入，停留时不动，回程时滑出。 */
    @LDLRegisterClient(name = "camera_shot_letterbox", group = "camera_shot", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Letterbox extends CameraShotViews {
        @Override public void define(ScenarioBuilder s) {
            arrive(s, 0, CameraType.FIRST_PERSON);
            s.step("按住潜行", ctx -> Minecraft.getInstance().options.keyShift.setDown(true));
            s.waitUntil("蹲下了", ctx -> Minecraft.getInstance().player.isShiftKeyDown()).ticks(5);
            mine(s);
            for (int age : new int[] {6, 12, 18}) { atAge(s, age); s.screenshot("in_" + age); }
            atAge(s, 40);
            s.screenshot("hold");
            s.step("遮幅完全进来", ctx -> ctx.check("停留时遮幅完全进来", CameraDirector.bars() == 1));
            for (int age : new int[] {90, 96, 102}) { atAge(s, age); s.screenshot("out_" + age); }
            finished(s);
            s.screenshot("after");
            s.step("检查收尾", ctx -> {
                restored(ctx, CameraType.FIRST_PERSON);
                ctx.check("结束后遮幅在屏幕外", CameraDirector.bars() == 0);
                ctx.check("遮幅先滑入再滑出，中途不回头", log.barsInThenOut());
                ctx.check("遮幅没有跳变", log.barsSmooth("letterbox", PEAK / 24));
                ctx.check("镜头运动不受遮幅影响", log.smooth("letterbox", PEAK * 10 / 24, PEAK * 180 / 24));
            });
            cleanup(s);
        }
    }

    /** 带遮幅和不带遮幅的镜头互相打断：遮幅从当时的位置接着走，不会先弹回屏幕外。 */
    @LDLRegisterClient(name = "camera_shot_letterbox_interrupt", group = "camera_shot", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class LetterboxInterrupt extends CameraShotViews {
        private CameraShot shot(int offset) {
            return CameraShot.of(CameraPoint.world(eye.add(offset, 8, 0)), CameraPoint.world(TARGET.getCenter())).timing(12, 16, 12);
        }

        @Override public void define(ScenarioBuilder s) {
            arrive(s, 0, CameraType.FIRST_PERSON);
            s.server("带遮幅的一段", ctx -> CameraShots.play(ctx.player(), shot(0).letterbox()));
            s.waitUntil("开始", ctx -> CameraDirector.active());
            atAge(s, 16);
            s.server("被不带遮幅的打断", ctx -> CameraShots.play(ctx.player(), shot(4)));
            s.waitUntil("第二段开始", ctx -> CameraDirector.age() < 16);
            atAge(s, 20);
            s.step("遮幅退出去了", ctx -> ctx.check("第二段不带遮幅", CameraDirector.bars() == 0));
            s.server("又被带遮幅的打断", ctx -> CameraShots.play(ctx.player(), shot(-4).letterbox()));
            s.waitUntil("第三段开始", ctx -> CameraDirector.age() < 20);
            atAge(s, 20);
            s.step("遮幅又进来了", ctx -> ctx.check("第三段带遮幅", CameraDirector.bars() == 1));
            finished(s);
            s.step("检查收尾", ctx -> {
                restored(ctx, CameraType.FIRST_PERSON);
                ctx.check("结束后遮幅在屏幕外", CameraDirector.bars() == 0);
                ctx.check("遮幅没有跳变", log.barsSmooth("letterbox interrupt", PEAK / 12));
            });
            cleanup(s);
        }
    }

    /** 保留界面时物品栏压在遮幅上面；按 F1 藏掉界面后遮幅还在。 */
    @LDLRegisterClient(name = "camera_shot_letterbox_hud", group = "camera_shot", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class LetterboxHud extends CameraShotViews {
        @Override public void define(ScenarioBuilder s) {
            arrive(s, 0, CameraType.FIRST_PERSON);
            s.server("播一段", ctx -> CameraShots.play(ctx.player(), CameraShot.of(CameraPoint.world(eye.add(0, 10, 0)), CameraPoint.world(TARGET.getCenter())).timing(12, 40, 12).keepHud().letterbox()));
            s.waitUntil("开始", ctx -> CameraDirector.active());
            atAge(s, 20);
            s.screenshot("keep_hud");
            s.step("按 F1", ctx -> Minecraft.getInstance().options.hideGui = true);
            atAge(s, 30);
            s.screenshot("f1");
            s.step("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
            finished(s);
            s.step("检查收尾", ctx -> restored(ctx, CameraType.FIRST_PERSON));
            cleanup(s);
        }
    }

    /** 运镜时玩家死了：提前往回走，死亡画面和复活都不受影响。 */
    @LDLRegisterClient(name = "camera_shot_death", group = "camera_shot", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Death extends CameraShotViews {
        @Override public void define(ScenarioBuilder s) {
            arrive(s, 0, CameraType.FIRST_PERSON);
            s.server("播一段很长的", ctx -> CameraShots.play(ctx.player(), CameraShot.of(CameraPoint.world(eye.add(0, 10, 0)), CameraPoint.world(TARGET.getCenter())).timing(12, 600, 12).letterbox()));
            s.waitUntil("开始", ctx -> CameraDirector.active());
            atAge(s, 20);
            s.server("玩家死亡", ctx -> ctx.player().kill(ctx.server().overworld()));
            finished(s);
            s.step("检查收尾", ctx -> {
                ctx.check("没有等满就结束了", !CameraDirector.active());
                ctx.check("视角设置恢复", Minecraft.getInstance().options.getCameraType() == CameraType.FIRST_PERSON);
                ctx.check("操作已交还", !CameraDirector.locked());
                ctx.check("提前返回时没有跳变", log.smooth("death", PEAK * 11 / 12, PEAK * 180 / 12));
                ctx.check("提前返回时遮幅也滑出去了", log.barsSmooth("death", PEAK / 12) && CameraDirector.bars() == 0);
                Minecraft.getInstance().player.respawn();
            });
            s.waitUntil("复活", ctx -> Minecraft.getInstance().player.isAlive()).step("关掉死亡画面", ctx -> Minecraft.getInstance().setScreen(null));
            cleanup(s);
        }
    }
}
