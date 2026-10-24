package dev.logos.dimension.client;

import com.geckolib.animatable.GeoEntity;
import com.geckolib.model.DefaultedEntityGeoModel;
import com.geckolib.renderer.GeoEntityRenderer;
import com.geckolib.renderer.base.BoneSnapshots;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.RenderPassInfo;
import com.geckolib.renderer.layer.builtin.ItemInHandGeoLayer;

import dev.logos.dimension.LogosDimension;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;

/**
 * 附属模组 NPC 的通用 GeckoLib 渲染器，即车万女仆 / YSM 的渲染风格（docs/NPC_MODELS.md 第 5 节）：
 * alpha 裁切、不剔除背面，原版实体光照与世界光照，不用自定义着色器。
 *
 * <p>与具体角色无关：按资源名读取 {@code geckolib/models/entity/<名>.geo.json}、
 * {@code textures/entity/<名>.png} 与 {@code geckolib/animations/entity/<名>.animation.json}。
 * 模型须使用车万女仆骨架命名；本类只用到 {@link #HEAD_BONE} 与两个手部挂点。
 */
public class NpcRenderer<T extends Mob & GeoEntity, R extends LivingEntityRenderState & GeoRenderState>
        extends GeoEntityRenderer<T, R> {
    /** NPC 按常规女仆模型的 2 倍制作，游戏内整体缩放 0.5。 */
    public static final float MODEL_SCALE = 0.5f;
    /** 随视线转动的头部骨骼。 */
    public static final String HEAD_BONE = "Head";
    /** 手持物品的挂点骨骼。 */
    public static final String RIGHT_HAND_BONE = "RightHandLocator";
    public static final String LEFT_HAND_BONE = "LeftHandLocator";

    public NpcRenderer(EntityRendererProvider.Context context, String asset) {
        this(context, LogosDimension.id(asset));
    }

    public NpcRenderer(EntityRendererProvider.Context context, Identifier asset) {
        super(context, new DefaultedEntityGeoModel<>(asset));
        withScale(MODEL_SCALE);
        withRenderLayer(new ItemInHandGeoLayer<>(context, this, RIGHT_HAND_BONE, LEFT_HAND_BONE));
        this.shadowRadius = 0.35f;
    }

    /** 26.1 的 {@code entityCutout} 即旧版的 entityCutoutNoCull：薄发片与裙摆内侧两面可见。 */
    @Override
    public RenderType getRenderType(R state, Identifier texture) {
        return RenderTypes.entityCutout(texture);
    }

    /** 头部跟随视线：在动画姿势之上叠加俯仰与相对身体的偏航。 */
    @Override
    public void adjustModelBonesForRender(RenderPassInfo<R> info, BoneSnapshots snapshots) {
        super.adjustModelBonesForRender(info, snapshots);
        R state = info.renderState();
        snapshots.ifPresent(HEAD_BONE, head -> {
            head.setRotX(head.getRotX() + state.xRot * Mth.DEG_TO_RAD);
            head.setRotY(head.getRotY() + state.yRot * Mth.DEG_TO_RAD);
        });
    }
}
