package dev.logos.dimension.client.music;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.music.Benches;
import dev.logos.dimension.music.Instruments;
import dev.logos.dimension.music.MusicStandBlock;
import dev.logos.dimension.music.organ.OrganConsoleBlock;
import dev.logos.dimension.music.organ.OrganPipeBlock;
import dev.logos.dimension.music.organ.OrganTrumpetBlock;
import dev.logos.dimension.music.orchestra.Orchestra;
import dev.logos.dimension.music.orchestra.PodiumBlock;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Supplier;

/**
 * 乐器美术验收：全部乐器朝南摆三排（键盘与大件 / 弦乐、谱架、家具 / 管风琴音管），正面、背面、近景截图；
 * 再看物品：背包里的图标（GUI 缩放 4）、第一人称手里拿着、第三人称、掉在地上。只截图，不检查数值。
 */
@LDLRegisterClient(name = "instrument_gallery_ui", group = "instrument", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class InstrumentGalleryViews implements UIScenario {
    private static final int GROUND = 240;
    private static final BlockPos ORIGIN = new BlockPos(-260, GROUND, 120);
    private static final MusicStandBlock.Holds[] STANDS = MusicStandBlock.Holds.values();

    /** 物品图标、手持、掉落都看这一份清单（顺序 = 背包里的顺序）。 */
    private static List<Supplier<? extends Item>> items() {
        return List.of(
            Instruments.GRAND_PIANO_ITEM, Instruments.HARPSICHORD_ITEM, Instruments.CELESTA_ITEM, Instruments.ORGAN_CONSOLE_ITEM,
            Instruments.ORGAN_WIND_CHEST_ITEM, Instruments.ORGAN_TIN_PIPE_ITEM, Instruments.ORGAN_WOOD_PIPE_ITEM, Instruments.ORGAN_TRUMPET_PIPE_ITEM,
            Instruments.CONCERT_HARP_ITEM, Instruments.TUBULAR_BELLS_ITEM, Instruments.GLOCKENSPIEL_ITEM, Instruments.TIMPANI_ITEM,
            Instruments.CELLO_ITEM, Instruments.DOUBLE_BASS_ITEM, Instruments.VIOLIN, Instruments.VIOLA,
            Instruments.FLUTE, Instruments.OBOE, Instruments.BASSOON, Instruments.TRUMPET,
            Instruments.MUSIC_STAND_ITEM, Orchestra.PODIUM_ITEM, Orchestra.BATON, Benches.BENCH_ITEM, Benches.DUET_ITEM,
            Instruments.BLANK_ROLL, Instruments.MUSIC_ROLL_ITEM);
    }

    /** 第一人称拿着看的几件：大件方块、管风琴零件、手持乐器。 */
    private static final int[] HELD = {0, 4, 5, 8, 10, 14, 16, 19, 20};

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("instrument", "art").guiScale(4).defaultTimeoutMs(180000).scenarioTimeoutMs(600000);
    }

    private static BlockState facing(BlockState s) {
        if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) s = s.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH);
        return s;
    }

    private static void put(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlockAndUpdate(pos, state);
        state.getBlock().setPlacedBy(level, pos, state, null, ItemStack.EMPTY);
    }

    private static void put(ServerLevel level, BlockPos pos, Block block) {
        put(level, pos, facing(block.defaultBlockState()));
    }

    private static void column(ServerLevel level, BlockPos bottom, Block pipe, int height) {
        for (int i = 0; i < height; i++) {
            BlockPos p = bottom.above(i);
            BlockState s = facing(pipe.defaultBlockState());
            level.setBlock(p, s, Block.UPDATE_CLIENTS);
            if (pipe instanceof OrganPipeBlock b) level.setBlock(p, b.withShape(level.getBlockState(p), level, p), Block.UPDATE_CLIENTS);
        }
    }

    /** 悬停在半空（创造模式飞行），机位不会往下掉。 */
    private static void fly(net.minecraft.server.level.ServerPlayer player) {
        player.getAbilities().mayfly = true;
        player.getAbilities().flying = true;
        player.onUpdateAbilities();
    }

    private static ScenarioBuilder shot(ScenarioBuilder s, String name, double x, double y, double z, float yaw, float pitch) {
        return s.server("camera " + name, ctx -> {
                DimensionTravel.teleport(ctx.player(), ctx.server().overworld(),
                    new Vec3(ORIGIN.getX() + x, GROUND + y, ORIGIN.getZ() + z), yaw, pitch);
                fly(ctx.player());
            })
            .ticks(30)
            .screenshot("gallery_" + name);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
            .runCommand("difficulty peaceful")
            .runCommand("time set noon")
            .runCommand("gamerule doDaylightCycle false")
            .runCommand("weather clear")
            .step("camera", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.hideGui = true;
                mc.options.renderDistance().set(8);
                mc.options.fov().set(70);
                mc.options.broadcastOptions();
            })
            .server("stage every instrument", ctx -> {
                ServerLevel level = ctx.server().overworld();
                for (int x = -20; x <= 20; x++) {
                    for (int z = -18; z <= 8; z++) {
                        BlockPos p = ORIGIN.offset(x, 0, z);
                        for (int y = 1; y <= 12; y++) {
                            level.setBlock(p.above(y), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS);
                        }
                        level.setBlock(p, ((x + z & 1) == 0 ? Blocks.POLISHED_ANDESITE : Blocks.CALCITE).defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
                level.getEntitiesOfClass(ItemEntity.class, new AABB(ORIGIN).inflate(24)).forEach(ItemEntity::discard);
                // 第一排：键盘与大件
                put(level, ORIGIN.offset(-15, 1, 0), Instruments.GRAND_PIANO.get());
                put(level, ORIGIN.offset(-10, 1, 0), Instruments.HARPSICHORD.get());
                put(level, ORIGIN.offset(-7, 1, 0), Instruments.CELESTA.get());
                BlockState console = Instruments.ORGAN_CONSOLE.get().placed(Direction.SOUTH);
                put(level, ORIGIN.offset(-3, 1, 0), console);
                put(level, ORIGIN.offset(2, 1, 0), Instruments.CONCERT_HARP.get());
                put(level, ORIGIN.offset(5, 1, 0), Instruments.TUBULAR_BELLS.get());
                put(level, ORIGIN.offset(8, 1, 0), Instruments.TIMPANI.get());
                put(level, ORIGIN.offset(11, 1, 0), Instruments.GLOCKENSPIEL.get());
                put(level, ORIGIN.offset(15, 1, 0), Orchestra.PODIUM.get());
                // 第二排：大提琴、低音提琴、七个谱架、琴凳
                put(level, ORIGIN.offset(-15, 1, -6), Instruments.CELLO.get());
                put(level, ORIGIN.offset(-12, 1, -6), Instruments.DOUBLE_BASS.get());
                for (int i = 0; i < STANDS.length; i++) {
                    put(level, ORIGIN.offset(-9 + 2 * i, 1, -6),
                        facing(Instruments.MUSIC_STAND.get().defaultBlockState()).setValue(MusicStandBlock.HOLDS, STANDS[i]));
                }
                put(level, ORIGIN.offset(7, 1, -6), Benches.BENCH.get());
                put(level, ORIGIN.offset(10, 1, -6), Benches.DUET.get());
                BlockState scored = facing(Orchestra.PODIUM.get().defaultBlockState()).setValue(PodiumBlock.SCORE, true);
                put(level, ORIGIN.offset(15, 1, -6), scored);
                // 第三排：管风琴音管（立在风箱上）
                int[][] cols = {{-8, 0, 3}, {-5, 1, 3}, {-2, 2, 1}, {1, 0, 1}, {3, 1, 1}, {5, 0, 2}, {7, 1, 2}};
                for (int[] c : cols) {
                    BlockPos chest = ORIGIN.offset(c[0], 1, -12);
                    put(level, chest, Instruments.ORGAN_WIND_CHEST.get());
                    Block pipe = c[1] == 0 ? Instruments.ORGAN_TIN_PIPE.get() : c[1] == 1 ? Instruments.ORGAN_WOOD_PIPE.get() : Instruments.ORGAN_TRUMPET_PIPE.get();
                    column(level, chest.above(), pipe, c[2]);
                }
                put(level, ORIGIN.offset(10, 1, -12), Instruments.ORGAN_WIND_CHEST.get());
                DimensionTravel.teleport(ctx.player(), level, new Vec3(ORIGIN.getX(), GROUND + 9, ORIGIN.getZ() + 14), 180f, 30f);
                fly(ctx.player());
            })
            .waitUntil("instruments arrive", ctx -> {
                var mc = Minecraft.getInstance();
                return mc.level != null && mc.level.getBlockState(ORIGIN.offset(10, 1, -12)).is(Instruments.ORGAN_WIND_CHEST.get())
                    && mc.level.getBlockState(ORIGIN.offset(-15, 1, 0)).is(Instruments.GRAND_PIANO.get());
            })
            .ticks(120);
        shot(s, "overview", 0, 9, 14, 180f, 30f);
        shot(s, "keyboards", -10, 2.8, 5.5, 180f, 16f);
        shot(s, "percussion", 8, 2.8, 5.5, 180f, 16f);
        shot(s, "keyboards_close", -13, 2.6, 3.4, 200f, 30f);
        shot(s, "console_close", -3, 2.6, 3.4, 180f, 22f);
        shot(s, "harp_bells_close", 3.5, 2.4, 3.6, 180f, 14f);
        shot(s, "timpani_close", 9.5, 2.6, 2.8, 180f, 34f);
        shot(s, "strings_stands", -9, 2.2, -2.6, 180f, 12f);
        shot(s, "stands_close", -2, 2.0, -3.6, 180f, 18f);
        shot(s, "furniture", 10, 2.4, -2.6, 180f, 18f);
        shot(s, "organ_pipes", 0, 3.4, -6.5, 180f, 14f);
        shot(s, "organ_pipes_close", -4, 2.6, -9.5, 200f, 20f);
        shot(s, "back", 0, 7, -22, 0f, 22f);
        shot(s, "back_keyboards", -9, 2.8, -4.5, 0f, 22f);
        s.step("show the HUD", ctx -> Minecraft.getInstance().options.hideGui = false)
            .setGameMode(GameType.SURVIVAL)
            .server("fill the inventory", ctx -> {
                var inv = ctx.player().getInventory();
                inv.clearContent();
                List<Supplier<? extends Item>> all = items();
                for (int i = 0; i < all.size() && i < 36; i++) inv.setItem((i + 9) % 36, new ItemStack(all.get(i).get()));
                for (int k = 0; k < HELD.length; k++) inv.setItem(k, new ItemStack(all.get(HELD[k]).get()));
                DimensionTravel.teleport(ctx.player(), ctx.server().overworld(), new Vec3(ORIGIN.getX() + 0.5, GROUND + 1, ORIGIN.getZ() + 5.5), 180f, 10f);
            })
            .ticks(20)
            .step("open the inventory", ctx -> {
                var mc = Minecraft.getInstance();
                mc.setScreen(new InventoryScreen(mc.player));
            })
            .ticks(10)
            .screenshot("gallery_inventory_icons")
            .step("close", ctx -> Minecraft.getInstance().setScreen(null));
        for (int k = 0; k < HELD.length; k++) {
            final int slot = k;
            s.step("hold slot " + k, ctx -> Minecraft.getInstance().player.getInventory().setSelectedSlot(slot))
                .ticks(12)
                .screenshot("gallery_held_" + k);
        }
        // 快捷栏第 2 格是锡管、第 5 格是小提琴（见 HELD）
        s.step("third person front, tin pipe", ctx -> {
                var mc = Minecraft.getInstance();
                mc.player.getInventory().setSelectedSlot(2);
                mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
            })
            .ticks(15)
            .screenshot("gallery_third_person_pipe")
            .step("third person front, violin", ctx -> Minecraft.getInstance().player.getInventory().setSelectedSlot(5))
            .ticks(15)
            .screenshot("gallery_third_person_violin")
            .step("first person", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON))
            .setGameMode(GameType.CREATIVE)
            .server("drop every item on the floor", ctx -> {
                ServerLevel level = ctx.server().overworld();
                List<Supplier<? extends Item>> all = items();
                for (int i = 0; i < all.size(); i++) {
                    double x = ORIGIN.getX() - 6.5 + (i % 9) * 1.6, z = ORIGIN.getZ() + 3.2 + (i / 9) * 1.4;
                    ItemEntity e = new ItemEntity(level, x, GROUND + 1.05, z, new ItemStack(all.get(i).get()));
                    e.setDeltaMovement(Vec3.ZERO);
                    e.setNeverPickUp();
                    e.setUnlimitedLifetime();
                    level.addFreshEntity(e);
                }
                DimensionTravel.teleport(ctx.player(), level, new Vec3(ORIGIN.getX() + 0.5, GROUND + 3.4, ORIGIN.getZ() + 7.5), 180f, 38f);
                fly(ctx.player());
            })
            .step("hide the HUD", ctx -> Minecraft.getInstance().options.hideGui = true)
            .ticks(40)
            .screenshot("gallery_dropped")
            .teardown("restore", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.hideGui = false;
                mc.options.setCameraType(CameraType.FIRST_PERSON);
            });
    }
}
