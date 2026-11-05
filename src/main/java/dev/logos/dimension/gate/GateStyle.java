package dev.logos.dimension.gate;

import dev.logos.dimension.LogosDimension;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

/**
 * 自动生成的门的样式，对应异世界的几个种群。主世界自然生成的大号门不在这里。
 *
 * <p>模板都是门面朝向 X 轴、长宽为奇数，启门石在地面中心，模板由 {@code tools/GateAssets.java} 生成。
 */
public enum GateStyle {
    /** 精灵：两棵树合拢的拱门。 */
    ELF("small_gate", 21),
    /** 神狐：鸟居。 */
    FOX("fox_gate", 25),
    /** 灵猫：英式红砖尖拱。 */
    CAT("cat_gate", 29),
    /** 群狼：古罗马凯旋门，斯巴达式的战旗和青铜。 */
    WOLF("wolf_gate", 43),
    /** 人类帝国：中世纪城门楼。 */
    HUMAN("human_gate", 23);

    private final Identifier template;
    private final int cells;

    GateStyle(String template, int cells) {
        this.template = LogosDimension.id("gate/" + template);
        this.cells = cells;
    }

    public Identifier template() { return template; }

    /** 门洞有多少格。 */
    public int cells() { return cells; }

    /**
     * 这个位置该生成哪种门。远行大陆以后按所在种群的领地来定，领地还没做，现在先随机；主世界一律是树门。
     */
    public static GateStyle at(ServerLevel level, RandomSource random) {
        if (level.dimension() != LogosDimension.CLOUDSEA) return ELF;
        GateStyle[] styles = values();
        return styles[random.nextInt(styles.length)];
    }
}
