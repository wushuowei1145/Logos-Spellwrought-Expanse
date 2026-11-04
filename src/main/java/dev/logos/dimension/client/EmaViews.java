package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.gui.ui.elements.TextArea;
import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.cloudsea.EmaItem;
import dev.logos.dimension.cloudsea.EmaRackBlockEntity;
import dev.logos.dimension.cloudsea.EmaWish;
import dev.logos.dimension.cloudsea.FoxWildsBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 绘马：拿一叠空白绘马右键写字、点「写好了」存回物品（只从一叠里取出一块写），
 * 再把它挂到绘马挂右边那一格；右键翻看架上的绘马，潜行空手右键取下中间那一块。
 */
@LDLRegisterClient(name = "ema_views", group = "fox_dev", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class EmaViews implements UIScenario {
    private static final int GROUND = 240;
    private static final BlockPos RACK = new BlockPos(0, GROUND + 1, -2);
    private static final String WISH = "愿家人平安喜乐，\n诸事顺遂。";

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("fox_dev").defaultTimeoutMs(180000).scenarioTimeoutMs(300000);
    }

    /** 点在绘马挂正面（朝南）某一格上的命中：0 左、1 中、2 右（从正面看）。 */
    private static BlockHitResult hit(int slot) {
        double x = RACK.getX() + 0.5 + (slot - 1) * 0.3;
        return new BlockHitResult(new Vec3(x, RACK.getY() + 0.6, RACK.getZ() + 11 / 16.0), Direction.SOUTH, RACK, false);
    }

    private static EmaRackBlockEntity rack(ServerContext ctx) {
        return ctx.blockEntity(RACK, EmaRackBlockEntity.class);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL)
            .runCommand("difficulty peaceful")
            .runCommand("time set day")
            .step("camera", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.hideGui = false;
                mc.options.fov().set(70);
            })
            .server("stage a rack and three blank emas", ctx -> {
                ServerLevel level = ctx.server().overworld();
                for (int x = -4; x <= 4; x++) for (int z = -5; z <= 4; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, GROUND, z), Blocks.STONE.defaultBlockState());
                    for (int y = 1; y <= 4; y++) level.removeBlock(new BlockPos(x, GROUND + y, z), false);
                }
                level.setBlockAndUpdate(RACK, FoxWildsBlocks.EMA_RACK.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH));
                var player = ctx.player();
                player.getInventory().clearContent();
                dev.logos.dimension.DimensionTravel.teleport(player, level, new Vec3(0.5, GROUND + 1, 0.9), 180f, 12f);
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(FoxWildsBlocks.EMA.get(), 3));
            })
            .awaitClientBlockEntity(RACK)
            .check("creative tab lists the ema and the rack", ctx -> {
                var mc = Minecraft.getInstance();
                net.minecraft.world.item.CreativeModeTabs.tryRebuildTabContents(mc.player.connection.enabledFeatures(), true, mc.level.registryAccess());
                var tab = net.minecraft.core.registries.BuiltInRegistries.CREATIVE_MODE_TAB.getValue(dev.logos.dimension.LogosDimension.id("cloudsea"));
                return tab != null && tab.getDisplayItems().stream().anyMatch(i -> i.is(FoxWildsBlocks.EMA.get()))
                    && tab.getDisplayItems().stream().anyMatch(i -> i.is(FoxWildsBlocks.EMA_RACK.get().asItem()));
            })
            .step("open the writing page", ctx -> EmaScreen.openWrite(Minecraft.getInstance().player, InteractionHand.MAIN_HAND))
            .awaitModularUI()
            .ticks(5)
            .check("writing area takes focus", ctx -> ctx.ui().select("#ema_text", TextArea.class).findFirst().orElseThrow().isFocused())
            .step("write a wish", ctx -> ctx.ui().select("#ema_text", TextArea.class).findFirst().orElseThrow().setValue(WISH.split("\n"), true))
            .ticks(3)
            .screenshot("ema_write")
            .step("start a fresh line", ctx -> ctx.ui().select("#ema_text", TextArea.class).findFirst().orElseThrow().setValue(new String[] {""}, true))
            .type("一二三四五六七八九十".repeat(4))
            .ticks(2)
            .check("typing stops at the board's edge", ctx -> {
                String line = ctx.ui().select("#ema_text", TextArea.class).findFirst().orElseThrow().getValue()[0];
                return line.length() > 10 && line.length() < 40 && Minecraft.getInstance().font.width(line) <= 222 - 6;
            })
            .step("write the wish back", ctx -> ctx.ui().select("#ema_text", TextArea.class).findFirst().orElseThrow().setValue(WISH.split("\n"), true))
            .checkExists("#ema_done")
            // 像书与笔：合上页面就存（「写好了」按下即关页，测试的松开动作落不到页面上，所以这里按 Esc 的路径合上）
            .closeScreen()
            .waitUntilServer("one written ema split off the stack", ctx -> {
                var inv = ctx.player().getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    EmaWish wish = inv.getItem(i).get(EmaItem.WISH.get());
                    if (wish != null && wish.text().equals(WISH)) return true;
                }
                return false;
            })
            .checkServer("two blank emas stay in hand", ctx -> {
                ItemStack held = ctx.player().getMainHandItem();
                return held.is(FoxWildsBlocks.EMA.get()) && held.getCount() == 2 && !EmaItem.isWritten(held);
            })
            .server("hang it on the right, plus two more", ctx -> {
                var player = ctx.player();
                var inv = player.getInventory();
                ItemStack written = ItemStack.EMPTY;
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    if (EmaItem.isWritten(inv.getItem(i))) written = inv.removeItemNoUpdate(i);
                }
                ItemStack blank = player.getMainHandItem().copy();
                BlockState state = ctx.server().overworld().getBlockState(RACK);
                state.useItemOn(blank, ctx.server().overworld(), player, InteractionHand.MAIN_HAND, hit(0));
                ctx.check("blank ema does not hang", rack(ctx).isEmpty());
                player.setItemInHand(InteractionHand.MAIN_HAND, written);
                state.useItemOn(written, ctx.server().overworld(), player, InteractionHand.MAIN_HAND, hit(2));
                ctx.check("written ema hangs where it was pointed", EmaItem.isWritten(rack(ctx).getItems().get(2)));
                ctx.check("hanging uses it up", player.getMainHandItem().isEmpty());
                ItemStack other = new ItemStack(FoxWildsBlocks.EMA.get());
                other.set(EmaItem.WISH.get(), new EmaWish("学业进步\n身体健康", "艾洛蒂"));
                ItemStack old = new ItemStack(FoxWildsBlocks.EMA.get());
                old.set(DataComponents.CUSTOM_NAME, Component.literal("五谷丰登"));
                rack(ctx).hang(old);
                rack(ctx).hang(other);
            })
            .waitUntil("client sees three emas", ctx -> Minecraft.getInstance().level.getBlockEntity(RACK) instanceof EmaRackBlockEntity r
                && r.getItems().stream().noneMatch(ItemStack::isEmpty))
            .step("hide HUD", ctx -> Minecraft.getInstance().options.hideGui = true)
            .ticks(10)
            .screenshot("ema_rack_world")
            .step("view the rack from the right one", ctx -> {
                Minecraft.getInstance().options.hideGui = false;
                EmaScreen.openRack(RACK, 2);
            })
            .awaitModularUI()
            .ticks(5)
            .check("starts at the pointed ema", ctx -> String.join("\n", ctx.ui().select("#ema_text", TextArea.class).findFirst().orElseThrow().getValue()).equals(WISH))
            .screenshot("ema_view")
            .click("#ema_next")
            .ticks(2)
            .check("pages to the next hung ema", ctx -> String.join("\n", ctx.ui().select("#ema_text", TextArea.class).findFirst().orElseThrow().getValue()).equals("五谷丰登"))
            .click("#ema_next")
            .ticks(2)
            .screenshot("ema_view_other")
            .check("page count reads 2 / 3", ctx -> ctx.ui().select("#ema_page", com.lowdragmc.lowdraglib2.gui.ui.elements.Label.class).findFirst().orElseThrow()
                .getValue().getString().equals("2 / 3"))
            .closeScreen()
            .server("sneak and take down the middle one", ctx -> {
                var player = ctx.player();
                player.setShiftKeyDown(true);
                ctx.server().overworld().getBlockState(RACK).useWithoutItem(ctx.server().overworld(), player, hit(1));
                player.setShiftKeyDown(false);
                var items = rack(ctx).getItems();
                ctx.check("middle one taken", items.get(1).isEmpty());
                ctx.check("the others stay", !items.get(0).isEmpty() && !items.get(2).isEmpty());
                EmaWish got = null;
                var inv = player.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    EmaWish w = inv.getItem(i).get(EmaItem.WISH.get());
                    if (w != null) got = w;
                }
                ctx.check("taken ema keeps its wish", got != null && got.author().equals("艾洛蒂"));
            })
            .teardown("restore HUD", ctx -> {
                Minecraft.getInstance().options.hideGui = false;
                Minecraft.getInstance().setScreen(null);
            });
    }
}
