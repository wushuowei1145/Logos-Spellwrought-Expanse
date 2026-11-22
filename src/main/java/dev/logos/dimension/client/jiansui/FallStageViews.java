package dev.logos.dimension.client.jiansui;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.gametest.TaixuanGameTests;
import dev.logos.dimension.jiansui.FallStage;
import dev.logos.dimension.jiansui.JianSuiCommands;
import dev.logos.dimension.taixuan.TaixuanSite;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * 第三阶段「坠渊」的场景，没有 Boss（docs/JIAN_SUI_BOSS.md 第 17 节第 3 步）：在真实的太玄候选点摆好空洞里的场景，
 * 从几个机位截图，看外面那一层、只看的碎块、往上飞的东西、雾在各种画面环境下的样子。
 * 0 站在起点碎块上、手里拿着剑、平视洞壁；1 站在起点碎块上往上看顶块；2 洞壁前站着一个人（盔甲架替另一个玩家）；
 * 3 爬到 y -30 附近往外、往下看。
 */
public abstract class FallStageViews implements UIScenario {
    private static final String[] NAMES = {"jian_sui_fall_wall", "jian_sui_fall_up", "jian_sui_fall_other", "jian_sui_fall_high"};
    private TaixuanSite site;
    private Vec3 eye, look;
    protected int view() { return 0; }

    @Override public void configure(ScenarioOptions options) {
        options.tags("jian_sui").defaultTimeoutMs(480000).scenarioTimeoutMs(600000);
    }

    /** 主路第 {@code index} 块（0 是起点）脚站的位置。 */
    private Vec3 stone(int index) {
        var cavity = TaixuanSite.cavity();
        var s = index == 0 ? cavity.getAsJsonObject("start") : cavity.getAsJsonArray("route").get(index - 1).getAsJsonObject();
        var top = s.getAsJsonArray("top");
        return site.point(top.get(0).getAsDouble(), s.get("feet").getAsInt(), top.get(2).getAsDouble());
    }

    @Override public void define(ScenarioBuilder scenario) {
        scenario.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
            .step("设置机位", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.hideGui = view() != 0;
                mc.options.fov().set(70);
                mc.options.renderDistance().set(12);
                mc.options.broadcastOptions();
            })
            .server("拼装地标、摆出坠渊的场景", ctx -> {
                var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                var study = TaixuanGameTests.placeStudy(level);
                site = TaixuanSite.of(study.start());
                JianSuiCommands.clear(level, site);
                FallStage.spawn(level, site);
                Vec3 axis = site.axis(-40);
                switch (view()) {
                    case 1 -> { eye = stone(0); look = site.axis(-27); }
                    case 2 -> {
                        eye = stone(0);
                        Vec3 at = stone(1);
                        var stand = new ArmorStand(EntityType.ARMOR_STAND, level);
                        stand.snapTo(at.x, at.y, at.z, 0, 0);
                        stand.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                        stand.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
                        stand.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
                        stand.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.LEATHER_BOOTS));
                        stand.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
                        level.addFreshEntity(stand);
                        // 看向那个人，再往外一点，让洞壁在他身后
                        Vec3 out = at.subtract(axis).multiply(1, 0, 1).normalize();
                        look = at.add(out.scale(6)).add(0, 1.2, 0);
                    }
                    case 3 -> { eye = stone(23); Vec3 out = eye.subtract(axis).multiply(1, 0, 1).normalize(); look = eye.add(out.scale(20)).add(0, -9, 0); }
                    default -> { eye = stone(0); Vec3 out = eye.subtract(axis).multiply(1, 0, 1).normalize(); look = eye.add(out.scale(20)).add(0, 1.62, 0); }
                }
                var player = ctx.player();
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
                var direction = look.subtract(eye.add(0, player.getEyeHeight(), 0));
                float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
                float pitch = (float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z)));
                DimensionTravel.teleport(player, level, eye, yaw, pitch);
                player.getAbilities().flying = false;player.onUpdateAbilities();
                System.out.println("JIAN_SUI_FALL view=" + NAMES[view()] + " anchor=" + site.anchor() + " rotation=" + site.rotation() + " eye=" + eye);
            })
            .waitUntil("场景和空洞的区块到了客户端", ctx -> {
                var mc = Minecraft.getInstance();
                return site != null && mc.level != null && mc.level.dimension().equals(LogosDimension.CLOUDSEA)
                    && FallStageClient.stage() != null && mc.level.hasChunkAt(BlockPos.containing(look));
            }).ticks(400)
            .step("对准", ctx -> {
                var player = Minecraft.getInstance().player;var direction = look.subtract(player.getEyePosition());
                player.setYRot((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)));
                player.setXRot((float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z))));
            }).ticks(80)
            .check("站在碎块上，没有掉下去", ctx -> Minecraft.getInstance().player.getY() > eye.y - .5)
            .check("镜头在场景里", ctx -> FallStageClient.inStage())
            .screenshot(NAMES[view()])
            // 往上流动看不出来静态截图，平视那个机位再隔两刻连拍几张，拼成动图看
            .step("连拍", ctx -> {}).ticks(view() == 0 ? 2 : 0).screenshot(NAMES[view()] + "_b")
            .step("连拍", ctx -> {}).ticks(view() == 0 ? 2 : 0).screenshot(NAMES[view()] + "_c")
            .step("连拍", ctx -> {}).ticks(view() == 0 ? 2 : 0).screenshot(NAMES[view()] + "_d")
            .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
    }

    @LDLRegisterClient(name="jian_sui_fall_wall", group="jian_sui", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Wall extends FallStageViews {}
    @LDLRegisterClient(name="jian_sui_fall_up", group="jian_sui", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Up extends FallStageViews { @Override protected int view() { return 1; } }
    @LDLRegisterClient(name="jian_sui_fall_other", group="jian_sui", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Other extends FallStageViews { @Override protected int view() { return 2; } }
    @LDLRegisterClient(name="jian_sui_fall_high", group="jian_sui", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class High extends FallStageViews { @Override protected int view() { return 3; } }
}
