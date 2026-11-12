package dev.logos.dimension.client.music;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.music.BenchSeat;
import dev.logos.dimension.music.Benches;
import dev.logos.dimension.music.InstrumentBlock;
import dev.logos.dimension.music.InstrumentBlockEntity;
import dev.logos.dimension.music.InstrumentKind;
import dev.logos.dimension.music.Instruments;
import dev.logos.dimension.music.MusicStandBlock;
import dev.logos.dimension.music.StarbloomBenchBlock;
import dev.logos.dimension.music.orchestra.Orchestra;
import dev.logos.dimension.music.orchestra.PodiumBlockEntity;
import dev.logos.dimension.npc.NpcEntities;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 生物加入合奏的客户端场景（docs/ORCHESTRA.md 第 14 节），在维护者的灵猫大音乐厅里（{@code tools/concert_hall.py --full-only}
 * 生成的 build/concert_hall/concert_hall.nbt；没有这个文件时场景失败并说明怎么生成）：
 * 乐谱架后的琴凳上坐着拿小提琴、中提琴、长笛、双簧管、巴松管、小号的僵尸、骷髅、尸壳、流髑和森林精灵；
 * 钢琴、钢片琴、竖琴、管风琴前坐着森林精灵，大键琴前坐着神狐（同一副骨架）。核对琴凳演奏什么、谁在弹什么、管风琴连上了全部音管；
 * 指挥台开始后琴凳上的乐器出声；每一处拍特写。最后玩家自己坐到空琴凳上拿着小提琴、坐到管风琴前让演奏台放纸带，各拍一张。
 */
public abstract class HallViews implements UIScenario {
    protected static final int GROUND = 236;
    /** 音乐厅坐标 (0, 0, 0) 在世界里的位置（地基最底层 y = -4 落在 GROUND）。 */
    protected static final BlockPos HALL = new BlockPos(600, GROUND + 4, 600);
    private static int camera = -1;
    private static final List<Integer> MUSICIANS = new ArrayList<>();
    private static int pianist = -1, violinist = -1, organist = -1, cellist = -1, bassist = -1, glockenist = -1, timpanist = -1;
    static final BlockPos TIMPANI_BENCH = at(-4, 6, -20);

    static BlockPos at(int x, int y, int z) {
        return HALL.offset(x, y, z);
    }

    static Vec3 point(double x, double y, double z) {
        return new Vec3(HALL.getX() + x, HALL.getY() + y, HALL.getZ() + z);
    }

    static final BlockPos PODIUM = at(-1, 3, -5);
    static final BlockPos CONSOLE = at(0, 6, -20);
    static final BlockPos PIANO_BENCH = at(-5, 3, -7), VIOLIN_BENCH = at(-11, 3, -8), FREE_VIOLIN = at(-13, 3, -8);
    static final BlockPos ORGAN_BENCH = at(0, 6, -19);

