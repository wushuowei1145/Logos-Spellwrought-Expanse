package dev.logos.dimension.client.companion;

import dev.logos.dimension.client.cutin.CutinMotes;
import dev.logos.dimension.client.cutin.CutinShot;
import dev.logos.dimension.client.ultimate.StandInShow;
import dev.logos.dimension.client.ultimate.UltimateFilm;
import dev.logos.dimension.client.ultimate.UltimateShow;
import dev.logos.dimension.companion.CompanionOutfit;
import dev.logos.dimension.companion.CompanionSheet;
import dev.logos.dimension.companion.Companions;
import dev.logos.dimension.companion.KohakuOutfit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * 同伴对话和角色面板的样子（docs/SHIRAUME_COMPANION.md 第 8.13 节）。界面是通用的（{@link CompanionTalkScreen}、{@link CompanionPanelScreen}），
 * 每位同伴只给自己的配色、纹、面板里展示的姿势和角度；文字（名字、称号、话题、能力、往事）在语言文件里按同伴的 ID 找。
 *
 * @param accent     主色（白梅：她独一份的淡粉狐火）
 * @param accentDeep 深一点的主色（描边、选中）
 * @param gold       金线（分隔、打勾、特殊能力）
 * @param cool       冷色（被动能力）
 * @param ink        正文
 * @param dim        次要文字
 * @param night0     面板底色（上）
 * @param night1     面板底色（下）
 * @param card       卡片底
 * @param crest      她的纹：名字旁一个小的，面板里身后一个大的
 * @param showcase   面板里展示的她
 * @param cutin      连携切入里的她
 * @param ultimate   她的奥义在客户端的画面（docs/ULTIMATES.md「画面」）；服务端那一半在她的 {@code CompanionDef#ultimate}
 */
public record CompanionStyle(int accent, int accentDeep, int gold, int cool, int ink, int dim, int night0, int night1, int card, Crest crest,
                             Showcase showcase, Cutin cutin, UltimateShow.Factory ultimate) {
    /** 画纹：圆心、半径（像素）、颜色。 */
    public interface Crest {
        void draw(GuiGraphicsExtractor g, float cx, float cy, float r, int color);
    }

    /**
     * 面板里展示的她：{@code make} 造一个不放进世界的实体、摆好姿势（每次打开面板造一次）；{@code yaw} 身子朝哪边侧（度，正数是往她自己的左边转），
     * 头转回来多少成看着你（{@code look}）；{@code pitch} 镜头俯仰；{@code height} 她占展示框高度的几成；{@code lift} 往上挪多少（格）。
     */
    public record Showcase(BiFunction<Level, CompanionSheet, @Nullable LivingEntity> make, float yaw, float look, float pitch, float height,
                           float lift) {
        /** 头最多往你这边转多少度。 */
        static final float MAX_HEAD = 50;

        /**
         * 身子侧了 {@code side} 度（拖动转身后的总角度）时，头相对身子转多少度。按 sin 变：侧身 90° 时转得最多、背对你时不转，
         * 转多少圈都连续、有上限（原来是 side × look，一直累加，转几圈头就拧到背后去了）。默认角度 {@code yaw} 时正好是 yaw × look。
         */
        public float head(float side) {
            double s = Math.sin(Math.toRadians(yaw));
            float amp = Math.abs(s) > .05 ? (float) (yaw * look / s) : look * 60;
            amp = Math.min(Math.abs(amp), MAX_HEAD);
            return amp * (float) Math.sin(Math.toRadians(side));
        }
    }

    /**
     * 连携切入里的她（docs/CHAIN_CUTIN.md「角色的姿势」「每个人的镜头和光屑」）：{@code make} 造一个不放进世界的实体，摆成她在切入里的样子。
     * 身体播她动画文件里的 {@code cutin}（tools/chain_cutin_poses.py 从她自己的出招、施法里取的那一刻，合她的人设），
     * 表情是她自己的一段 {@code face_*}；穿哪一套照档案（{@link CompanionSheet#outfit()}）。{@code turn} 是身子在朝着画面中间之外再侧多少度
     * （正数往她自己的左边转），头照样回过来看着镜头。{@code shot} 是拍她的镜头怎么动，{@code motes} 是飘在她前面的光屑；
     * 格子的底色和光用她的 {@link #night0()}、{@link #accent()}。
     */
    public record Cutin(BiFunction<Level, CompanionSheet, @Nullable LivingEntity> make, float turn, CutinShot shot, CutinMotes motes) {}

    private static final Map<String, CompanionStyle> STYLES = new ConcurrentHashMap<>();

    public static void register(String companion, CompanionStyle style) {
        STYLES.put(companion, style);
    }

    public static CompanionStyle of(String companion) {
        return STYLES.getOrDefault(companion, SHIRAUME);
    }

    /**
     * 白梅：夜里的藏青到紫，淡粉的狐火当主色，金线；纹是梅鉢（五瓣梅花，正中一个圆）。面板里她凝着流火、摆着流火的架势，
     * 狐狸似的眯着眼（{@code face_kitsune}），身子往左侧一点、脸朝着你。
     */
    public static final CompanionStyle SHIRAUME = new CompanionStyle(0xFFF7B6D6, 0xFFD9779F, 0xFFE3C27A, 0xFFB9C6F2, 0xFFF4EEF2, 0xFFB3A4BF,
            0xFF1C1532, 0xFF0D0A1A, 0xB4120E22, CompanionUi::umebachi,
            new Showcase((level, sheet) -> {
                var c = Companions.SHIRAUME.get().create(level, EntitySpawnReason.LOAD);
                if (c != null) c.showcase(CompanionOutfit.byId(sheet.outfit()), "face_kitsune", "ryuka_idle", true);
                return c;
            }, 18, .75f, -8, .68f, 0),
            // 切入：流火竖在右肩外、刀尖过头，左手叉腰，眯着眼笑——赢的人的式神，也还是那只骄傲的九尾。
            // 镜头横扫、歪得最多，慢慢往下沉；身前飘着淡粉的狐火
            new Cutin((level, sheet) -> {
                var c = Companions.SHIRAUME.get().create(level, EntitySpawnReason.LOAD);
                if (c != null) c.showcase(CompanionOutfit.byId(sheet.outfit()), "face_kitsune", "cutin", true);
                return c;
            }, 0, new CutinShot(46, .14f, 14, 9, -.05f), new CutinMotes(CutinMotes.Kind.FOXFIRE, 0xFFC2E0)),
            // 奥义「狐嫁入 · 水镜形代断」的画面：水镜、浮世绘、斩纸、破镜、因果斩，梅瓣擦镜
            ShiraumeUltimateShow.FACTORY);

    /**
     * 精灵公主：夜里的林子（深绿到墨绿），薄荷绿当主色，金线，被动能力用荷花粉；纹是世界树的叶（一圈细环、四片菱形的叶、正中一颗生命晶）。
     * 面板里她拿着世界树之弓，安心地笑着，身子往左侧一点、脸朝着你。
     */
    public static final CompanionStyle PRINCESS = new CompanionStyle(0xFF8CD9B0, 0xFF4FA07C, 0xFFE3C27A, 0xFFF2C4C1, 0xFFF1F6EE, 0xFFA8C2B4,
            0xFF0F2A21, 0xFF07140F, 0xB40B1C16, CompanionStyle::worldTree,
            new Showcase((level, sheet) -> {
                var c = Companions.ELF_PRINCESS.get().create(level, EntitySpawnReason.LOAD);
                if (c != null) c.showcase("face_content");
                return c;
            }, 18, .75f, -8, .68f, 0),
            // 切入：把世界树之弓拉满，难得一脸认真——哪怕只有万分之一的机会。
            // 镜头几乎不扫，一直往她脸上推（瞄准时的那股专注）；光叶打着转落下
            new Cutin((level, sheet) -> {
                var c = Companions.ELF_PRINCESS.get().create(level, EntitySpawnReason.LOAD);
                if (c != null) {
                    c.showcase("face_determined");
                    c.stage("face_determined", "cutin");
                }
                return c;
            }, 28, new CutinShot(38, .2f, 4, 5, 0), new CutinMotes(CutinMotes.Kind.LEAF, 0xA8F0C6)),
            // 奥义「万分之一 · 世界树祈愿矢」的画面：专属过场做好以前先用占位的那一段
            StandInShow.of(UltimateFilm.Wipe.LEAF, 0xA8F0C6, 0xF2D58A));

    /**
     * 琥珀：温室午后的咖啡色到深棕，琥珀色当主色，金线，奶油色正文，被动能力用淡紫（星辰花）；纹是一只琥珀色的猫爪按在圆形炼成阵上。
     * 面板里她拄着长杖，懒洋洋地看着你，身子往左侧一点。
     */
    public static final CompanionStyle KOHAKU = new CompanionStyle(0xFFE8A043, 0xFFB8732A, 0xFFE3C27A, 0xFFC9B6E8, 0xFFF6EEDD, 0xFFBFAE95,
            0xFF2A1C14, 0xFF140D09, 0xB41E140E, CompanionStyle::paw,
            new Showcase((level, sheet) -> {
                var c = Companions.KOHAKU.get().create(level, EntitySpawnReason.LOAD);
                if (c != null) c.showcase("face_lazy", sheet.seals(), KohakuOutfit.byId(sheet.outfit()));
                return c;
            }, 18, .75f, -8, .68f, 0),
            // 切入：长杖拄在身侧，左手抬到下巴前竖起一指，竖瞳一闪——想到解法了，这一下交给她。
            // 镜头从左往右扫、慢慢升起来；身前翻飞着黄符纸
            new Cutin((level, sheet) -> {
                var c = Companions.KOHAKU.get().create(level, EntitySpawnReason.LOAD);
                if (c != null) {
                    c.showcase("face_focus", sheet.seals(), KohakuOutfit.byId(sheet.outfit()));
                    c.stage("face_focus", "cutin");
                }
                return c;
            }, 0, new CutinShot(42, .1f, -10, 7, .05f), new CutinMotes(CutinMotes.Kind.TALISMAN, 0xF2D06E)),
            // 奥义「星辰花炼成 · 凝时琥珀」的画面：敌群凝时、画面凝进琥珀、指尖轻弹碎镜、星辰花爆破、碎晶擦镜（docs/KOHAKU_COMPANION.md 第 20 节）
            KohakuUltimateShow.FACTORY);

    /**
     * 拉塞塔：熔岩夜里的酒红到深绯，珊瑚红当主色（翼骨与龙鳞），金线（角尖鎏金），被动能力用骑士甲的冷银，奶油色正文；
     * 纹是一枚盾形的龙鳞，正中一簇跃动的龙炎（既是真龙之鳞，也是护主之盾）。面板里她叉腰站着、自信地浅笑，身子侧 20°。
     */
    public static final CompanionStyle LACERTA = new CompanionStyle(0xFFE0566B, 0xFF8E2A3C, 0xFFE3C27A, 0xFFC9D3E0, 0xFFF6EEDD, 0xFFC0A7A5,
            0xFF2A1218, 0xFF14080B, 0xB4200C12, CompanionStyle::dragonScale,
            new Showcase((level, sheet) -> {
                var c = Companions.LACERTA.get().create(level, EntitySpawnReason.LOAD);
                if (c != null) c.showcase("face_poise");
                return c;
            }, 20, .75f, -8, .68f, 0),
            // 切入：炽焰长剑剑尖朝下拄在身前，双翼全展，龙瞳一竖——寸步不退的绯翼前排。
            // 镜头最广、最稳，往下沉着仰拍，几乎不歪；火星往上蹿
            new Cutin((level, sheet) -> {
                var c = Companions.LACERTA.get().create(level, EntitySpawnReason.LOAD);
                if (c != null) {
                    c.showcase("face_battle");
                    c.showcaseSword();
                    c.stage("face_battle", "cutin");
                }
                return c;
            }, 0, new CutinShot(50, .08f, 8, 4, -.08f), new CutinMotes(CutinMotes.Kind.EMBER, 0xF6A04D)),
            // 奥义「绯穹裁决 · 炽龙裂地破」的画面：八镜过场和世界里的碎岩、光柱、地裂（docs/LACERTA_COMPANION.md 第 22 节）
            LacertaUltimateShow::begin);

    /** 盾形的龙鳞，正中一簇龙炎：一圈细环，环里一枚大菱形（鳞），鳞的上半一枚小菱形、下面一点（火苗）。 */
    static void dragonScale(GuiGraphicsExtractor g, float cx, float cy, float r, int color) {
        CompanionUi.ring(g, cx, cy, r, Math.max(1, r * .1f), color);
        int scale = Math.max(2, Math.round(r * .55f));
        CompanionUi.diamond(g, Math.round(cx), Math.round(cy + r * .06f), scale, color);
        int flame = Math.max(1, Math.round(r * .18f));
        CompanionUi.diamond(g, Math.round(cx), Math.round(cy - r * .52f), flame, color);
        CompanionUi.disc(g, cx, cy - r * .3f, Math.max(1, r * .08f), color);
    }

    /** 猫爪按在炼成阵上：一圈细环，环里一个大肉垫、四个小肉垫。 */
    static void paw(GuiGraphicsExtractor g, float cx, float cy, float r, int color) {
        CompanionUi.ring(g, cx, cy, r, Math.max(1, r * .1f), color);
        CompanionUi.disc(g, cx, cy + r * .2f, Math.max(1, r * .3f), color);
        float toe = Math.max(1, r * .14f);
        CompanionUi.disc(g, cx - r * .42f, cy - r * .12f, toe, color);
        CompanionUi.disc(g, cx - r * .16f, cy - r * .4f, toe, color);
        CompanionUi.disc(g, cx + r * .16f, cy - r * .4f, toe, color);
        CompanionUi.disc(g, cx + r * .42f, cy - r * .12f, toe, color);
    }

    /** 世界树的叶纹：一圈细环，四片菱形的叶，正中一颗生命晶。 */
    static void worldTree(GuiGraphicsExtractor g, float cx, float cy, float r, int color) {
        CompanionUi.ring(g, cx, cy, r, Math.max(1, r * .12f), color);
        int leaf = Math.max(1, Math.round(r * .3f));
        float d = r * .55f;
        CompanionUi.diamond(g, Math.round(cx), Math.round(cy - d), leaf, color);
        CompanionUi.diamond(g, Math.round(cx - d), Math.round(cy), leaf, color);
        CompanionUi.diamond(g, Math.round(cx + d), Math.round(cy), leaf, color);
        CompanionUi.diamond(g, Math.round(cx), Math.round(cy + d), leaf, color);
        CompanionUi.disc(g, cx, cy, Math.max(1, r * .16f), color);
    }

    static {
        register("shiraume", SHIRAUME);
        register("elf_princess", PRINCESS);
        // 腐化女王之战里的她（对话菜单另一个 ID，样子还是她）
        register(dev.logos.dimension.elven.blight.ElfPrincess.TALK, PRINCESS);
        register("kohaku", KOHAKU);
        register("lacerta", LACERTA);
    }
}
