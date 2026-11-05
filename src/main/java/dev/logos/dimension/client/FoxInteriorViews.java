package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.cloudsea.FoxWildsBlocks;
import dev.logos.dimension.cloudsea.MenuStripsBlock;
import dev.logos.dimension.cloudsea.OfferingBoxBlock;
import dev.logos.dimension.cloudsea.Omikuji;
import dev.logos.dimension.cloudsea.OmikujiRackBlock;
import dev.logos.dimension.cloudsea.ShrineHokoraBlock;
import dev.logos.dimension.economy.Currency;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** 神狐聚落的室内与新方块（本地开发用）：把各地块模板摆在高空，进屋拍居酒屋、卧室与楼上，再拍新方块一排。 */
@LDLRegisterClient(name = "fox_interior_views", group = "fox_dev", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class FoxInteriorViews implements UIScenario {
    private static final int Y = 300;
    /** 模板名、镜头位置与看向（模板内的建筑坐标，见 tools/fox_buildings.py；模板原点是建筑坐标 (-2, -2, -2)）。 */
    private record Shot(String template, double fx, double fy, double fz, double tx, double ty, double tz) {
    }

    private static final Shot[] SHOTS = {
        new Shot("shop_izakaya_u", 4.5, 1, 2.5, 4.5, 2.4, 9),
        new Shot("shop_izakaya_long", 6.5, 1, 2.5, 1.5, 2.2, 7),
        new Shot("shop_noodle", 6.5, 6, 3.5, 1.5, 6.5, 8.5),
        new Shot("house_a", 8.5, 1, 4.5, 2, 1.5, 7),
        new Shot("house_b", 5.5, 6, 3.5, 2, 6.5, 8),
        new Shot("house_d", 6.5, 1, 2.5, 6.5, 2.6, 9),
        new Shot("house_e", 9.5, 1, 4.5, 7.5, 1.5, 8),
    };

    @Override public void configure(ScenarioOptions o) {
        o.tags("fox_dev").defaultTimeoutMs(200000).scenarioTimeoutMs(400000);
    }

    /** 手持 stack 右键点 pos 处的方块。 */
    private static void use(ServerLevel level, net.minecraft.server.level.ServerPlayer player, BlockPos pos, ItemStack stack) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        level.getBlockState(pos).useItemOn(stack, level, player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }

    private static void go(ServerLevel level, net.minecraft.server.level.ServerPlayer player, Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        DimensionTravel.teleport(player, level, from, (float) (-Math.toDegrees(Math.atan2(dx, dz))), (float) (-Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)))));
        player.setNoGravity(true);
        player.setDeltaMovement(Vec3.ZERO);
    }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SPECTATOR).runCommand("time set noon").runCommand("weather clear")
            .step("camera", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.hideGui = true;
                mc.options.fov().set(80);
            })
            .server("build", ctx -> {
                var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                ctx.player().addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 20 * 600, 0, false, false));
                var manager = ctx.server().getStructureManager();
                for (int i = 0; i < SHOTS.length; i++) {
                    Identifier id = LogosDimension.id("fox_sanctuary/" + SHOTS[i].template);
                    var template = manager.get(id).orElseThrow(() -> new IllegalStateException("缺少模板 " + id));
                    BlockPos origin = new BlockPos(i * 40 - 2, Y - 2, -2);
                    template.placeInWorld(level, origin, origin, new StructurePlaceSettings().setKnownShape(true), level.getRandom(), 2);
                }
                // 新方块一排：石龛未激活与激活、签箱、结签架四档、菜单条三色
                BlockPos row = new BlockPos(0, Y, -40);
                for (int x = -2; x <= 18; x++) for (int z = -3; z <= 3; z++) {
                    level.setBlockAndUpdate(row.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState());
                    for (int k = 0; k < 5; k++) level.setBlockAndUpdate(row.offset(x, k, z), Blocks.AIR.defaultBlockState());
                }
                var south = Direction.SOUTH;
                var hokora = FoxWildsBlocks.SHRINE_HOKORA.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, south);
                level.setBlockAndUpdate(row, hokora);
                level.setBlockAndUpdate(row.offset(2, 0, 0), hokora.setValue(ShrineHokoraBlock.ACTIVE, true));
                level.setBlockAndUpdate(row.offset(4, 0, 0), FoxWildsBlocks.OMIKUJI_BOX.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, south));
                for (int i = 0; i < 4; i++) {
                    level.setBlockAndUpdate(row.offset(6 + i, 0, 0), FoxWildsBlocks.OMIKUJI_RACK.get().defaultBlockState()
                        .setValue(HorizontalDirectionalBlock.FACING, south).setValue(OmikujiRackBlock.TIED, i));
                }
                for (int i = 0; i < 3; i++) {
                    level.setBlockAndUpdate(row.offset(11 + i, 3, 0), Blocks.DARK_OAK_PLANKS.defaultBlockState());
                    level.setBlockAndUpdate(row.offset(11 + i, 2, 0), FoxWildsBlocks.MENU_STRIPS.get().defaultBlockState()
                        .setValue(HorizontalDirectionalBlock.FACING, south).setValue(MenuStripsBlock.PAPER, i));
                }
                // 石龛只有激活时发光；赛钱箱的保佑随面额变长，大钱带上生命恢复与抗性；签运权重加起来对得上
                int off = hokora.getLightEmission(), on = hokora.setValue(ShrineHokoraBlock.ACTIVE, true).getLightEmission();
                var mon = OfferingBoxBlock.blessing(Currency.FOX, Currency.FOX.denominations.getFirst());
                var koban = OfferingBoxBlock.blessing(Currency.FOX, Currency.FOX.top());
                System.out.println("[fox] hokora light off=" + off + " on=" + on + "; offering mon=" + mon + " koban=" + koban + "; fortune weights=" + Omikuji.totalWeight());
                if (off != 0 || on != ShrineHokoraBlock.ACTIVE_LIGHT) throw new IllegalStateException("石龛亮度不对");
                go(level, ctx.player(), new Vec3(7, Y + 0.6, -33), new Vec3(7, Y + 1.2, -40));
            })
            .ticks(100)
            .screenshot("fox_new_blocks");
        // 真的点一下：投钱币得保佑，抽签每天一次，系签解霉运
        s.server("interact", ctx -> {
            var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
            var player = ctx.player();
            BlockPos row = new BlockPos(0, Y, -40);
            BlockPos boxPos = row.offset(15, 0, 0);
            level.setBlockAndUpdate(boxPos, FoxWildsBlocks.OFFERING_BOX.get().defaultBlockState());
            var coin = new ItemStack(BuiltInRegistries.ITEM.getValue(LogosDimension.id("fox_koban")), 2);
            use(level, player, boxPos, coin);
            var luck = player.getEffect(MobEffects.LUCK);
            int first = luck == null ? 0 : luck.getDuration();
            use(level, player, boxPos, coin);
            luck = player.getEffect(MobEffects.LUCK);
            System.out.println("[fox] offering: coins left=" + coin.getCount() + " luck amp=" + (luck == null ? -1 : luck.getAmplifier()) + " ticks " + first + " -> " + (luck == null ? 0 : luck.getDuration())
                + " regen=" + player.hasEffect(MobEffects.REGENERATION) + " resist=" + player.hasEffect(MobEffects.RESISTANCE));
            if (!coin.isEmpty() || luck == null || luck.getAmplifier() != 1 || luck.getDuration() <= first || !player.hasEffect(MobEffects.RESISTANCE)) {
                throw new IllegalStateException("赛钱箱的保佑不对");
            }

            player.removeAllEffects();
            player.getInventory().clearContent();
            BlockPos drawPos = row.offset(4, 0, 0);
            var box = level.getBlockState(drawPos);
            var hit = new BlockHitResult(Vec3.atCenterOf(drawPos), Direction.UP, drawPos, false);
            box.useWithoutItem(level, player, hit);
            int once = player.getInventory().countItem(FoxWildsBlocks.OMIKUJI.get());
            box.useWithoutItem(level, player, hit);
            int twice = player.getInventory().countItem(FoxWildsBlocks.OMIKUJI.get());
            var paper = ItemStack.EMPTY;
            for (var stack : player.getInventory().getNonEquipmentItems()) if (stack.is(FoxWildsBlocks.OMIKUJI.get())) paper = stack;
            System.out.println("[fox] omikuji: first draw=" + once + " after second=" + twice + " name=" + paper.getHoverName().getString()
                + " luck=" + player.hasEffect(MobEffects.LUCK) + " unluck=" + player.hasEffect(MobEffects.UNLUCK));
            if (once != 1 || twice != 1 || !(player.hasEffect(MobEffects.LUCK) || player.hasEffect(MobEffects.UNLUCK))) {
                throw new IllegalStateException("抽签不对");
            }

            player.addEffect(new MobEffectInstance(MobEffects.UNLUCK, 2000));
            BlockPos rackPos = row.offset(6, 0, 0);
            use(level, player, rackPos, paper);
            int tied = level.getBlockState(rackPos).getValue(OmikujiRackBlock.TIED);
            System.out.println("[fox] rack: tied=" + tied + " paper left=" + paper.getCount() + " unluck=" + player.hasEffect(MobEffects.UNLUCK));
            if (tied != 1 || !paper.isEmpty() || player.hasEffect(MobEffects.UNLUCK)) throw new IllegalStateException("结签架不对");
            player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 20 * 600, 0, false, false));
        });
        for (int i = 0; i < SHOTS.length; i++) {
            Shot shot = SHOTS[i];
            int ox = i * 40;
            s.server("go " + shot.template, ctx -> {
                var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                go(level, ctx.player(), new Vec3(ox + shot.fx, Y + shot.fy, shot.fz), new Vec3(ox + shot.tx, Y + shot.ty, shot.tz));
            }).ticks(40).screenshot("fox_room_" + shot.template);
        }
        s.teardown("restore HUD", ctx -> Minecraft.getInstance().options.hideGui = false);
    }
}
