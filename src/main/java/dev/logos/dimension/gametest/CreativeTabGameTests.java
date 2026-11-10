package dev.logos.dimension.gametest;

import dev.logos.dimension.AddonTabs;
import dev.logos.dimension.LogosDimension;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 创造模式物品栏：附属的每件物品都在某一页里，几页的分类不串。 */
public final class CreativeTabGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);
    private static final String NAME = "creative_tabs_cover_items";

    public static void register(IEventBus bus) {
        FUNCTIONS.register(NAME, () -> CreativeTabGameTests::cover);
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent e) -> {
            var env = e.registerEnvironment(LogosDimension.id("creative_tabs_default"), new TestEnvironmentDefinition.AllOf());
            e.registerTest(LogosDimension.id(NAME), new FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(NAME)),
                    new TestData<>(env, Identifier.withDefaultNamespace("empty"), 100, 0, true, Rotation.NONE, false, 1, 1, false, 2)));
        });
    }

    private static void cover(GameTestHelper h) {
        var level = h.getLevel();
        var params = new CreativeModeTab.ItemDisplayParameters(level.enabledFeatures(), true, level.registryAccess());
        Set<Item> listed = new HashSet<>();
        for (var tab : AddonTabs.all()) {
            tab.get().buildContents(params);
            h.assertTrue(!tab.get().getDisplayItems().isEmpty(), "Empty creative tab " + tab.get().getDisplayName().getString());
            for (ItemStack stack : tab.get().getDisplayItems()) listed.add(stack.getItem());
        }
        var eggs = BuiltInRegistries.CREATIVE_MODE_TAB.getValueOrThrow(CreativeModeTabs.SPAWN_EGGS);
        eggs.buildContents(params);
        for (ItemStack stack : eggs.getDisplayItems()) listed.add(stack.getItem());

        List<String> missing = new ArrayList<>();
        for (var entry : BuiltInRegistries.ITEM.entrySet()) {
            var id = entry.getKey().identifier();
            if (!id.getNamespace().equals(LogosDimension.ID)) continue;
            String path = id.getPath();
            // 只为旧存档保留的稻子；写好的霊符要在符札书案上写，不放空壳
            if (path.equals("journey_rice") || path.endsWith("_inscribed_reifu")) continue;
            if (!listed.contains(entry.getValue())) missing.add(path);
        }
        h.assertTrue(missing.isEmpty(), "Not in any creative tab: " + missing);

        // 建材、装饰、功能方块页只放方块；武器页不放方块
        for (var tab : List.of(AddonTabs.BUILDING, AddonTabs.DECORATIONS, AddonTabs.FUNCTIONAL))
            for (ItemStack stack : tab.get().getDisplayItems())
                h.assertTrue(stack.getItem() instanceof BlockItem, "Non-block in " + tab.getId().getPath() + ": " + stack.getItem());
        for (ItemStack stack : AddonTabs.WEAPONS.get().getDisplayItems())
            h.assertTrue(!(stack.getItem() instanceof BlockItem), "Block in weapons: " + stack.getItem());
        h.succeed();
    }

    private CreativeTabGameTests() {}
}
