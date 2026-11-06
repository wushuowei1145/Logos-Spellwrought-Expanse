package dev.logos.dimension.tarot;

import dev.logos.dimension.LogosDimension;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 灵猫塔罗的二十二张大阿尔卡那。目前只是能拿在手里看的道具，占卜玩法还没定。
 * 牌面、模型和语言条目由 tools/tarot_art 生成，见 docs/TAROT.md。
 */
public final class TarotCards {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(LogosDimension.ID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, LogosDimension.ID);

    /** 牌序即注册 ID 的后缀，和贴图文件名一一对应。 */
    public static final List<String> MAJOR_ARCANA = List.of(
            "00_fool", "01_magician", "02_high_priestess", "03_empress", "04_emperor", "05_hierophant",
            "06_lovers", "07_chariot", "08_strength", "09_hermit", "10_wheel_of_fortune", "11_justice",
            "12_hanged_man", "13_death", "14_temperance", "15_devil", "16_tower", "17_star",
            "18_moon", "19_sun", "20_judgement", "21_world");

    public static final List<DeferredItem<Item>> MAJOR;

    static {
        var cards = new ArrayList<DeferredItem<Item>>();
        for (String id : MAJOR_ARCANA) cards.add(ITEMS.registerSimpleItem("tarot_" + id, p -> p.stacksTo(1)));
        MAJOR = List.copyOf(cards);
        TABS.register("tarot", () -> CreativeModeTab.builder().title(Component.translatable("itemGroup.logos_dimension.tarot"))
                .icon(() -> MAJOR.getFirst().get().getDefaultInstance())
                .displayItems((params, out) -> MAJOR.forEach(out::accept))
                .build());
    }

    private TarotCards() {}

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
        TABS.register(bus);
    }
}
