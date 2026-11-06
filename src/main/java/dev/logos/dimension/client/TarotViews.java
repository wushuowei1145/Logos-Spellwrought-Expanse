package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.tarot.TarotCards;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 灵猫塔罗的实机观察：二十二张牌挂满一面墙，分别看第一人称、第三人称、物品栏、掉落物，
 * 再到午夜看发光层是否常亮。这些截图用来校手持位置和模型朝向。
 */
@LDLRegisterClient(name = "tarot_views", group = "tarot_dev", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class TarotViews implements UIScenario {
    private static final int GROUND = 100;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("tarot_dev").defaultTimeoutMs(120000).scenarioTimeoutMs(300000);
    }

    private static ItemStack card(int i) {
        return new ItemStack(TarotCards.MAJOR.get(i).get());
    }

    /** 一面墙，上下两排各 11 张牌，玩家站在 4 格外朝北看。 */
    private static void stage(ServerLevel level) {
        for (int x = -10; x <= 10; x++) for (int z = -8; z <= 8; z++) {
            level.setBlockAndUpdate(new BlockPos(x, GROUND, z), Blocks.MOSS_BLOCK.defaultBlockState());
            for (int y = 1; y <= 8; y++) level.removeBlock(new BlockPos(x, GROUND + y, z), false);
        }
        for (int x = -7; x <= 7; x++) for (int y = 1; y <= 5; y++) {
            level.setBlockAndUpdate(new BlockPos(x, GROUND + y, -5), Blocks.DARK_OAK_PLANKS.defaultBlockState());
        }
        for (int i = 0; i < TarotCards.MAJOR.size(); i++) {
            var pos = new BlockPos(-5 + i % 11, GROUND + (i < 11 ? 4 : 2), -4);
            var frame = new ItemFrame(level, pos, Direction.SOUTH);
            frame.setItem(card(i));
            level.addFreshEntity(frame);
        }
    }

    private static void camera(CameraType type) {
        Minecraft.getInstance().options.setCameraType(type);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set noon").runCommand("weather clear").runCommand("difficulty peaceful")
            .step("固定视口", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.hideGui = false;
                mc.options.fov().set(70);
                camera(CameraType.FIRST_PERSON);
                var w = mc.getWindow();
                w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
                mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
            })
            .server("布置牌墙", ctx -> {
                var level = ctx.server().overworld();
                stage(level);
                var player = ctx.player();
                player.getInventory().clearContent();
                DimensionTravel.teleport(player, level, new Vec3(0.5, GROUND + 1, 1.5), 180f, -2f);
                for (int i = 0; i < 22; i++) player.getInventory().setItem(i, card(i));
                player.setItemInHand(InteractionHand.OFF_HAND, card(21));
            })
            .waitUntil("牌到达客户端", ctx -> Minecraft.getInstance().player.getMainHandItem().is(TarotCards.MAJOR.getFirst().get()))
            .check("创造模式页签列出全部大阿尔卡那", ctx -> {
                var mc = Minecraft.getInstance();
                CreativeModeTabs.tryRebuildTabContents(mc.player.connection.enabledFeatures(), true, mc.level.registryAccess());
                var tab = BuiltInRegistries.CREATIVE_MODE_TAB.getValue(LogosDimension.id("tarot"));
                return tab != null && tab.getDisplayItems().size() == 22;
            })
            .ticks(30).screenshot("tarot_wall_day")
            .step("看右手的牌", ctx -> Minecraft.getInstance().player.setXRot(35f)).ticks(5).screenshot("tarot_hand_day")
            .step("第三人称背面", ctx -> { Minecraft.getInstance().player.setXRot(10f); camera(CameraType.THIRD_PERSON_BACK); }).ticks(5).screenshot("tarot_third_back")
            .step("第三人称正面", ctx -> camera(CameraType.THIRD_PERSON_FRONT)).ticks(5).screenshot("tarot_third_front")
            .step("回第一人称", ctx -> camera(CameraType.FIRST_PERSON))
            .server("把牌丢在地上", ctx -> {
                var level = ctx.server().overworld();
                for (int i = 0; i < 3; i++) {
                    var drop = new ItemEntity(level, 0.5 + (i - 1) * 0.9, GROUND + 1.05, -0.4, card(i * 7), 0, 0, 0);
                    drop.setNeverPickUp();
                    drop.setUnlimitedLifetime();
                    level.addFreshEntity(drop);
                }
            })
            .step("低头看地面", ctx -> Minecraft.getInstance().player.setXRot(60f)).ticks(25).screenshot("tarot_ground")
            .setGameMode(GameType.SURVIVAL)
            .step("打开物品栏", ctx -> {
                var mc = Minecraft.getInstance();
                mc.player.setXRot(0f);
                mc.setScreen(new InventoryScreen(mc.player));
            })
            .ticks(5).screenshot("tarot_inventory").closeScreen()
            .runCommand("time set midnight")
            .ticks(20).screenshot("tarot_wall_night")
            .step("夜里看手上的牌", ctx -> Minecraft.getInstance().player.setXRot(35f)).ticks(5).screenshot("tarot_hand_night");
    }
}
