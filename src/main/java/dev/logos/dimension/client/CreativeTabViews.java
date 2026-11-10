package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.dimension.AddonTabs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.GameType;

/** 附属的每一页创造模式物品栏依次打开截图，用来人工看分类和排列。物品是否齐全由世界测试 {@code creative_tabs_cover_items} 查。 */
@LDLRegisterClient(name = "creative_tab_views", group = "creative_tabs", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class CreativeTabViews implements UIScenario {
    @Override
    public void configure(ScenarioOptions o) {
        o.tags("creative_tabs").guiScale(3);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE)
                .ticks(5)
                .step("打开创造模式物品栏", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.setScreen(new CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(), false));
                })
                .awaitScreen(CreativeModeInventoryScreen.class);
        for (var tab : AddonTabs.all()) {
            s.step("切到 " + tab.getId().getPath(), ctx -> {
                        CreativeModeTab t = tab.get();
                        if (t.getDisplayItems().isEmpty()) throw new AssertionError("空页签 " + t.getDisplayName().getString());
                        try {
                            var select = CreativeModeInventoryScreen.class.getDeclaredMethod("selectTab", CreativeModeTab.class);
                            select.setAccessible(true);
                            select.invoke(ctx.screen(), t);
                        } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .ticks(3)
                    .screenshot("creative_tab_" + tab.getId().getPath());
        }
        s.step("关闭", ctx -> Minecraft.getInstance().setScreen(null));
    }
}
