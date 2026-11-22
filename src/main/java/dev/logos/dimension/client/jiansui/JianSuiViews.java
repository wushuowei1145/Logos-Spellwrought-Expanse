package dev.logos.dimension.client.jiansui;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.jiansui.JianSui;
import dev.logos.dimension.jiansui.JianSuiEntities;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 剑祟本体的样子（docs/JIAN_SUI_BOSS.md）：在出生点旁边铺一块石台，放一只不自己出招的剑祟，从几个机位截图：
 * 0 正面全身（手中古剑画在 Weapon 骨骼上）；1 脸的特写，人站在它的右前方，竖瞳应该往那边看、头不转；
 * 2 出踏风，截在出手前双眼闪琥珀光的那一刻。
 */
public abstract class JianSuiViews implements UIScenario {
    private static final String[] NAMES = {"jian_sui_front", "jian_sui_eyes", "jian_sui_flash"};
    private JianSui boss;
    private Vec3 eye, look;
    protected int view() { return 0; }

    @Override public void configure(ScenarioOptions options) {
        options.tags("jian_sui").defaultTimeoutMs(240000).scenarioTimeoutMs(300000);
    }

    @Override public void define(ScenarioBuilder scenario) {
        scenario.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful").runCommand("time set noon").runCommand("weather clear")
            .step("设置机位", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.hideGui = true;
                mc.options.fov().set(70);
                mc.options.broadcastOptions();
            })
            .server("铺石台、放剑祟", ctx -> {
                var player = ctx.player();
                ServerLevel level = (ServerLevel) player.level();
                BlockPos base = player.blockPosition().offset(0, 20, 0);
                for (int x = -8; x <= 8; x++) for (int z = -4; z <= 14; z++) {
                    level.setBlock(base.offset(x, -1, z), Blocks.SMOOTH_STONE.defaultBlockState(), 3);
                    for (int y = 0; y < 8; y++) level.setBlock(base.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
                Vec3 at = Vec3.atBottomCenterOf(base.offset(0, 0, 7));
                boss = JianSuiEntities.JIAN_SUI.get().create(level, EntitySpawnReason.COMMAND);
                boss.snapTo(at.x, at.y, at.z, 180, 0);
                boss.setYBodyRot(180);
                boss.setYHeadRot(180);
                level.addFreshEntity(boss);
                boss.setScripted(true);
                boss.lookAt(player);
                Vec3 face = at.add(0, 3.3, 0);
                switch (view()) {
                    case 1 -> { eye = at.add(1.6, 2.0, -2.6); look = face; }
                    default -> { eye = at.add(0, 0, -6.5); look = at.add(0, 2.0, 0); }
                }
                player.teleportTo(level, eye.x, eye.y, eye.z, java.util.Set.of(), 0, 0, true);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
            })
            .ticks(40)
            .step("对准", ctx -> {
                var player = Minecraft.getInstance().player;var direction = look.subtract(player.getEyePosition());
                player.setYRot((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)));
                player.setXRot((float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z))));
            }).ticks(20)
            .server("出招", ctx -> { if (view() == 2) boss.play("tafeng"); })
            .ticks(view() == 2 ? 12 : 2)
            .check("剑祟在客户端", ctx -> Minecraft.getInstance().level.getEntitiesOfClass(JianSui.class, Minecraft.getInstance().player.getBoundingBox().inflate(16)).size() == 1)
            .screenshot(NAMES[view()])
            .server("收掉", ctx -> boss.discard())
            .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
    }

    @LDLRegisterClient(name="jian_sui_front", group="jian_sui_boss", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Front extends JianSuiViews {}
    @LDLRegisterClient(name="jian_sui_eyes", group="jian_sui_boss", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Eyes extends JianSuiViews { @Override protected int view() { return 1; } }
    @LDLRegisterClient(name="jian_sui_flash", group="jian_sui_boss", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Flash extends JianSuiViews { @Override protected int view() { return 2; } }
}
