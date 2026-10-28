package dev.logos.dimension.client;

import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.rendertype.RenderTypes;

/** 导入素材包的完整盾牌几何；普通与常亮两遍互斥绘制，保留原图、UV 和实际实体光照。 */
public final class AncientEmbraceModel extends Model<Integer> {
    private final boolean emissive;
    public AncientEmbraceModel(ModelPart root, boolean emissive) {
        super(root, RenderTypes::entityCutout);
        this.emissive = emissive;
    }
    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("plate",
            CubeListBuilder.create().texOffs(0, 0).addBox(-6.000000F, -11.000000F, -2.000000F, 12.000000F, 22.000000F, 1.000000F),
            PartPose.offsetAndRotation(0.000000F, 0.000000F, 0.000000F, 0.000000F, 0.000000F, -0.000000F));
        root.addOrReplaceChild("handle",
            CubeListBuilder.create().texOffs(26, 0).addBox(-1.000000F, -3.000000F, -1.000000F, 2.000000F, 6.000000F, 6.000000F),
            PartPose.offsetAndRotation(0.000000F, 0.000000F, 0.000000F, 0.000000F, 0.000000F, -0.000000F));
        root.addOrReplaceChild("ivy_top",
            CubeListBuilder.create().texOffs(0, 26).addBox(-5.000000F, -10.000000F, -3.000000F, 5.000000F, 1.000000F, 1.000000F),
            PartPose.offsetAndRotation(0.000000F, 0.000000F, 0.000000F, 0.000000F, 0.000000F, -0.000000F));
        root.addOrReplaceChild("ivy_upper_left",
            CubeListBuilder.create().texOffs(13, 26).addBox(-6.000000F, -10.000000F, -3.000000F, 1.000000F, 7.000000F, 1.000000F),
            PartPose.offsetAndRotation(0.000000F, 0.000000F, 0.000000F, 0.000000F, 0.000000F, -0.000000F));
        root.addOrReplaceChild("ivy_left_cross",
            CubeListBuilder.create().texOffs(18, 26).addBox(-7.000000F, -3.000000F, -3.000000F, 2.000000F, 1.000000F, 1.000000F),
            PartPose.offsetAndRotation(0.000000F, 0.000000F, 0.000000F, 0.000000F, 0.000000F, -0.000000F));
        root.addOrReplaceChild("ivy_left_side",
            CubeListBuilder.create().texOffs(25, 26).addBox(-7.000000F, -2.000000F, -2.000000F, 1.000000F, 5.000000F, 1.000000F),
            PartPose.offsetAndRotation(0.000000F, 0.000000F, 0.000000F, 0.000000F, 0.000000F, -0.000000F));
        root.addOrReplaceChild("ivy_left_lower",
            CubeListBuilder.create().texOffs(30, 26).addBox(-6.000000F, 2.000000F, -3.000000F, 1.000000F, 6.000000F, 1.000000F),
            PartPose.offsetAndRotation(0.000000F, 0.000000F, 0.000000F, 0.000000F, 0.000000F, -0.000000F));
        root.addOrReplaceChild("ivy_bottom",
            CubeListBuilder.create().texOffs(35, 26).addBox(-5.000000F, 10.000000F, -3.000000F, 6.000000F, 1.000000F, 1.000000F),
            PartPose.offsetAndRotation(0.000000F, 0.000000F, 0.000000F, 0.000000F, 0.000000F, -0.000000F));
        root.addOrReplaceChild("ivy_bottom_corner",
            CubeListBuilder.create().texOffs(50, 26).addBox(-6.000000F, 8.000000F, -3.000000F, 1.000000F, 3.000000F, 1.000000F),
            PartPose.offsetAndRotation(0.000000F, 0.000000F, 0.000000F, 0.000000F, 0.000000F, -0.000000F));
        root.addOrReplaceChild("ivy_right_lower",
            CubeListBuilder.create().texOffs(55, 26).addBox(5.000000F, 4.000000F, -3.000000F, 1.000000F, 6.000000F, 1.000000F),
            PartPose.offsetAndRotation(0.000000F, 0.000000F, 0.000000F, 0.000000F, 0.000000F, -0.000000F));
        root.addOrReplaceChild("ivy_right_corner",
            CubeListBuilder.create().texOffs(0, 35).addBox(5.000000F, 3.000000F, -3.000000F, 2.000000F, 1.000000F, 1.000000F),
            PartPose.offsetAndRotation(0.000000F, 0.000000F, 0.000000F, 0.000000F, 0.000000F, -0.000000F));
        root.addOrReplaceChild("ivy_right_side",
            CubeListBuilder.create().texOffs(7, 35).addBox(6.000000F, 0.000000F, -2.000000F, 1.000000F, 3.000000F, 1.000000F),
            PartPose.offsetAndRotation(0.000000F, 0.000000F, 0.000000F, 0.000000F, 0.000000F, -0.000000F));
        root.addOrReplaceChild("leaf_upper_out_stem",
            CubeListBuilder.create().texOffs(24, 35).addBox(-0.500000F, 0.000000F, 0.000000F, 1.000000F, 1.000000F, 1.000000F),
            PartPose.offsetAndRotation(-5.500000F, -7.000000F, -4.000000F, 0.000000F, 0.000000F, -0.785398F));
        root.addOrReplaceChild("leaf_upper_out_body",
            CubeListBuilder.create().texOffs(12, 35).addBox(-1.000000F, -3.000000F, 0.000000F, 2.000000F, 3.000000F, 1.000000F),
            PartPose.offsetAndRotation(-5.500000F, -7.000000F, -4.000000F, 0.000000F, 0.000000F, -0.785398F));
        root.addOrReplaceChild("leaf_upper_out_tip",
            CubeListBuilder.create().texOffs(19, 35).addBox(-0.500000F, -4.000000F, 0.000000F, 1.000000F, 1.000000F, 1.000000F),
            PartPose.offsetAndRotation(-5.500000F, -7.000000F, -4.000000F, 0.000000F, 0.000000F, -0.785398F));
        root.addOrReplaceChild("leaf_top_stem",
            CubeListBuilder.create().texOffs(24, 35).addBox(-0.500000F, 0.000000F, 0.000000F, 1.000000F, 1.000000F, 1.000000F),
            PartPose.offsetAndRotation(-3.000000F, -9.000000F, -3.500000F, 0.000000F, 0.000000F, -0.392699F));
        root.addOrReplaceChild("leaf_top_body",
            CubeListBuilder.create().texOffs(12, 35).addBox(-1.000000F, -3.000000F, 0.000000F, 2.000000F, 3.000000F, 1.000000F),
            PartPose.offsetAndRotation(-3.000000F, -9.000000F, -3.500000F, 0.000000F, 0.000000F, -0.392699F));
        root.addOrReplaceChild("leaf_top_tip",
            CubeListBuilder.create().texOffs(19, 35).addBox(-0.500000F, -4.000000F, 0.000000F, 1.000000F, 1.000000F, 1.000000F),
            PartPose.offsetAndRotation(-3.000000F, -9.000000F, -3.500000F, 0.000000F, 0.000000F, -0.392699F));
        root.addOrReplaceChild("leaf_upper_in_stem",
            CubeListBuilder.create().texOffs(24, 35).addBox(-0.500000F, 0.000000F, 0.000000F, 1.000000F, 1.000000F, 1.000000F),
            PartPose.offsetAndRotation(-5.000000F, -5.000000F, -4.250000F, 0.000000F, 0.000000F, 0.392699F));
        root.addOrReplaceChild("leaf_upper_in_body",
            CubeListBuilder.create().texOffs(12, 35).addBox(-1.000000F, -3.000000F, 0.000000F, 2.000000F, 3.000000F, 1.000000F),
            PartPose.offsetAndRotation(-5.000000F, -5.000000F, -4.250000F, 0.000000F, 0.000000F, 0.392699F));
        root.addOrReplaceChild("leaf_upper_in_tip",
            CubeListBuilder.create().texOffs(19, 35).addBox(-0.500000F, -4.000000F, 0.000000F, 1.000000F, 1.000000F, 1.000000F),
            PartPose.offsetAndRotation(-5.000000F, -5.000000F, -4.250000F, 0.000000F, 0.000000F, 0.392699F));
        root.addOrReplaceChild("leaf_lower_out_stem",
            CubeListBuilder.create().texOffs(24, 35).addBox(-0.500000F, 0.000000F, 0.000000F, 1.000000F, 1.000000F, 1.000000F),
            PartPose.offsetAndRotation(5.500000F, 6.000000F, -3.750000F, 0.000000F, 0.000000F, 0.785398F));
        root.addOrReplaceChild("leaf_lower_out_body",
            CubeListBuilder.create().texOffs(12, 35).addBox(-1.000000F, -3.000000F, 0.000000F, 2.000000F, 3.000000F, 1.000000F),
            PartPose.offsetAndRotation(5.500000F, 6.000000F, -3.750000F, 0.000000F, 0.000000F, 0.785398F));
        root.addOrReplaceChild("leaf_lower_out_tip",
            CubeListBuilder.create().texOffs(19, 35).addBox(-0.500000F, -4.000000F, 0.000000F, 1.000000F, 1.000000F, 1.000000F),
            PartPose.offsetAndRotation(5.500000F, 6.000000F, -3.750000F, 0.000000F, 0.000000F, 0.785398F));
        root.addOrReplaceChild("leaf_lower_in_stem",
            CubeListBuilder.create().texOffs(24, 35).addBox(-0.500000F, 0.000000F, 0.000000F, 1.000000F, 1.000000F, 1.000000F),
            PartPose.offsetAndRotation(4.500000F, 9.000000F, -4.250000F, 0.000000F, 0.000000F, -0.392699F));
        root.addOrReplaceChild("leaf_lower_in_body",
            CubeListBuilder.create().texOffs(12, 35).addBox(-1.000000F, -3.000000F, 0.000000F, 2.000000F, 3.000000F, 1.000000F),
            PartPose.offsetAndRotation(4.500000F, 9.000000F, -4.250000F, 0.000000F, 0.000000F, -0.392699F));
        root.addOrReplaceChild("leaf_lower_in_tip",
            CubeListBuilder.create().texOffs(19, 35).addBox(-0.500000F, -4.000000F, 0.000000F, 1.000000F, 1.000000F, 1.000000F),
            PartPose.offsetAndRotation(4.500000F, 9.000000F, -4.250000F, 0.000000F, 0.000000F, -0.392699F));
        root.addOrReplaceChild("gold_binding_top",
            CubeListBuilder.create().texOffs(29, 35).addBox(-5.000000F, -10.000000F, -3.750000F, 1.000000F, 1.000000F, 2.000000F),
            PartPose.offsetAndRotation(0.000000F, 0.000000F, 0.000000F, 0.000000F, 0.000000F, -0.000000F));
        root.addOrReplaceChild("gold_binding_bottom",
            CubeListBuilder.create().texOffs(36, 35).addBox(5.000000F, 8.000000F, -3.750000F, 1.000000F, 1.000000F, 2.000000F),
            PartPose.offsetAndRotation(0.000000F, 0.000000F, 0.000000F, 0.000000F, 0.000000F, -0.000000F));
        return LayerDefinition.create(mesh, 64, 64);
    }
    @Override public void setupAnim(Integer leaves) {
        resetPose();
        root.getChild("plate").visible = !emissive;
        root.getChild("handle").visible = !emissive;
        root.getChild("ivy_top").visible = !emissive;
        root.getChild("ivy_upper_left").visible = !emissive;
        root.getChild("ivy_left_cross").visible = !emissive;
        root.getChild("ivy_left_side").visible = !emissive;
        root.getChild("ivy_left_lower").visible = !emissive;
        root.getChild("ivy_bottom").visible = !emissive;
        root.getChild("ivy_bottom_corner").visible = !emissive;
        root.getChild("ivy_right_lower").visible = !emissive;
        root.getChild("ivy_right_corner").visible = !emissive;
        root.getChild("ivy_right_side").visible = !emissive;
        root.getChild("leaf_upper_out_stem").visible = (leaves > 0) == emissive;
        root.getChild("leaf_upper_out_body").visible = (leaves > 0) == emissive;
        root.getChild("leaf_upper_out_tip").visible = (leaves > 0) == emissive;
        root.getChild("leaf_top_stem").visible = (leaves > 1) == emissive;
        root.getChild("leaf_top_body").visible = (leaves > 1) == emissive;
        root.getChild("leaf_top_tip").visible = (leaves > 1) == emissive;
        root.getChild("leaf_upper_in_stem").visible = (leaves > 2) == emissive;
        root.getChild("leaf_upper_in_body").visible = (leaves > 2) == emissive;
        root.getChild("leaf_upper_in_tip").visible = (leaves > 2) == emissive;
        root.getChild("leaf_lower_out_stem").visible = (leaves > 3) == emissive;
        root.getChild("leaf_lower_out_body").visible = (leaves > 3) == emissive;
        root.getChild("leaf_lower_out_tip").visible = (leaves > 3) == emissive;
        root.getChild("leaf_lower_in_stem").visible = (leaves > 4) == emissive;
        root.getChild("leaf_lower_in_body").visible = (leaves > 4) == emissive;
        root.getChild("leaf_lower_in_tip").visible = (leaves > 4) == emissive;
        root.getChild("gold_binding_top").visible = !emissive;
        root.getChild("gold_binding_bottom").visible = !emissive;
    }
}
