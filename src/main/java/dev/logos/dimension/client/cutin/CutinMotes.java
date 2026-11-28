package dev.logos.dimension.client.cutin;

/**
 * 连携切入里飘在她前面的光屑（docs/CHAIN_CUTIN.md「每个人的镜头和光屑」），写在各自的档案里。两层：近的大、虚、快，
 * 远一点的小、清楚；冲进来时比她冲得还快（离镜头更近），停留时按各自的样子飘。形状在 {@code chain_cutin.fsh} 里画。
 *
 * @param kind  什么光屑
 * @param color 颜色（RGB）
 */
public record CutinMotes(Kind kind, int color) {
    /** 着色器里按 {@link #ordinal()} 认，顺序不能改。 */
    public enum Kind {
        /** 狐火：带尾巴的小火团，摇着往上飘，一闪一闪。 */
        FOXFIRE,
        /** 光叶：细长的叶片，打着转往斜下方落。 */
        LEAF,
        /** 符纸：一张张小纸条翻着往上飘，正中一道朱砂。 */
        TALISMAN,
        /** 火星：拖着短尾的亮点，抖着往上蹿。 */
        EMBER,
        /** 星芒：四角的闪光，慢慢漂、一亮一暗。 */
        GLINT
    }
}
