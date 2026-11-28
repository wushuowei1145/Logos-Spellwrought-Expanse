package dev.logos.dimension.client.cutin;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.ultimate.StandInShow;
import dev.logos.dimension.client.ultimate.UltimateFilm;
import dev.logos.dimension.client.ultimate.UltimateShow;
import dev.logos.dimension.ultimate.FinisherTemplate;
import java.util.Map;
import net.minecraft.util.context.ContextKey;

/**
 * 玩家在连携切入里的档案（docs/CHAIN_CUTIN.md；同伴的在 {@code CompanionStyle.Cutin}）：架势、身子侧多少、镜头、光屑和格子的颜色。
 *
 * @param pose   架势
 * @param turn   身子侧多少度（看得到举着武器的那条胳膊），头回过来看着镜头
 * @param shot   镜头怎么动
 * @param motes  飘在前面的光屑
 * @param accent 格子的主色（分割线的外焰、身后的光、速度线）
 * @param night     格子的底色（上面；下面更暗）
 * @param finishers 最后一击在客户端的画面，每种武器大类一个（docs/ULTIMATES.md「玩家的最后一击」）；没写的那一类没有客户端画面。
 *                  服务端那一半在 {@code HeroUltimate#PLAYER}
 */
public record HeroCutin(Pose pose, float turn, CutinShot shot, CutinMotes motes, int accent, int night,
                        Map<FinisherTemplate, UltimateShow.Factory> finishers) {
    /**
     * 架势（弧度，原版人形模型的骨骼角度；左手惯用时左右镜像）：主手抬到胸前，武器竖在脸旁；副手往前下方一展，两脚一前一后。
     * 格子窄（五人时只有约 0.35 格宽），手和武器都收在身前、竖着，不往两边伸。
     * 由 {@code ChainCutinModelMixin} 在模型摆完动作之后套上，只对画进切入贴图的那一份渲染状态生效。
     */
    public record Pose(float mainArmX, float mainArmY, float mainArmZ, float offArmX, float offArmY, float offArmZ, float headX, float headZ,
                       float frontLegX, float backLegX) {}

    /** 渲染状态上带着这个的是切入里的玩家（只在画切入贴图时设）。 */
    public static final ContextKey<Pose> POSE = new ContextKey<>(LogosDimension.id("chain_cutin_pose"));

    /** 暖金的格子，金色星芒；镜头从右往左扫、慢慢升，略歪。 */
    public static final HeroCutin PLAYER = new HeroCutin(new Pose(-1.3f, .09f, .12f, -.55f, -.3f, -.18f, .12f, -.06f, -.35f, .3f), -18,
            new CutinShot(42, .14f, -10, 4, .04f), new CutinMotes(CutinMotes.Kind.GLINT, 0xF2D58A), 0xF2D58A, 0x1C1A30,
            Map.of(FinisherTemplate.UNARMED, StandInShow.of(UltimateFilm.Wipe.STAR, 0xF2D58A, 0xFFFFFF)));

    public HeroCutin {
        finishers = Map.copyOf(finishers);
    }

    /** 拿着 {@code template} 这一类（服务端已经按档案落好类）放最后一击时的画面。 */
    public UltimateShow.Factory finisher(FinisherTemplate template) {
        return finishers.getOrDefault(template, UltimateShow.NONE);
    }
}
