package dev.logos.dimension.client.music;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.music.InstrumentBlock;
import dev.logos.dimension.music.InstrumentBlockEntity;
import dev.logos.dimension.music.InstrumentKind;
import dev.logos.dimension.music.Instruments;
import dev.logos.dimension.music.MusicPayloads;
import dev.logos.dimension.music.MusicStandBlock;
import java.util.List;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 手持乐器与乐谱架的客户端场景：三个乐谱架（靠着小提琴、横放长笛、搁着小号垂王室旗）和一个空架子；
 * 拿起每件乐器右键演奏（演奏条在底部、世界照常显示），按三个键核对声音在响、跟着自己；第一人称拍一张，
 * 再从隐形盔甲架的眼睛里在斜前方左右、侧面各拍一张姿势；关掉演奏条后放下；最后模拟收到别人手上小提琴的一批音，
 * 核对声音跟着那个人。
 */
public abstract class HandheldViews implements UIScenario {
    protected static final int GROUND = 240;
    protected static final BlockPos ORIGIN = new BlockPos(180, GROUND, 70);
    private static int other = -1;
    private static int camera = -1;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("instrument").defaultTimeoutMs(180000).scenarioTimeoutMs(300000);
    }

    private static void stand(ServerLevel level, BlockPos pos, MusicStandBlock.Holds holds, Item item) {
        BlockState state = Instruments.MUSIC_STAND.get().defaultBlockState().setValue(InstrumentBlock.FACING, Direction.SOUTH)
            .setValue(MusicStandBlock.HOLDS, holds);
        level.setBlockAndUpdate(pos, state);
        if (item != null && level.getBlockEntity(pos) instanceof InstrumentBlockEntity be) be.setInstrument(new ItemStack(item));
        if (item != null) level.setBlockAndUpdate(pos, state);
    }

    protected ScenarioBuilder stage(ScenarioBuilder s, double eyeX, double eyeY, double eyeZ, float yaw, float pitch, boolean hideGui) {
        return s.setGameMode(GameType.CREATIVE)
            .runCommand("difficulty peaceful")
            .runCommand("time set noon")
            .step("camera", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.hideGui = hideGui;
                mc.options.renderDistance().set(8);
                mc.options.fov().set(70);
                mc.options.broadcastOptions();
            })
            .server("stage the stands", ctx -> {
                ServerLevel level = ctx.server().overworld();
                for (int x = -8; x <= 8; x++) {
                    for (int z = -6; z <= 8; z++) {
                        BlockPos p = ORIGIN.offset(x, 0, z);
                        for (int y = 1; y <= 5; y++) {
                            level.setBlock(p.above(y), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
                        }
                        level.setBlock(p, ((x + z & 1) == 0 ? Blocks.POLISHED_DIORITE : Blocks.SMOOTH_QUARTZ).defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
                level.getEntitiesOfClass(ItemEntity.class, new AABB(ORIGIN).inflate(16)).forEach(ItemEntity::discard);
                stand(level, ORIGIN.offset(-3, 1, 0), MusicStandBlock.Holds.VIOLIN, Instruments.VIOLIN.get());
                stand(level, ORIGIN.offset(-1, 1, 0), MusicStandBlock.Holds.FLUTE, Instruments.FLUTE.get());
                stand(level, ORIGIN.offset(1, 1, 0), MusicStandBlock.Holds.TRUMPET, Instruments.TRUMPET.get());
                stand(level, ORIGIN.offset(3, 1, 0), MusicStandBlock.Holds.NONE, null);
                DimensionTravel.teleport(ctx.player(), level, new Vec3(ORIGIN.getX() + eyeX, GROUND + eyeY, ORIGIN.getZ() + eyeZ), yaw, pitch);
            })
            .waitUntil("stands arrive", ctx -> {
                var mc = Minecraft.getInstance();
                return mc.level != null && mc.level.getBlockState(ORIGIN.offset(1, 1, 0)).is(Instruments.MUSIC_STAND.get())
                    && mc.level.getBlockState(ORIGIN.offset(1, 1, 0)).getValue(MusicStandBlock.HOLDS) == MusicStandBlock.Holds.TRUMPET;
            });
    }

    @LDLRegisterClient(name = "music_stand_views", group = "instrument", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Stands extends HandheldViews {
        @Override
        public void define(ScenarioBuilder s) {
            stage(s, 0.5, 2.3, 3.6, 180f, 24f, true)
                .ticks(60)
                .check("the stands know their instruments", ctx -> MusicClient.kindAt(ORIGIN.offset(-3, 1, 0)) == InstrumentKind.VIOLIN
                    && MusicClient.kindAt(ORIGIN.offset(3, 1, 0)) == null)
                .screenshot("music_stand_views")
                .server("side view", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(),
                    new Vec3(ORIGIN.getX() + 4.5, GROUND + 2.6, ORIGIN.getZ() + 3.0), 130f, 22f))
                .ticks(40)
                .screenshot("music_stand_views_side")
                .server("close to the violin", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(),
                    new Vec3(ORIGIN.getX() - 2.5, GROUND + 2.0, ORIGIN.getZ() + 2.0), 180f, 20f))
                .ticks(40)
                .screenshot("music_stand_views_violin")
                .teardown("restore HUD", ctx -> Minecraft.getInstance().options.hideGui = false);
        }
    }

    @LDLRegisterClient(name = "handheld_play_ui", group = "instrument", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Play extends HandheldViews {
        /** 从玩家身边看演奏的样子（dx 正是玩家左侧、dz 正是前方）：镜头放在隐形盔甲架的眼睛上，演奏条照常开着。 */
        private static ScenarioBuilder view(ScenarioBuilder s, String shot, double dx, double dz) {
            return s.server("camera for " + shot, ctx -> {
                    var cam = ctx.server().overworld().getEntity(camera);
                    Vec3 me = ctx.player().position();
                    Vec3 eye = me.add(dx, 1.8, dz);
                    Vec3 look = me.add(0, 1.3, 0).subtract(eye);
                    float yaw = (float) (Mth.atan2(look.z, look.x) * Mth.RAD_TO_DEG) - 90;
                    float pitch = (float) -(Mth.atan2(look.y, look.horizontalDistance()) * Mth.RAD_TO_DEG);
                    cam.snapTo(eye.x, eye.y - cam.getEyeHeight(), eye.z, yaw, pitch);
                    cam.setYHeadRot(yaw);
                })
                .ticks(5)
                .step("look from the camera", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.hideGui = true;
                    mc.setCameraEntity(mc.level.getEntity(camera));
                })
                .ticks(2)
                .screenshot(shot)
                .step("back to my eyes", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.setCameraEntity(mc.player);
                    mc.options.hideGui = false;
                });
        }

        private static ScenarioBuilder hold(ScenarioBuilder s, String name, Item item) {
            s = s.server("hold the " + name, ctx -> {
                    ctx.player().getInventory().setItem(0, new ItemStack(item));
                    ctx.player().getInventory().setSelectedSlot(0);
                })
                .ticks(5)
                .step("raise the " + name, ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                })
                .waitUntil(name + " play bar opens", ctx -> Minecraft.getInstance().screen instanceof HandheldScreen)
                .waitUntilServer(name + " is in use on the server", ctx -> ctx.player().isUsingItem())
                .keyDown('Z').keyDown('C').keyDown('B')
                .ticks(6)
                .check(name + " sounds and follows the player", ctx -> {
                    var me = Minecraft.getInstance().player;
                    return VoiceBank.sounding(MusicClient.heldKey(me.getId())) >= 3 && MusicClient.recentlyPlayed(me.getId());
                })
                .screenshot("handheld_play_" + name);
            s = view(s, "handheld_play_" + name + "_left", 1.5, 1.9);
            s = view(s, "handheld_play_" + name + "_right", -1.5, 1.9);
            s = view(s, "handheld_play_" + name + "_side", 2.3, 0.2);
            return s.keyUp('Z').keyUp('C').keyUp('B')
                .step("put the " + name + " away", ctx -> Minecraft.getInstance().setScreen(null))
                .ticks(6)
                .check(name + " is put down", ctx -> !Minecraft.getInstance().player.isUsingItem());
        }

        @Override
        public void define(ScenarioBuilder s) {
            ScenarioBuilder b = stage(s, 0.5, 1.0, 4.0, 0f, 5f, false)
                .server("an invisible camera", ctx -> {
                    var cam = EntityType.ARMOR_STAND.create(ctx.server().overworld(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                    cam.setInvisible(true);
                    cam.setNoGravity(true);
                    cam.setPos(ORIGIN.getX() + 2.5, GROUND + 1, ORIGIN.getZ() + 7);
                    ctx.server().overworld().addFreshEntity(cam);
                    camera = cam.getId();
                })
                .waitUntil("the camera arrives", ctx -> Minecraft.getInstance().level.getEntity(camera) != null);
            b = hold(b, "violin", Instruments.VIOLIN.get());
            b = hold(b, "flute", Instruments.FLUTE.get());
            b = hold(b, "trumpet", Instruments.TRUMPET.get());
            b.server("another player with a violin", ctx -> {
                    var stand = EntityType.ARMOR_STAND.create(ctx.server().overworld(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                    stand.setPos(ORIGIN.getX() - 2, GROUND + 1, ORIGIN.getZ() + 3);
                    ctx.server().overworld().addFreshEntity(stand);
                    other = stand.getId();
                })
                .waitUntil("the other arrives", ctx -> Minecraft.getInstance().level.getEntity(other) != null)
                .step("their notes arrive", ctx -> MusicClient.heardHeld(new MusicPayloads.HeldNotesOut(other, InstrumentKind.VIOLIN.ordinal(), false,
                    List.of(new MusicPayloads.LiveNote(0, 67, 100), new MusicPayloads.LiveNote(40, 71, 90)))))
                .ticks(8)
                .check("their violin sounds at their position", ctx -> VoiceBank.sounding(MusicClient.heldKey(other)) >= 2)
                .step("they put it away", ctx -> MusicClient.heardHeld(new MusicPayloads.HeldNotesOut(other, InstrumentKind.VIOLIN.ordinal(), true, List.of())))
                .ticks(10)
                .check("their notes stop", ctx -> VoiceBank.sounding(MusicClient.heldKey(other)) == 0)
                .teardown("my own eyes again", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.setScreen(null);
                    if (mc.player != null) mc.setCameraEntity(mc.player);
                    mc.options.hideGui = false;
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                });
        }
    }
}
