package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.LogosMod;
import dev.logos.block.InscriptionTableBlockEntity;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.reifu.Reifu;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.Vec3;

/** 霊符与符札书案陈列（本地开发用）：一排书案放着不同品级的霊符，旁边一张编纂台对照；快捷栏放 16 张霊符。 */
@LDLRegisterClient(name = "reifu_views", group = "reifu", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class ReifuViews implements UIScenario {
    private static final String SPELL = "(spell :name \"符\" :mana 5 (heal caster 1))";

    @Override public void configure(ScenarioOptions o) {
        o.tags("reifu").defaultTimeoutMs(200000).scenarioTimeoutMs(300000);
    }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SPECTATOR).runCommand("time set noon").runCommand("weather clear")
            .step("camera", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.hideGui = true;
                mc.options.fov().set(60);
            })
            .server("build", ctx -> {
                var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                int y = 300;
                for (int x = -2; x <= 18; x++) for (int z = -3; z <= 6; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.SPRUCE_PLANKS.defaultBlockState());
                    for (int k = 1; k < 5; k++) level.setBlockAndUpdate(new BlockPos(x, y + k, z), Blocks.AIR.defaultBlockState());
                }
                // 第一张是 Logos 的编纂台作对照，后面依次：空书案、和紙空白、藤紫写好、木霊、錬丹、胡粉、天津、仙朱、神勅
                place(level, new BlockPos(0, y + 1, 1), LogosMod.INSCRIPTION_TABLE.get().defaultBlockState(), SpellPages.write(new ItemStack(PageTier.CRYSTAL.blank()), SPELL, null));
                place(level, new BlockPos(2, y + 1, 1), Reifu.FUDA_DESK.get().defaultBlockState(), ItemStack.EMPTY);
                PageTier[] tiers = PageTier.values();
                for (int i = 0; i < tiers.length; i++) {
                    var pair = Reifu.REIFU.get(tiers[i]);
                    ItemStack page = i == 0 ? new ItemStack(pair.blank().get()) : SpellPages.write(new ItemStack(pair.blank().get()), SPELL, null);
                    place(level, new BlockPos(4 + i * 2 - (i >= 4 ? 8 : 0), y + 1, i >= 4 ? 4 : 1), Reifu.FUDA_DESK.get().defaultBlockState(), page);
                }
                // 一张朝东的书案，看转向
                place(level, new BlockPos(14, y + 1, 1), Reifu.FUDA_DESK.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.EAST),
                    SpellPages.write(new ItemStack(Reifu.REIFU.get(PageTier.BASIC).blank().get()), SPELL, null));
                DimensionTravel.teleport(ctx.player(), level, new Vec3(6.5, y + 3.6, 9), 180f, 28f);
                ctx.player().setNoGravity(true);
            })
            .ticks(80)
            .screenshot("reifu_row")
            .server("close", ctx -> {
                var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                DimensionTravel.teleport(ctx.player(), level, new Vec3(5.0, 302.3, 3.6), 180f, 45f);
            })
            .ticks(40)
            .screenshot("reifu_desk_close")
            .server("side", ctx -> {
                var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                DimensionTravel.teleport(ctx.player(), level, new Vec3(1.2, 301.9, 4.2), 225f, 20f);
            })
            .ticks(40)
            .screenshot("reifu_desk_side")
            .server("hotbar", ctx -> {
                var p = ctx.player();
                p.setGameMode(GameType.CREATIVE);
                PageTier[] tiers = PageTier.values();
                for (int i = 0; i < tiers.length; i++) {
                    var pair = Reifu.REIFU.get(tiers[i]);
                    p.getInventory().setItem(i, new ItemStack(pair.blank().get()));
                    p.getInventory().setItem(9 + i, SpellPages.write(new ItemStack(pair.blank().get()), SPELL, null));
                }
                p.getInventory().setItem(8, new ItemStack(Reifu.FUDA_DESK_ITEM.get()));
            })
            .step("show gui", ctx -> Minecraft.getInstance().options.hideGui = false)
            .ticks(20)
            .screenshot("reifu_hotbar")
            .step("inventory", ctx -> Minecraft.getInstance().setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(Minecraft.getInstance().player)))
            .ticks(20)
            .screenshot("reifu_inventory")
            .teardown("restore", ctx -> {
                Minecraft.getInstance().setScreen(null);
                Minecraft.getInstance().options.hideGui = false;
            });
    }

    private static void place(net.minecraft.server.level.ServerLevel level, BlockPos pos, net.minecraft.world.level.block.state.BlockState state, ItemStack page) {
        if (state.hasProperty(HorizontalDirectionalBlock.FACING) && state.getValue(HorizontalDirectionalBlock.FACING) == Direction.NORTH) {
            state = state.setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH);
        }
        level.setBlockAndUpdate(pos, state);
        if (!page.isEmpty() && level.getBlockEntity(pos) instanceof InscriptionTableBlockEntity table) table.setPage(page);
    }
}
