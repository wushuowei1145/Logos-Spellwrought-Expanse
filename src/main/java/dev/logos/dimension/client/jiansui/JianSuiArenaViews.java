package dev.logos.dimension.client.jiansui;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.gametest.TaixuanGameTests;
import dev.logos.dimension.jiansui.JianSui;
import dev.logos.dimension.jiansui.JianSuiBlocks;
import dev.logos.dimension.jiansui.JianSuiCue;
import dev.logos.dimension.jiansui.JianSuiFight;
import dev.logos.dimension.jiansui.JianSuiFight.State;
import dev.logos.dimension.taixuan.TaixuanSite;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * 剑祟这一战在真实太玄灵境上的几个画面（docs/JIAN_SUI_BOSS.md 第 18 节）：
 * 0 雾底石台上的残骸；1 登场完、标题「剑祟」；2 裂地，发光的裂缝爬满石台；3 第三阶段站在起点碎块上，巨剑的光线瞄过来；
 * 4 躲开以后巨剑扎在碎块上；5 打完，遗剑插在插剑的地方、一圈裂纹瓷砖。每个场景开始和结束都重置这一处的剑祟。
 */
public abstract class JianSuiArenaViews implements UIScenario {
    private static final String[] NAMES = {"jian_sui_remains", "jian_sui_title", "jian_sui_cracks", "jian_sui_giant_aim", "jian_sui_giant_stuck", "jian_sui_relic"};
    private TaixuanSite site;
    private ServerLevel level;
    private Vec3 feet, look, aimed;
    protected int view() { return 0; }

    @Override public void configure(ScenarioOptions options) {
        options.tags("jian_sui").defaultTimeoutMs(480000).scenarioTimeoutMs(600000);
    }

    /**
     * 开始以后先等多久（刻）。每一步之间还有约 60 毫秒的间隔，按刻数掐时间不准：标题、巨剑瞄准都是先等到差不多，
     * 再按画面自己的状态等（标题出来了停住、光线开始聚）。
     */
    private int settleTicks() {
        return switch (view()) {
            case 1 -> JianSuiFight.TITLE_AT - 20;
            case 2 -> 20;
            case 3 -> 0;
            case 4 -> 70;
            default -> 40;
        };
    }

