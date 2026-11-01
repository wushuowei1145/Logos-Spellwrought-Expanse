package dev.logos.dimension.client.longsword;

import java.util.*;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** 肩甲与前臂分段跟随相同骨骼，继续交给原版装备层处理染色、纹饰和附魔闪光。 */
public final class LongswordArmorModel extends Model<LongswordClient.Frame> {
    private final Map<String,ModelPart> segments;
    public LongswordArmorModel() { this(parts()); }
    private LongswordArmorModel(Map<String,ModelPart> parts) {
        super(new ModelPart(List.of(),parts),RenderTypes::armorCutoutNoCull); segments=parts;
    }
    private static Map<String,ModelPart> parts() {
        Map<String,ModelPart> parts=new LinkedHashMap<>();
        for(var side:new String[]{"Right","Left"}) for(boolean upper:new boolean[]{true,false}) {
            float x=side.equals("Right")?-7:3;
            var cube=new ModelPart.Cube(40,upper?16:22,x,upper?.55f:6.9f,-2,4,upper?6.35f:5.4f,4,
                    .65f,.18f,.65f,side.equals("Left"),64,32,EnumSet.allOf(Direction.class));
            parts.put(side+(upper?"Arm":"ForeArm"),new ModelPart(List.of(cube),Map.of()));
        }
        return parts;
    }
    @Override public void setupAnim(LongswordClient.Frame frame) {
        resetPose();
        var sourceToNative=new Matrix4f().translation(0,24,0).scale(-1,-1,1);
        var nativeToSource=new Matrix4f(sourceToNative).invert();
        for(var entry:segments.entrySet()) {
            var m=new Matrix4f(sourceToNative).mul(frame.sample().bones().get(entry.getKey())).mul(nativeToSource);
            var position=m.getTranslation(new Vector3f()); var rotation=m.getEulerAnglesZYX(new Vector3f());
            entry.getValue().setPos(position.x,position.y,position.z);
            entry.getValue().setRotation(rotation.x,rotation.y,rotation.z);
        }
    }
}
