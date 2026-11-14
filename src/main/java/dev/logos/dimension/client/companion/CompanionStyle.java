package dev.logos.dimension.client.companion;

import dev.logos.dimension.companion.CompanionOutfit;
import dev.logos.dimension.companion.CompanionSheet;
import dev.logos.dimension.companion.Companions;
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
 */
public record CompanionStyle(int accent, int accentDeep, int gold, int cool, int ink, int dim, int night0, int night1, int card, Crest crest,
                             Showcase showcase) {
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
            }, 18, .75f, -8, .68f, 0));

    static {
        register("shiraume", SHIRAUME);
    }
}
