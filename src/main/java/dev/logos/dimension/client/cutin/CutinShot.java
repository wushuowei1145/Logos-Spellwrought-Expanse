package dev.logos.dimension.client.cutin;

/**
 * 连携切入里拍她的那个镜头怎么动（docs/CHAIN_CUTIN.md「每个人的镜头和光屑」），写在各自的档案里：同伴在
 * {@code CompanionStyle.Cutin}，玩家在 {@link HeroCutin}。落位时的冲镜（先远、冲过头一点再回来）和甩出时的前冲大家一样，
 * 由 {@link dev.logos.dimension.cutin.ChainTimeline} 定；这里只定每个人自己的镜头。
 *
 * @param fov   镜头的竖直视角（度）。画面装的那一段高度不变，视角越广镜头贴得越近，伸向镜头的手和武器越大
 * @param push  停留期间镜头往前推多少（离她距离的几成）
 * @param orbit 停留期间镜头绕着她横扫多少度（正数从她的右边扫到左边），落位时在一半
 * @param lean  镜头歪多少度：她的头往格子外侧倒（和斜分割线同向）；冲进来时更歪，落位后摆正到这个角度。正中那一格往右倒
 * @param crane 停留期间镜头升高多少（画面高度的几成，负数是往下沉、越来越仰拍）
 */
public record CutinShot(float fov, float push, float orbit, float lean, float crane) {
    /** 冲进来、刚落位时比平常多歪几成。 */
    static final float LEAN_SETTLE = .7f;
}
