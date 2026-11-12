package dev.logos.dimension;

import com.mojang.logging.LogUtils;
import dev.logos.dimension.cloudsea.CloudseaBiome;
import dev.logos.dimension.cloudsea.CloudseaBlocks;
import dev.logos.dimension.economy.Economy;
import dev.logos.dimension.reifu.Reifu;
import dev.logos.dimension.tarot.TarotCards;
import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

/**
 * 附属的全部创造模式物品栏，按原版的分法排：建材、装饰、自然、功能方块、道具、武器，之后是货币、塔罗和炼金釜。
 *
 * <p>每页里同一套风格的东西挨在一起（云海、远行村落、精灵、和风），同类武器挨在一起。新增物品时加进对应的一页；
 * 世界测试 {@code creative_tabs_cover_items} 会找出没进任何一页的物品。故意不放的只有三种：
 * 只为旧存档保留的 {@code journey_rice}、写好的霊符（要在符札书案上写）、刷怪蛋（在原版刷怪蛋页）。
 */
public final class AddonTabs {
    private static final Logger LOG = LogUtils.getLogger();
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, LogosDimension.ID);

    /** 建材：整块、台阶、楼梯、门窗，按风格成组。 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> BUILDING = tab("building", null, "journey_kawara_roof", out -> ids(out,
            // 云海
            "cloudsea_ivory_bricks", "cloudsea_blue_slate", "cloudsea_dark_timber", "cloudsea_lit_window",
            // 远行村落
            "journey_limestone", "journey_lime_plaster", "journey_timber_frame", "journey_roof_tile",
            // 精灵林地
            "elven_heartwood", "elven_pale_planks", "elven_luminous_inlay",
            // 和风：朱漆木、灰泥与腰墙、陶瓦、榻榻米、障子、踏步石
            "journey_lacquer_log", "journey_lacquer_planks", "journey_lacquer_stairs", "journey_lacquer_slab",
            "journey_lacquer_fence", "journey_lacquer_fence_gate",
            "journey_white_plaster", "journey_wood_wainscot",
            "journey_kawara_roof", "journey_kawara_stairs", "journey_kawara_slab",
            "journey_tatami", "journey_tatami_slab",
            "journey_shoji_door", "journey_shoji_window",
            "journey_stepping_stone",
            // 太玄：花窗、云纹砖雕、黄铜筒瓦、黄铜、玄铁
            "taixuan_lattice_window", "taixuan_carved_brick",
            "taixuan_brass_tiles", "taixuan_brass_tile_stairs", "taixuan_brass_ridge_slab", "taixuan_brass_block",
            "taixuan_dark_iron"));

    /** 装饰：家具、灯、布帘与绳结、石像，没有交互。 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> DECORATIONS = tab("decorations", BUILDING, "journey_stone_lantern", out -> ids(out,
            // 精灵家具与灯
            "elven_leaf_seat", "elven_reading_table", "elven_bookshelf", "elven_flower_planter",
            "elven_leaf_lamp", "elven_bough_light", "elven_lamp_vine", "elven_climbing_rope",
            // 和风灯笼
            "journey_paper_lantern", "journey_stone_lantern", "journey_stone_lantern_tall",
            // 暖帘、注连绳、菜单条、御札
            "journey_noren", "journey_noren_red", "journey_noren_white", "journey_noren_brown",
            "journey_shimenawa", "journey_menu_strips", "journey_ofuda",
            // 石像、石龛、竹出水口、封印杭
            "journey_fox_statue", "journey_shrine_hokora", "journey_bamboo_spout", "journey_seal_stake",
            // 太玄深渊的雾
            "taixuan_abyss_mist"));

    /** 自然：远行大陆的地形石、各群系的地面、原木和树叶、花草蘑菇与水草。 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> NATURE = tab("nature", DECORATIONS, "cloudsea_white_cherry_leaf", out -> {
        ids(out, "cloudsea_porcelain", "cloudsea_mantle", "cloudsea_demonstone");
        for (var b : CloudseaBiome.values()) out.accept(CloudseaBlocks.GROUND.get(b).get());
        ids(out, "cloudsea_stem");
        for (var b : CloudseaBiome.values()) out.accept(CloudseaBlocks.TRUNKS.get(b).get());
        for (var b : CloudseaBiome.values()) out.accept(CloudseaBlocks.LEAVES.get(b).get());
        ids(out, "elven_canopy",
                // 云海植物
                "cloudsea_lantern_bud", "cloudsea_hanging_root", "cloudsea_eye_fern", "cloudsea_demon_reed",
                // 林地与原野的草和花
                "journey_woodland_fern", "journey_bracken", "journey_lumen_fern", "journey_meadow_grass", "journey_meadow_grass_young",
                "journey_river_reed", "journey_bluebell", "journey_foxglove", "journey_lupine", "journey_stardew",
                // 四季植物
                "journey_silver_anemone", "journey_moonbell", "journey_glowcap", "journey_heather",
                "journey_amber_fern", "journey_winter_hellebore", "journey_fireweed",
                // 蘑菇：前三种能吃，后三种有毒
                "journey_field_mushroom", "journey_chanterelle", "journey_bolete",
                "journey_fly_agaric", "journey_death_cap", "journey_false_chanterelle",
                // 浆果与葡萄
                "journey_berry_bramble", "journey_grapevine", "journey_grapes", "journey_grape_leaf",
                // 地面落瓣与落叶、水草
                "journey_white_petals", "journey_fallen_leaves",
                "journey_seagrass", "journey_glow_seagrass", "journey_kelp");
    });

    /** 功能方块：右键或注魔会做事的方块。柜台也留在货币页，炼金釜也留在炼金釜页。 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> FUNCTIONAL = tab("functional", NATURE, "gate_stone", out -> ids(out,
            // 门、书案、石中剑
            "gate_stone", "fuda_desk", "dawnvow_stone", "dawnvow_stone_empty",
            // 神社：绘马挂、签箱、结签架、赛钱箱、鈴緒、手水钵，温泉地热石
            "journey_ema_rack", "journey_omikuji_box", "journey_omikuji_rack", "journey_offering_box",
            "journey_suzu_rope", "journey_stone_basin", "journey_hot_spring_stone",
            // 兑换台、钱庄、炼金釜
            "exchange_counter", "bank_counter", "alchemical_cauldron",
            // 魔女之森的乐器
            "starbloom_grand_piano", "hexen_concert_harp",
            "hexen_organ_console", "organ_wind_chest", "organ_tin_pipe", "organ_wood_pipe", "organ_trumpet_pipe",
            "hexen_harpsichord", "hexen_celesta", "hexen_glockenspiel", "hexen_tubular_bells",
            "hexen_cello", "hexen_double_bass", "hexen_timpani",
            "hexen_music_stand", "hexen_podium", "starbloom_bench",
            // 运镜的测试方块
            "camera_test_block"));

    /** 道具：画具、绘马与签纸、空白霊符、白梅的形代、空白纸带。 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TOOLS = tab("tools", FUNCTIONAL, "paint_brush", out -> {
        ids(out, "paint_brush", "paint_palette", "journey_ema", "journey_omikuji", "shiraume_katashiro", "blank_music_roll",
            "hexen_violin", "hexen_viola", "hexen_flute", "hexen_oboe", "hexen_bassoon", "hexen_trumpet", "hexen_baton");
        Reifu.REIFU.values().forEach(r -> out.accept(r.blank().get()));
    });

    /** 武器：剑、刀、长柄、弓、盾、法杖，同类挨在一起，同一把的几种配色挨在一起。 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> WEAPONS = tab("weapons", TOOLS, "dawnvow", out -> ids(out,
            // 剑
            "dawnvow", "blacksteel_longsword", "lingxiao_sword", "jinghong_sword",
            // 刀
            "fox_uchigatana_ink", "fox_uchigatana_vermilion", "fox_uchigatana_indigo", "fox_uchigatana_wood", "ryuka",
            // 长柄
            "elf_spear", "pack_oath_spear",
            // 弓
            "natures_force", "fox_yumi_bamboo", "fox_yumi_vermilion", "fox_yumi_indigo", "fox_yumi_chestnut",
            // 盾
            "ancient_embrace",
            // 法杖
            "explosion_staff"));

    /** 异世界货币：钱囊、兑换台、钱庄与四国钱币。 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> ECONOMY = tab("economy", WEAPONS, "fox_ichibu", Economy::creativeItems);

    /** 灵猫塔罗的二十二张大阿尔卡那。 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAROT = tab("tarot", ECONOMY, "tarot_00_fool",
            out -> TarotCards.MAJOR.forEach(out::accept));

    /** 炼金釜维度的材料、液体桶与腐败。 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CRUCIBLE = tab("crucible", TAROT, "salt_crystal", out -> {
        DimensionBlocks.PALETTE.forEach(b -> out.accept(b.get()));
        ids(out, "mercury_bucket", "molten_glass_bucket", "corruption_core", "corruption", "corruption_vein");
    });

    private AddonTabs() {}

    public static void register(IEventBus bus) {
        TABS.register(bus);
    }

    /**
     * 炼金釜页的标题沿用最早的 {@code itemGroup.logos_dimension}，其余是 {@code itemGroup.logos_dimension.<名字>}。
     * {@code after} 为空的是附属的第一页；其余排在上一页之后，几页总挨在一起。
     */
    private static DeferredHolder<CreativeModeTab, CreativeModeTab> tab(String name, DeferredHolder<CreativeModeTab, CreativeModeTab> after,
                                                                       String icon, Consumer<CreativeModeTab.Output> items) {
        return TABS.register(name, () -> {
            var builder = CreativeModeTab.builder()
                    .title(Component.translatable(name.equals("crucible") ? "itemGroup." + LogosDimension.ID : "itemGroup." + LogosDimension.ID + "." + name))
                    .icon(() -> new ItemStack(item(icon)))
                    .displayItems((params, out) -> items.accept(out));
            if (after != null) builder.withTabsBefore(after.getKey());
            return builder.build();
        });
    }

    private static void ids(CreativeModeTab.Output out, String... ids) {
        for (String id : ids) {
            ItemLike item = item(id);
            if (item != null) out.accept(item);
        }
    }

    /** 按 ID 取附属物品；写错的 ID 只记日志不崩，世界测试会因为那件物品不在任何一页而失败。 */
    private static ItemLike item(String id) {
        var key = LogosDimension.id(id);
        if (!BuiltInRegistries.ITEM.containsKey(key)) {
            LOG.error("Creative tab lists an unknown item {}", key);
            return null;
        }
        return BuiltInRegistries.ITEM.getValue(key);
    }

    /** 测试用：附属的几页，按显示顺序。 */
    public static java.util.List<DeferredHolder<CreativeModeTab, CreativeModeTab>> all() {
        return java.util.List.of(BUILDING, DECORATIONS, NATURE, FUNCTIONAL, TOOLS, WEAPONS, ECONOMY, TAROT, CRUCIBLE);
    }
}
