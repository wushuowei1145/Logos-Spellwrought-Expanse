package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.cloudsea.Omikuji;
import dev.logos.dimension.luck.LuckKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.CompletableFuture;

/** 抽签的客户端场景。 */
public abstract class OmikujiViews implements UIScenario {
    @Override
    public void configure(ScenarioOptions o) {
        o.tags("fox_dev").defaultTimeoutMs(120000).scenarioTimeoutMs(180000);
    }

    /** 下界没有时钟，抽签跟主世界的日子走，而不是永远停在第 0 天。放在客户端场景里，因为世界测试的服务器不加载下界。 */
    @LDLRegisterClient(name = "omikuji_clockless", group = "fox_dev", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Clockless extends OmikujiViews {
        @Override
        public void define(ScenarioBuilder s) {
            s.setGameMode(GameType.CREATIVE)
                .server("draw in the nether, then let an overworld day pass", ctx -> {
                    var nether = ctx.server().getLevel(Level.NETHER);
                    ctx.check("the nether has no clock", nether.dimensionType().defaultClock().isEmpty());
                    DimensionTravel.teleport(ctx.player(), nether, new Vec3(0.5, 120, 0.5), 0, 0);
                    ctx.check("not drawn yet", !Omikuji.drawnToday(ctx.player()));
                    Omikuji.draw(ctx.player(), nether.getRandom());
                    ctx.check("drawn today", Omikuji.drawnToday(ctx.player()));
                    var overworld = nether.registryAccess().getOrThrow(WorldClocks.OVERWORLD);
                    ctx.server().clockManager().addTicks(overworld, 24000);
                    ctx.check("an overworld day later the nether has a new day too", !Omikuji.drawnToday(ctx.player()));
                })
                .teardownServer("back to the overworld", ctx ->
                    DimensionTravel.teleport(ctx.player(), ctx.server().overworld(), new Vec3(0.5, 200, 0.5), 0, 0));
        }
    }

    /** 看运势的样子：屏幕右上角的图标、物品栏里的效果名，以及签纸上的签文与签项。 */
    @LDLRegisterClient(name = "omikuji_fortune_views", group = "fox_dev", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Fortunes extends OmikujiViews {
        /** 打开物品栏，把鼠标放到第一个快捷栏格子（签纸放在那里）上。 */
        private static ScenarioBuilder inspectPaper(ScenarioBuilder s, String shot) {
            var window = Minecraft.getInstance().getWindow();
            float x = window.getGuiScaledWidth() / 2f - 88 + 8 + 8;
            float y = window.getGuiScaledHeight() / 2f - 83 + 142 + 8;
            return s.step("open inventory", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.setScreen(new InventoryScreen(mc.player));
                })
                .ticks(5)
                .hoverAt(x, y)
                .ticks(3)
                .screenshot(shot)
                .closeScreen();
        }

        @Override
        public void define(ScenarioBuilder s) {
            s.setGameMode(GameType.SURVIVAL)
                .runCommand("difficulty peaceful")
                .runCommand("time set day")
                .server("draw great fortune", ctx -> {
                    ctx.player().getInventory().clearContent();
                    ctx.player().getInventory().add(Omikuji.draw(ctx.player(), Omikuji.Fortune.DAIKICHI, ctx.server().overworld().getRandom()));
                })
                .ticks(10)
                .screenshot("omikuji_daikichi_hud");
            inspectPaper(s, "omikuji_daikichi_paper")
                .server("draw great misfortune", ctx -> {
                    ctx.player().getInventory().clearContent();
                    ctx.player().getInventory().add(Omikuji.draw(ctx.player(), Omikuji.Fortune.DAIKYO, ctx.server().overworld().getRandom()));
                })
                .ticks(10)
                .screenshot("omikuji_daikyo_hud");
            inspectPaper(s, "omikuji_daikyo_paper")
                .server("show every icon", ctx -> {
                    // 一次挂上全部图标，核对每张贴图都进了图集（缺图会画成紫黑块）
                    for (LuckKind kind : LuckKind.values()) {
                        for (String side : kind.hasBad ? new String[] {"luck", "misfortune"} : new String[] {"luck"}) {
                            var effect = BuiltInRegistries.MOB_EFFECT.get(LogosDimension.id(kind.id + "_" + side)).orElseThrow();
                            ctx.player().addEffect(new MobEffectInstance(effect, 2000, 0, false, false, true));
                        }
                    }
                })
                .ticks(5)
                .screenshot("omikuji_all_icons")
                .teardownServer("clear", ctx -> ctx.player().removeAllEffects());
        }
    }

    /** 同样的截图，切成中文看：测试客户端默认是英文，中文的提示框宽度和字形要实际看一眼。 */
    @LDLRegisterClient(name = "omikuji_fortune_views_zh", group = "fox_dev", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class FortunesChinese extends Fortunes {
        private CompletableFuture<Void> reload = CompletableFuture.completedFuture(null);

        private void language(String code) {
            var mc = Minecraft.getInstance();
            mc.getLanguageManager().setSelected(code);
            mc.options.languageCode = code;
            reload = mc.reloadResourcePacks();
        }

        @Override
        public void define(ScenarioBuilder s) {
            s.step("switch to Chinese", ctx -> language("zh_cn"))
                .waitUntil("resources reloaded", ctx -> reload.isDone());
            super.define(s);
            s.teardown("back to English", ctx -> language("en_us"));
        }
    }
}