    /** 仓库里生成的音乐厅蓝图：从游戏目录往上找 build/concert_hall/concert_hall.nbt。 */
    static Path blueprint() {
        for (Path p = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath(); p != null; p = p.getParent()) {
            Path nbt = p.resolve("build/concert_hall/concert_hall.nbt");
            if (Files.isRegularFile(nbt)) return nbt;
        }
        throw new IllegalStateException("先运行 uv run --no-project --with pillow --with numpy python tools/concert_hall.py --full-only build/concert_hall/concert_hall.nbt");
    }

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("instrument").defaultTimeoutMs(240000).scenarioTimeoutMs(600000);
    }

    /** 让一个生物坐到琴凳上，拿着这件乐器（没有就空着手）。不走动、不出声。 */
    static Mob seat(ServerLevel level, EntityType<? extends Mob> type, BlockPos bench, @Nullable Item instrument, Player giver) {
        Mob mob = type.create(level, EntitySpawnReason.COMMAND);
        if (mob == null) throw new IllegalStateException("cannot create " + type);
        return seat(level, mob, bench, instrument, giver);
    }

    static Mob seat(ServerLevel level, Mob mob, BlockPos bench, @Nullable Item instrument, Player giver) {
        EntityType<?> type = mob.getType();
        mob.snapTo(bench.getX() + 0.5, bench.getY(), bench.getZ() + 0.5, 0, 0);
        mob.setNoAi(true);
        mob.setSilent(true);
        level.addFreshEntity(mob);
        if (!StarbloomBenchBlock.sit(level, bench, mob)) throw new IllegalStateException("could not seat " + type + " at " + bench);
        if (instrument != null) Benches.give(mob, new ItemStack(instrument), giver);
        StarbloomBenchBlock.refresh(level, bench);
        MUSICIANS.add(mob.getId());
        return mob;
    }

    /** 生物到 main 处乐器的工位上（同拴绳带过去）。 */
    static Mob post(ServerLevel level, EntityType<? extends Mob> type, BlockPos main) {
        Mob mob = type.create(level, EntitySpawnReason.COMMAND);
        if (mob == null) throw new IllegalStateException("cannot create " + type);
        return post(level, mob, main);
    }

    static Mob post(ServerLevel level, Mob mob, BlockPos main) {
        EntityType<?> type = mob.getType();
        mob.snapTo(main.getX() + 0.5, main.getY(), main.getZ() - 1.5, 0, 0);
        mob.setNoAi(true);
        mob.setSilent(true);
        level.addFreshEntity(mob);
        if (!dev.logos.dimension.music.InstrumentPosts.take(level, main, mob)) throw new IllegalStateException("could not post " + type + " at " + main);
        MUSICIANS.add(mob.getId());
        return mob;
    }

    static void placeHall(ServerLevel level) throws IOException {
        Path nbt = blueprint();
        JsonObject manifest = JsonParser.parseString(Files.readString(nbt.resolveSibling("concert_hall.json"))).getAsJsonObject();
        var offset = manifest.getAsJsonArray("offset");
        BlockPos corner = at(offset.get(0).getAsInt(), offset.get(1).getAsInt(), offset.get(2).getAsInt());
        CompoundTag tag = NbtIo.readCompressed(nbt, NbtAccounter.unlimitedHeap());
        StructureTemplate template = new StructureTemplate();
        template.load(level.holderLookup(Registries.BLOCK), tag);
        template.placeInWorld(level, corner, corner, new StructurePlaceSettings(), level.getRandom(), Block.UPDATE_CLIENTS);
    }

    protected ScenarioBuilder stage(ScenarioBuilder s) {
        return s.setGameMode(GameType.CREATIVE)
            .runCommand("difficulty easy")
            .runCommand("time set noon")
            .runCommand("tick rate 20")
            .step("camera", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.hideGui = true;
                mc.options.renderDistance().set(10);
                mc.options.fov().set(70);
                mc.options.broadcastOptions();
            })
            .server("build the concert hall and seat the orchestra", ctx -> {
                ServerLevel level = ctx.server().overworld();
                level.getEntitiesOfClass(Entity.class, new AABB(HALL).inflate(48), e -> !(e instanceof Player)).forEach(Entity::discard);
                try {
                    placeHall(level);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                MUSICIANS.clear();
                var me = ctx.player();
                Item violin = Instruments.VIOLIN.get(), viola = Instruments.VIOLA.get(), flute = Instruments.FLUTE.get();
                Item oboe = Instruments.OBOE.get(), bassoon = Instruments.BASSOON.get(), trumpet = Instruments.TRUMPET.get();
                var elf = NpcEntities.FOREST_ELF.get();
                // 弦乐：第一小提琴、第二小提琴、中提琴
                violinist = seat(level, EntityType.ZOMBIE, VIOLIN_BENCH, violin, me).getId();
                seat(level, elf, at(-9, 3, -8), violin, me);
                seat(level, EntityType.SKELETON, at(-7, 3, -8), violin, me);
                seat(level, EntityType.HUSK, at(-8, 3, -10), violin, me);
                seat(level, elf, at(-10, 3, -10), violin, me);
                seat(level, EntityType.ZOMBIE, at(-9, 3, -12), violin, me);
                seat(level, EntityType.STRAY, at(-7, 3, -12), violin, me);
                seat(level, elf, at(0, 3, -9), viola, me);
                seat(level, EntityType.ZOMBIE, at(2, 3, -9), viola, me);
                seat(level, elf, at(1, 3, -12), viola, me);
                // 木管、铜管
                seat(level, EntityType.SKELETON, at(-3, 4, -14), flute, me);
                seat(level, elf, at(-1, 4, -14), flute, me);
                seat(level, elf, at(2, 4, -14), oboe, me);
                seat(level, EntityType.HUSK, at(4, 4, -14), oboe, me);
                seat(level, EntityType.ZOMBIE, at(-3, 5, -17), bassoon, me);
                seat(level, elf, at(-1, 5, -17), bassoon, me);
                seat(level, elf, at(2, 5, -17), trumpet, me);
                seat(level, EntityType.SKELETON, at(4, 5, -17), trumpet, me);
                // 键盘、竖琴：坐在关联了乐器的琴凳上，手里拿什么都不算
                pianist = seat(level, elf, PIANO_BENCH, null, me).getId();
                seat(level, NpcEntities.DIVINE_FOX.get(), at(-4, 3, -9), null, me);
                seat(level, elf, at(-8, 4, -14), null, me);
                seat(level, elf, at(-10, 4, -14), null, me);
                organist = seat(level, elf, ORGAN_BENCH, violin, me).getId();
                // 工位：大提琴、低音提琴琴后，钟琴、管钟前面；定音鼓后的双人琴凳
                cellist = post(level, EntityType.ZOMBIE, at(5, 3, -7)).getId();
                post(level, elf, at(7, 3, -7));
                post(level, EntityType.SKELETON, at(6, 3, -10));
                bassist = post(level, elf, at(12, 3, -8)).getId();
                post(level, EntityType.HUSK, at(12, 3, -11));
                glockenist = post(level, EntityType.SKELETON, at(-6, 4, -13)).getId();
                post(level, elf, at(5, 6, -19));
                timpanist = seat(level, elf, TIMPANI_BENCH, null, me).getId();
                seat(level, EntityType.ZOMBIE, at(-8, 6, -20), null, me);
                var cam = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
                cam.setInvisible(true);
                cam.setNoGravity(true);
                cam.setPos(at(0, 9, 8).getX(), at(0, 9, 8).getY(), at(0, 9, 8).getZ());
                level.addFreshEntity(cam);
                camera = cam.getId();
                // 指挥台在 (-1, 3, -5) 朝北，侧块在东；指挥站在两格交界
                var inv = me.getInventory();
                inv.clearContent();
                ItemStack baton = new ItemStack(Orchestra.BATON.get());
                baton.set(Orchestra.BATON_PODIUM.get(), GlobalPos.of(level.dimension(), PODIUM));
                inv.setItem(0, baton);
                inv.setSelectedSlot(0);
                DimensionTravel.teleport(me, level, new Vec3(PODIUM.getX() + 1.0, PODIUM.getY() + 0.5, PODIUM.getZ() + 0.5), 180f, 15f);
            })
            .waitUntil("the hall and its musicians arrive", ctx -> {
                var mc = Minecraft.getInstance();
                if (mc.level == null || !(mc.level.getBlockEntity(PODIUM) instanceof PodiumBlockEntity be) || be.members().size() != 32) return false;
                Entity v = mc.level.getEntity(violinist), o = mc.level.getEntity(organist);
                return v != null && v.getVehicle() instanceof BenchSeat && o != null && o.getVehicle() instanceof BenchSeat
                    && mc.level.getEntity(camera) != null;
            });
    }

    /** 从隐形盔甲架的眼睛里看 target（音乐厅坐标），拍一张。 */
    protected static ScenarioBuilder shot(ScenarioBuilder s, String name, Vec3 eye, Vec3 target) {
        return s.server("camera for " + name, ctx -> {
                var cam = ctx.server().overworld().getEntity(camera);
                Vec3 look = target.subtract(eye);
                float yaw = (float) (Mth.atan2(look.z, look.x) * Mth.RAD_TO_DEG) - 90;
                float pitch = (float) -(Mth.atan2(look.y, look.horizontalDistance()) * Mth.RAD_TO_DEG);
                cam.snapTo(eye.x, eye.y - cam.getEyeHeight(), eye.z, yaw, pitch);
                cam.setYHeadRot(yaw);
            })
            .ticks(4)
            .step("look from the camera", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.hideGui = true;
                mc.setCameraEntity(mc.level.getEntity(camera));
            })
            .ticks(3)
            .screenshot(name)
            .step("back to my eyes", ctx -> Minecraft.getInstance().setCameraEntity(Minecraft.getInstance().player));
    }

    /**
     * 从演奏者身边拍一张：front 往他面朝的方向、right 往他右手边、up 往上（格），看着他胸口前一点。
     * 演奏者朝哪边按他骑着的座位算（座位朝向就是演奏的方向）。
     */
    protected static ScenarioBuilder shotOf(ScenarioBuilder s, String name, java.util.function.IntSupplier id, double right, double front, double up) {
        return s.server("camera for " + name, ctx -> {
                var level = ctx.server().overworld();
                Entity who = level.getEntity(id.getAsInt());
                if (who == null) throw new IllegalStateException("no musician for " + name);
                float facing = who.getVehicle() != null ? who.getVehicle().getYRot() : who.getYRot();
                Vec3 fwd = Vec3.directionFromRotation(0, facing), side = new Vec3(-fwd.z, 0, fwd.x);
                Vec3 target = who.position().add(0, 0.75, 0).add(fwd.scale(0.3));
                Vec3 eye = who.position().add(fwd.scale(front)).add(side.scale(right)).add(0, up, 0);
                Vec3 look = target.subtract(eye);
                var cam = level.getEntity(camera);
                float yaw = (float) (Mth.atan2(look.z, look.x) * Mth.RAD_TO_DEG) - 90;
                float pitch = (float) -(Mth.atan2(look.y, look.horizontalDistance()) * Mth.RAD_TO_DEG);
                cam.snapTo(eye.x, eye.y - cam.getEyeHeight(), eye.z, yaw, pitch);
                cam.setYHeadRot(yaw);
            })
            .ticks(4)
            .step("look from the camera", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.hideGui = true;
                mc.options.gamma().set(1.0);
                mc.setCameraEntity(mc.level.getEntity(camera));
            })
            .ticks(3)
            .screenshot(name)
            .step("back to my eyes", ctx -> Minecraft.getInstance().setCameraEntity(Minecraft.getInstance().player));
    }

    static MusicianPoses.@Nullable Musician musician(int id) {
        Entity e = Minecraft.getInstance().level.getEntity(id);
        return e instanceof net.minecraft.world.entity.LivingEntity l ? MusicianPoses.of(l) : null;
    }

    @LDLRegisterClient(name = "orchestra_hall_ui", group = "instrument", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Hall extends HallViews {
        @Override
        public void define(ScenarioBuilder s) {
            ScenarioBuilder b = stage(s)
                .check("the violin bench plays the violin", ctx ->
                    Minecraft.getInstance().level.getBlockState(VIOLIN_BENCH).getValue(StarbloomBenchBlock.PLAYS) == MusicStandBlock.Holds.VIOLIN)
                .check("the zombie plays the violin it holds", ctx -> {
                    var m = musician(violinist);
                    return m != null && m.mode() == MusicianPoses.Mode.HANDHELD && m.kind() == InstrumentKind.VIOLIN;
                })
                .check("the zombie faces the music stand in front of its bench", ctx -> {
                    Entity v = Minecraft.getInstance().level.getEntity(violinist);
                    if (!(v instanceof net.minecraft.world.entity.LivingEntity l)) return false;
                    Vec3 toStand = Vec3.atBottomCenterOf(VIOLIN_BENCH.south()).subtract(l.position()).normalize();
                    return Vec3.directionFromRotation(0, l.yBodyRot).dot(toStand) > 0.9;
                })
                .check("the elf at the piano bench is the pianist", ctx -> {
                    var m = musician(pianist);
                    return m != null && m.mode() == MusicianPoses.Mode.KEYS && m.kind() == InstrumentKind.GRAND_PIANO;
                })
                .check("the pianist sits where the duet bench's two cells meet, moved up to the keys", ctx -> {
                    Entity e = Minecraft.getInstance().level.getEntity(pianist);
                    return e != null && Math.abs(e.getZ() - (PIANO_BENCH.getZ() + 1)) < 1e-3
                        && Math.abs(e.getX() - (PIANO_BENCH.getX() + 0.5 + StarbloomBenchBlock.LINKED_FORWARD / 16)) < 1e-3;
                })
                .check("the zombie at the cello bows it, the elf at the double bass stands", ctx -> {
                    var c = musician(cellist);
                    var bass = musician(bassist);
                    return c != null && c.mode() == MusicianPoses.Mode.BOWED && c.kind() == InstrumentKind.CELLO && !c.standing()
                        && bass != null && bass.mode() == MusicianPoses.Mode.BOWED && bass.kind() == InstrumentKind.DOUBLE_BASS && bass.standing();
                })
                .check("the skeleton at the glockenspiel holds two mallets", ctx -> {
                    var g = musician(glockenist);
                    return g != null && g.mode() == MusicianPoses.Mode.MALLETS && g.left() != null && g.right() != null && g.standing();
                })
                .check("the timpanist's duet bench reaches two drums, one per hand", ctx -> {
                    var drums = StarbloomBenchBlock.linkedAll(Minecraft.getInstance().level, TIMPANI_BENCH);
                    var t = musician(timpanist);
                    return drums.size() == 2 && drums.get(0) != null && drums.get(1) != null && t != null
                        && t.mode() == MusicianPoses.Mode.MALLETS && t.kind() == InstrumentKind.TIMPANI && t.left().x < t.right().x;
                })
                .check("the organist holds a violin but plays the organ", ctx -> {
                    var m = musician(organist);
                    return m != null && m.mode() == MusicianPoses.Mode.KEYS && m.kind() == InstrumentKind.PIPE_ORGAN
                        && Minecraft.getInstance().level.getBlockState(ORGAN_BENCH).getValue(StarbloomBenchBlock.PLAYS) == MusicStandBlock.Holds.NONE;
                })
                .check("the hall organ reaches every pipe", ctx -> {
                    InstrumentBlockEntity console = InstrumentBlock.entityAt(Minecraft.getInstance().level, CONSOLE);
                    var organ = console == null ? null : console.organ();
                    return organ != null && organ.chests() == 44 && organ.pipes() == 206;
                })
                .server("score on the stand, start with the baton", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    if (level.getBlockEntity(PODIUM) instanceof PodiumBlockEntity be) {
                        be.setScore(ConductingViews.punched(ConductingViews.score()));
                        Orchestra.batonOnPodium(ctx.player().getMainHandItem(), level, PODIUM, ctx.player());
                    }
                })
                .waitUntil("the zombie's bench sounds in the ensemble", ctx -> Conducting.sounding(PODIUM, VIOLIN_BENCH) > 0)
                .waitUntil("the pianist's hands are on the keys", ctx -> {
                    var m = musician(pianist);
                    return m != null && m.left() != null && m.right() != null && m.left().y < 14.5f;
                });
            b = shot(b, "hall_overview", point(0, 9.5, 9), point(0, 4, -12));
            b = shot(b, "hall_first_violins", point(-3.5, 6.2, -4.5), point(-9, 4.6, -9.5));
            b = shot(b, "hall_violas_winds", point(4, 7.2, -6), point(1, 5.5, -13));
            b = shot(b, "hall_brass", point(6, 7.6, -13.5), point(3, 6.6, -17));
            b = shot(b, "hall_piano_elf", point(-6.8, 5.2, -3.4), point(-5, 4.6, -6.2));
            b = shot(b, "hall_harpsichord_fox", point(-2.2, 5.0, -11.2), point(-4, 4.4, -9));
            b = shot(b, "hall_celesta_harp", point(-6.2, 6.4, -11.2), point(-9, 5.5, -14));
            b = shot(b, "hall_organ_elf", point(2.4, 8.6, -16.4), point(0, 7.6, -19.4));
            b = shot(b, "hall_cellos_basses", point(4.0, 5.8, -3.6), point(8.5, 4.2, -8.6));
            b = shot(b, "hall_timpani", point(-6.4, 8.8, -15.6), point(-6.4, 7.0, -19.8));
            b = shot(b, "hall_tubular_bells", point(7.4, 9.2, -22.6), point(5.5, 7.6, -19.6));
            b = shot(b, "hall_glockenspiel", point(-3.9, 6.8, -11.0), point(-5.6, 5.0, -13.3));
            // 侧面近景：看得见键盘手的手搭在琴键上
            b = shot(b, "hall_piano_hands", point(-3.3, 5.7, -4.4), point(-4.1, 4.1, -6.1));
            b = shot(b, "hall_celesta_hands", point(-6.5, 6.0, -13.9), point(-7.6, 5.0, -13.2));
            b = shot(b, "hall_organ_hands", point(1.9, 8.0, -17.6), point(0.4, 7.1, -18.9));
            b = b.server("stop, the player sits at a free violin bench with a violin", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    if (level.getBlockEntity(PODIUM) instanceof PodiumBlockEntity be) be.stop();
                    ctx.player().getInventory().setItem(0, new ItemStack(Instruments.VIOLIN.get()));
                    StarbloomBenchBlock.sit(level, FREE_VIOLIN, ctx.player());
                })
                .waitUntil("I sit with my violin", ctx -> {
                    var me = Minecraft.getInstance().player;
                    var m = MusicianPoses.of(me);
                    return me.getVehicle() instanceof BenchSeat && m != null && m.mode() == MusicianPoses.Mode.HANDHELD;
                });
            b = shot(b, "hall_player_violin", point(-12.4, 5.0, -5.6), point(-13, 4.6, -8));
            b = b.server("the player moves to the organ bench; the console plays a roll", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    ctx.player().stopRiding();
                    // 管风琴只有正中一张琴凳：精灵让座
                    Entity organ = level.getEntity(organist);
                    if (organ != null) {
                        organ.stopRiding();
                        organ.discard();
                    }
                    StarbloomBenchBlock.sit(level, ORGAN_BENCH, ctx.player());
                    if (InstrumentBlock.entityAt(level, CONSOLE) instanceof InstrumentBlockEntity console) {
                        console.setRoll(ConductingViews.punched(ConductingViews.score()));
                        console.play();
                    }
                })
                .waitUntil("my hands play the organ", ctx -> {
                    var m = MusicianPoses.of(Minecraft.getInstance().player);
                    return m != null && m.mode() == MusicianPoses.Mode.KEYS && m.left() != null && m.left().x < m.right().x;
                });
            b = shot(b, "hall_player_organ", point(-2.4, 8.6, -16.6), point(0.5, 7.6, -19.4));
            b.teardownServer("stop playing, stand up", ctx -> {
                ServerLevel level = ctx.server().overworld();
                if (InstrumentBlock.entityAt(level, CONSOLE) instanceof InstrumentBlockEntity console) console.stopPlayback();
                if (level.getBlockEntity(PODIUM) instanceof PodiumBlockEntity be) be.stop();
                ctx.player().stopRiding();
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

    static final BlockPos GLOCKENSPIEL = at(-6, 4, -13), TUBULAR_BELLS = at(5, 6, -19), TIMPANI_BENCH_WEST = at(-8, 6, -20);

    /** 让出工位、琴凳：上面的生物下来并消失（给玩家、女仆腾地方）。 */
    static void vacate(ServerLevel level, BlockPos benchOrPost) {
        Entity who = level.getBlockState(benchOrPost).getBlock() instanceof StarbloomBenchBlock
            ? StarbloomBenchBlock.occupant(level, benchOrPost) : dev.logos.dimension.music.InstrumentPosts.occupant(level, benchOrPost);
        if (who != null) {
            who.stopRiding();
            MUSICIANS.remove((Integer) who.getId());
            who.discard();
        }
    }

    /** 开始合奏（总谱放上指挥台、指挥棒开始），工位上的乐器跟着声部响。 */
    static ScenarioBuilder conduct(ScenarioBuilder b) {
        return b.server("score on the stand, start with the baton", ctx -> {
                ServerLevel level = ctx.server().overworld();
                if (level.getBlockEntity(PODIUM) instanceof PodiumBlockEntity be) {
                    be.setScore(ConductingViews.punched(ConductingViews.score()));
                    Orchestra.batonOnPodium(ctx.player().getMainHandItem(), level, PODIUM, ctx.player());
                }
            })
            .waitUntil("the zombie's bench sounds in the ensemble", ctx -> Conducting.sounding(PODIUM, VIOLIN_BENCH) > 0);
    }

    /** 玩家站到钟琴、管钟前的工位上（空手右键乐器），坐到定音鼓后的双人琴凳上：琴槌画在手上，跟着那件乐器的音敲。 */
    @LDLRegisterClient(name = "orchestra_player_mallets_ui", group = "instrument", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class PlayerMallets extends HallViews {
        @Override
        public void define(ScenarioBuilder s) {
            ScenarioBuilder b = conduct(stage(s))
                .server("the skeleton leaves the glockenspiel; I right-click it with an empty hand", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    vacate(level, GLOCKENSPIEL);
                    ctx.player().getInventory().setSelectedSlot(1);
                    level.getBlockState(GLOCKENSPIEL).useWithoutItem(level, ctx.player(),
                        new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(GLOCKENSPIEL), net.minecraft.core.Direction.UP, GLOCKENSPIEL, false));
                })
                .waitUntil("I stand at the glockenspiel's post", ctx -> {
                    var me = Minecraft.getInstance().player;
                    return me.getVehicle() instanceof dev.logos.dimension.music.InstrumentSeat seat
                        && seat.getType() == Instruments.STANDING_POST.get() && seat.instrument().equals(GLOCKENSPIEL);
                })
                .waitUntil("my two mallets follow the glockenspiel and strike", ctx -> {
                    var m = MusicianPoses.of(Minecraft.getInstance().player);
                    return m != null && m.mode() == MusicianPoses.Mode.MALLETS && m.kind() == InstrumentKind.GLOCKENSPIEL && m.standing()
                        && m.left() != null && m.right() != null && Math.min(m.left().y, m.right().y) < 13.3f;
                });
            b = shot(b, "hall_player_glockenspiel", point(-3.9, 6.8, -11.0), point(-5.6, 5.0, -13.3));
            b = shotOf(b, "hall_player_glockenspiel_side", () -> Minecraft.getInstance().player.getId(), 2.8, 1.3, 1.4);
            b = b.server("on to the tubular bells", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    ctx.player().stopRiding();
                    vacate(level, TUBULAR_BELLS);
                    level.getBlockState(TUBULAR_BELLS).useWithoutItem(level, ctx.player(),
                        new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(TUBULAR_BELLS), net.minecraft.core.Direction.UP, TUBULAR_BELLS, false));
                })
                .waitUntil("my hammer follows the tubular bells", ctx -> {
                    var m = MusicianPoses.of(Minecraft.getInstance().player);
                    return m != null && m.mode() == MusicianPoses.Mode.MALLETS && m.kind() == InstrumentKind.TUBULAR_BELLS
                        && m.right() != null && m.left() == null;
                });
            b = shot(b, "hall_player_tubular_bells", point(7.4, 9.2, -22.6), point(5.5, 7.6, -19.6));
            b = shotOf(b, "hall_player_tubular_bells_side", () -> Minecraft.getInstance().player.getId(), -2.8, 1.6, 1.4);
            b = b.server("on to the west pair of timpani", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    ctx.player().stopRiding();
                    vacate(level, TIMPANI_BENCH_WEST);
                    StarbloomBenchBlock.sit(level, TIMPANI_BENCH_WEST, ctx.player());
                })
                .waitUntil("my mallets reach both drums", ctx -> {
                    var m = MusicianPoses.of(Minecraft.getInstance().player);
                    return m != null && m.mode() == MusicianPoses.Mode.MALLETS && m.kind() == InstrumentKind.TIMPANI
                        && m.left() != null && m.right() != null && m.left().x < m.right().x;
                });
            b = shot(b, "hall_player_timpani", point(-8.4, 8.8, -15.8), point(-8.4, 7.0, -19.8));
            b.teardownServer("stop playing, stand up", ctx -> {
                ServerLevel level = ctx.server().overworld();
                if (level.getBlockEntity(PODIUM) instanceof PodiumBlockEntity be) be.stop();
                ctx.player().stopRiding();
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
}
