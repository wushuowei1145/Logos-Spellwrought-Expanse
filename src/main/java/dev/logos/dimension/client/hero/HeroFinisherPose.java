package dev.logos.dimension.client.hero;

import static dev.logos.dimension.ultimate.HeroFinisherTimeline.APEX;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.CONVERGE;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.FALL;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.IMPACT;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.LENGTH;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.LUNGE;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.PILLAR;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.THROW;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.cutin.HeroCutin;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Avatar;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.renderstate.AvatarRenderStateModifier;
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent;

/**
 * 最后一击里主角的姿势：在渲染状态上带一份 {@link HeroCutin.Pose}（连携切入里的玩家用的同一份，由 {@code ChainCutinModelMixin} 套上），
 * 只在这一段放着时、只改渲染状态。身子转到面朝要砸的那一点。
 * <ol>
 *   <li>伸臂托火（Cut 1–2，参考图）：右臂向前水平伸直托着火，左臂垂在身侧，两脚一前一后；</li>
 *   <li>光流灌进来（Cut 3）：站得更开，左臂往后张开压住；</li>
 *   <li>举球（Cut 4）：双臂高举托住头顶的魔弹，仰头；</li>
 *   <li>暴砸（Cut 5）：冲刺时双臂抡过头顶，最后几刻往前下方砸出去；</li>
 *   <li>收势（Cut 6–8）：砸完右臂留在身前，Cut 8 慢慢垂下来，交还原版。</li>
 * </ol>
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class HeroFinisherPose {
    private record Key(float age, HeroCutin.Pose pose) {}

    private static final HeroCutin.Pose REST = new HeroCutin.Pose(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    private static final HeroCutin.Pose HOLD = new HeroCutin.Pose(-1.57f, -.06f, 0, .12f, 0, -.1f, .12f, 0, -.22f, .25f);
    private static final HeroCutin.Pose SURGE = new HeroCutin.Pose(-1.6f, -.1f, 0, .45f, 0, -.75f, -.05f, 0, -.45f, .45f);
    private static final HeroCutin.Pose RAISE = new HeroCutin.Pose(-2.9f, 0, -.32f, -2.9f, 0, .32f, -.45f, 0, -.18f, .22f);
    private static final HeroCutin.Pose WIND = new HeroCutin.Pose(-2.85f, .2f, 0, -2.85f, -.2f, 0, -.2f, 0, -1f, .8f);
    private static final HeroCutin.Pose SMASH = new HeroCutin.Pose(-1.15f, .3f, 0, -1.15f, -.3f, 0, .25f, 0, -.8f, .7f);
    private static final HeroCutin.Pose EXTEND = new HeroCutin.Pose(-1.45f, -.05f, 0, .08f, 0, -.08f, 0, 0, -.25f, .25f);

    private static final Key[] KEYS = {
            new Key(0, REST), new Key(8, HOLD), new Key(CONVERGE, HOLD), new Key(CONVERGE + 5, SURGE), new Key(APEX - 6, SURGE),
            new Key(APEX + 2, RAISE), new Key(LUNGE, RAISE), new Key(LUNGE + 4, WIND), new Key(THROW, WIND), new Key(IMPACT, SMASH),
            new Key(PILLAR, SMASH), new Key(FALL - 6, EXTEND), new Key(FALL + 2, EXTEND), new Key(LENGTH - 4, REST)};

    private HeroFinisherPose() {}

    /** 第 {@code age} 刻的姿势：在关键姿势之间两头慢、中间快地过渡。 */
    static HeroCutin.Pose at(float age) {
        if (age <= KEYS[0].age) return KEYS[0].pose;
        for (int i = 1; i < KEYS.length; i++) {
            if (age < KEYS[i].age) {
                Key a = KEYS[i - 1], b = KEYS[i];
                float t = (age - a.age) / (b.age - a.age);
                return blend(a.pose, b.pose, t * t * (3 - 2 * t));
            }
        }
        return KEYS[KEYS.length - 1].pose;
    }

    private static HeroCutin.Pose blend(HeroCutin.Pose a, HeroCutin.Pose b, float t) {
        return new HeroCutin.Pose(Mth.lerp(t, a.mainArmX(), b.mainArmX()), Mth.lerp(t, a.mainArmY(), b.mainArmY()), Mth.lerp(t, a.mainArmZ(), b.mainArmZ()),
                Mth.lerp(t, a.offArmX(), b.offArmX()), Mth.lerp(t, a.offArmY(), b.offArmY()), Mth.lerp(t, a.offArmZ(), b.offArmZ()),
                Mth.lerp(t, a.headX(), b.headX()), Mth.lerp(t, a.headZ(), b.headZ()), Mth.lerp(t, a.frontLegX(), b.frontLegX()),
                Mth.lerp(t, a.backLegX(), b.backLegX()));
    }

    @SubscribeEvent
    static void modifiers(RegisterRenderStateModifiersEvent event) {
        event.registerAvatarEntityModifier(new AvatarRenderStateModifier() {
            @Override
            public <T extends Avatar & ClientAvatarEntity> void accept(T avatar, AvatarRenderState state) {
                if (HeroFinisherShow.ACTIVE.isEmpty() || state.isSpectator) return;
                HeroFinisherShow show = HeroFinisherShow.ACTIVE.get(avatar.getId());
                if (show == null) return;
                float age = show.age(state.partialTick);
                if (age < 0 || age >= LENGTH - 4) return;
                state.setRenderData(HeroCutin.POSE, at(age));
                // 身子转到面朝要砸的那一点，头照旧看他自己的方向（不超过 75°）
                float view = state.bodyRot + state.yRot;
                state.bodyRot = show.yaw;
                state.yRot = Mth.clamp(Mth.wrapDegrees(view - show.yaw), -75, 75);
                state.attackTime = 0;
                state.walkAnimationSpeed = 0;
            }
        });
    }
}