    private void aim(net.minecraft.world.entity.player.Player player) {
        var direction = look.subtract(player.getEyePosition());
        player.setYRot((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)));
        player.setXRot((float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z))));
    }

    @Override public void define(ScenarioBuilder scenario) {
        scenario.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful").runCommand("time set noon").runCommand("weather clear")
            .step("设置机位", ctx -> {
                var mc = Minecraft.getInstance();
                // 上一个场景留下的巨剑、裂缝、标题不带过来（同一维度里换场景，客户端世界不换）
                JianSuiCueClient.reset();
                mc.options.hideGui = view() != 1;
                mc.options.fov().set(70);
                mc.options.renderDistance().set(12);
                mc.options.broadcastOptions();
            })
            .server("拼装地标、重置剑祟、站到石台边", ctx -> {
                level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                site = TaixuanSite.of(TaixuanGameTests.placeStudy(level).start());
                JianSuiFight.reset(level, site);
                Vec3 plunge = JianSuiFight.plunge(site);
                Vec3 out = plunge.subtract(site.axis(plunge.y)).multiply(1, 0, 1).normalize();
                Vec3 side = new Vec3(-out.z, 0, out.x);
                switch (view()) {
                    case 0 -> { feet = plunge.add(out.scale(3.4)).add(side.scale(1.4)).add(0, .6, 0); look = plunge.add(0, .5, 0); }
                    case 1 -> { feet = plunge.add(out.scale(7)).add(0, .3, 0); look = plunge.add(0, 2.4, 0); }
                    case 2 -> { feet = plunge.add(out.scale(9)).add(side.scale(3)).add(0, 8, 0); look = plunge.add(out.scale(-3)); }
                    case 5 -> { feet = plunge.add(out.scale(2.4)).add(side.scale(1.1)).add(0, .1, 0); look = Vec3.atBottomCenterOf(JianSuiFight.relicPos(site)).add(0, .5, 0); }
                    default -> { feet = plunge.add(out.scale(5)).add(0, .3, 0); look = plunge.add(0, 1, 0); }
                }
                var player = ctx.player();
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
                DimensionTravel.teleport(player, level, feet, 0, 0);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                System.out.println("JIAN_SUI_ARENA view=" + NAMES[view()] + " anchor=" + site.anchor() + " rotation=" + site.rotation() + " plunge=" + plunge);
            })
            .waitUntil("石台的区块到了客户端", ctx -> {
                var mc = Minecraft.getInstance();
                if (site == null || mc.level == null || !mc.level.dimension().equals(LogosDimension.CLOUDSEA)) return false;
                // 客户端世界的 hasChunkAt 总是真，要问区块缓存
                BlockPos at = BlockPos.containing(look);
                return mc.level.getChunkSource().hasChunk(at.getX() >> 4, at.getZ() >> 4);
            }).ticks(40)
            .server("开始", ctx -> {
                JianSuiFight.open(level, site);
                var player = ctx.player();
                switch (view()) {
                    case 1 -> JianSuiFight.skip(level, site, State.ENTRANCE);
                    case 2 -> JianSuiFight.skip(level, site, State.RIFT);
                    case 3, 4 -> {
                        JianSuiFight.skip(level, site, State.FALL);
                        // 生存模式才会被巨剑瞄准；和平难度不掉血
                        player.setGameMode(GameType.SURVIVAL);
                        player.getAbilities().flying = false;
                        player.onUpdateAbilities();
                        look = JianSuiFight.top(site).add(0, 2.2, 0);
                    }
                    case 5 -> JianSuiFight.skip(level, site, State.DEFEATED);
                    default -> { }
                }
            })
            .ticks(view() == 4 ? 24 : 0)
            .server("躲开巨剑", ctx -> {
                if (view() != 4) return;
                // 光线瞄准时就定死了：往旁边挪两格，剑扎在刚才站的地方
                var player = ctx.player();
                aimed = player.position();
                Vec3 dodge = site.point(135.5, TaixuanSite.cavity().getAsJsonObject("start").get("feet").getAsInt(), 124.5);
                player.teleportTo(level, dodge.x, dodge.y, dodge.z, java.util.Set.of(), player.getYRot(), 0, true);
                Vec3 in = site.axis(aimed.y).subtract(aimed).multiply(1, 0, 1).normalize();
                look = aimed.add(in.scale(1.2)).add(0, 1.6, 0);
            })
            .ticks(settleTicks())
            // 标题：出来了就停在淡入完、还没淡出的那一刻；巨剑瞄准：等光线开始聚
            .waitUntil("标题出来", ctx -> view() != 1 || JianSuiCueClient.count(JianSuiCue.TITLE) == 1)
            .step("标题停住", ctx -> { if (view() == 1) JianSuiCueClient.holdTitle((JianSuiCueClient.IN + JianSuiCueClient.OUT_AT) / 2); })
            .waitUntil("巨剑起手", ctx -> view() != 3 || JianSuiCueClient.aiming() >= 1)
            .step("对准", ctx -> aim(Minecraft.getInstance().player)).ticks(view() == 3 ? 4 : 10)
            // 裂缝：等它爬满再截，白屏停在还没盖上的样子（步骤之间的间隔不准，按裂缝自己的刻数等）
            .waitUntil("裂缝爬满", ctx -> view() != 2 || JianSuiCueClient.crackAge() >= 50)
            .step("白屏先不盖", ctx -> { if (view() == 2) dev.logos.dimension.client.screenfx.ScreenCoverRenderer.hold(0); })
            .check("画面里该有的东西都在", ctx -> {
                var mc = Minecraft.getInstance();
                return switch (view()) {
                    case 0 -> !mc.level.getEntitiesOfClass(JianSui.class, mc.player.getBoundingBox().inflate(16)).isEmpty();
                    case 1 -> JianSuiCueClient.count(JianSuiCue.TITLE) == 1;
                    case 2 -> JianSuiCueClient.count(JianSuiCue.CRACKS) >= 1;
                    case 3 -> JianSuiCueClient.aiming() + JianSuiCueClient.flying() >= 1 && FallStageClient.inStage();
                    case 4 -> JianSuiCueClient.stuck() >= 1 && FallStageClient.inStage();
                    case 5 -> mc.level.getBlockState(JianSuiFight.relicPos(site)).is(JianSuiBlocks.AXIAL_SWORD.get());
                    default -> true;
                };
            })
            .screenshot(NAMES[view()])
            .server("收拾", ctx -> {
                JianSuiFight.reset(level, site);
                ctx.player().setGameMode(GameType.CREATIVE);
            })
            .teardown("恢复界面", ctx -> {
                Minecraft.getInstance().options.hideGui = false;
                dev.logos.dimension.client.screenfx.ScreenCoverRenderer.hold(-1);
                JianSuiCueClient.holdTitle(-1);
            });
    }

    @LDLRegisterClient(name="jian_sui_remains", group="jian_sui_arena", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Remains extends JianSuiArenaViews {}
    @LDLRegisterClient(name="jian_sui_title", group="jian_sui_arena", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Title extends JianSuiArenaViews { @Override protected int view() { return 1; } }
    @LDLRegisterClient(name="jian_sui_cracks", group="jian_sui_arena", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Cracks extends JianSuiArenaViews { @Override protected int view() { return 2; } }
    @LDLRegisterClient(name="jian_sui_giant_aim", group="jian_sui_arena", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class GiantAim extends JianSuiArenaViews { @Override protected int view() { return 3; } }
    @LDLRegisterClient(name="jian_sui_giant_stuck", group="jian_sui_arena", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class GiantStuck extends JianSuiArenaViews { @Override protected int view() { return 4; } }
    @LDLRegisterClient(name="jian_sui_relic", group="jian_sui_arena", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Relic extends JianSuiArenaViews { @Override protected int view() { return 5; } }
}
