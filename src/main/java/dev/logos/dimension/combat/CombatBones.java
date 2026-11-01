package dev.logos.dimension.combat;

import java.util.List;

/**
 * 战斗骨架的标准骨骼名：TLM GeckoLib 女仆骨架的 PascalCase 名称，加两根扩展骨骼 {@link #WEAPON}、{@link #SHEATH}。
 * 规则见 docs/COMBAT_SKELETON.md；左右指角色自身的左右。
 */
public final class CombatBones {
    public static final String ROOT = "Root", ALL_BODY = "AllBody", UP_BODY = "UpBody", UPPER_BODY = "UpperBody",
            ALL_HEAD = "AllHead", HEAD = "Head", ARM = "Arm",
            RIGHT_ARM = "RightArm", RIGHT_FORE_ARM = "RightForeArm", RIGHT_HAND = "RightHand",
            LEFT_ARM = "LeftArm", LEFT_FORE_ARM = "LeftForeArm", LEFT_HAND = "LeftHand",
            DOWN_BODY = "DownBody", RIGHT_LEG = "RightLeg", RIGHT_LOWER_LEG = "RightLowerLeg", RIGHT_FOOT = "RightFoot",
            LEFT_LEG = "LeftLeg", LEFT_LOWER_LEG = "LeftLowerLeg", LEFT_FOOT = "LeftFoot",
            WEAPON = "Weapon", SHEATH = "Sheath";
    /** 每副战斗骨架都必须有的骨骼；与 docs/NPC_MODELS.md 第 2 节的必需层级一致。 */
    public static final List<String> REQUIRED = List.of(ROOT, ALL_BODY, UP_BODY, UPPER_BODY, ALL_HEAD, HEAD, ARM,
            RIGHT_ARM, RIGHT_FORE_ARM, RIGHT_HAND, LEFT_ARM, LEFT_FORE_ARM, LEFT_HAND,
            DOWN_BODY, RIGHT_LEG, RIGHT_LOWER_LEG, RIGHT_FOOT, LEFT_LEG, LEFT_LOWER_LEG, LEFT_FOOT);
    private CombatBones() {}

    /** 左右互换后的骨骼名，用于左手惯用镜像；没有左右之分的名字原样返回。 */
    public static String mirror(String bone) {
        if (bone.startsWith("Right")) return "Left" + bone.substring(5);
        if (bone.startsWith("Left")) return "Right" + bone.substring(4);
        return bone;
    }
}
