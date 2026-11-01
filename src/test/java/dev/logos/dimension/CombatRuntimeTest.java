package dev.logos.dimension;

import com.google.gson.JsonParser;
import dev.logos.dimension.combat.*;
import java.util.*;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 战斗骨架运行时：Bedrock 符号、插值方式、容错、混合与骨骼层级。 */
class CombatRuntimeTest {
    private static CombatClip clip(String bones) {
        List<String> warnings = new ArrayList<>();
        var clips = CombatClip.parseFile(JsonParser.parseString(
                "{\"format_version\":\"1.8.0\",\"animations\":{\"a\":{\"animation_length\":1,\"bones\":" + bones + "}}}").getAsJsonObject(), warnings);
        assertEquals(List.of(), warnings);
        return clips.get("a");
    }
    private static Vector3f at(CombatClip clip, String bone, CombatClip.Channel channel, float t) {
        return clip.sample(bone, channel, t, new Vector3f());
    }

    @Test void bedrockSignsMatchBlockbenchExport() {
        // Blockbench 导出 Bedrock：旋转 X、Y 取反，位移 X 取反；读入时还原。
        var c = clip("{\"RightArm\":{\"rotation\":[10,20,30],\"position\":[1,2,3],\"scale\":2}}");
        assertEquals(new Vector3f(-10, -20, 30), at(c, "RightArm", CombatClip.Channel.ROTATION, 0));
        assertEquals(new Vector3f(-1, 2, 3), at(c, "RightArm", CombatClip.Channel.POSITION, .5f));
        assertEquals(new Vector3f(2, 2, 2), at(c, "RightArm", CombatClip.Channel.SCALE, 1));
        assertNull(at(c, "LeftArm", CombatClip.Channel.ROTATION, 0));
    }
    @Test void linearStepCatmullromAndPrePost() {
        var c = clip("{\"Head\":{\"rotation\":{\"0.0\":[0,0,0],\"1.0\":[0,0,10]}},"
                + "\"Arm\":{\"rotation\":{\"0.0\":{\"post\":[0,0,0],\"lerp_mode\":\"step\"},\"1.0\":[0,0,10]}},"
                + "\"Root\":{\"position\":{\"0.0\":{\"post\":[0,0,0],\"lerp_mode\":\"catmullrom\"},\"0.5\":{\"post\":[0,4,0],\"lerp_mode\":\"catmullrom\"},\"1.0\":{\"post\":[0,0,0],\"lerp_mode\":\"catmullrom\"}}},"
                + "\"UpBody\":{\"rotation\":{\"0.0\":[0,0,0],\"0.5\":{\"pre\":[0,0,5],\"post\":[0,0,20]},\"1.0\":[0,0,20]}}}");
        assertEquals(5, at(c, "Head", CombatClip.Channel.ROTATION, .5f).z, 1e-5);
        assertEquals(0, at(c, "Arm", CombatClip.Channel.ROTATION, .9f).z, 1e-5);
        var mid = at(c, "Root", CombatClip.Channel.POSITION, .25f).y;
        assertTrue(mid > 2 && mid < 4, "catmullrom 在峰值前应高于直线插值的 2");
        assertEquals(2.5f, at(c, "UpBody", CombatClip.Channel.ROTATION, .25f).z, 1e-5);
        assertEquals(20, at(c, "UpBody", CombatClip.Channel.ROTATION, .75f).z, 1e-5);
        assertEquals(10, at(c, "Head", CombatClip.Channel.ROTATION, 5).z, 1e-5);
    }
    @Test void molangAndBrokenClipsDoNotThrow() {
        List<String> warnings = new ArrayList<>();
        var clips = CombatClip.parseFile(JsonParser.parseString("{\"animations\":{"
                + "\"ok\":{\"bones\":{\"Head\":{\"rotation\":[\"math.sin(query.anim_time)\",0,0]}}},"
                + "\"bad\":{\"bones\":{\"Head\":{\"rotation\":{\"x\":[0,0,0]}}}}}}").getAsJsonObject(), warnings);
        assertTrue(clips.containsKey("ok"));
        assertFalse(clips.containsKey("bad"));
        assertEquals(2, warnings.size());
        assertEquals(0, clips.get("ok").sample("Head", CombatClip.Channel.ROTATION, 0, new Vector3f()).x, 0);
    }
    @Test void skeletonToleratesMissingParentsAndReportsRequiredBones() {
        var skeleton = new CombatSkeleton(List.of(
                new CombatSkeleton.Bone("RightHand", "RightForeArm", new Vector3f(5, 10, 0)),
                new CombatSkeleton.Bone("RightForeArm", "Ghost", new Vector3f(5, 17, 0))));
        assertEquals("RightForeArm", skeleton.bones().get(0).name());
        assertNull(skeleton.bones().get(0).parent());
        assertTrue(skeleton.missingRequired().contains(CombatBones.ROOT));
        assertThrows(IllegalArgumentException.class, () -> new CombatSkeleton(List.of(
                new CombatSkeleton.Bone("A", "B", new Vector3f()), new CombatSkeleton.Bone("B", "A", new Vector3f()))));
    }
    @Test void poseIgnoresUnknownBonesAndComposesParents() {
        var c = clip("{\"Arm\":{\"rotation\":[0,0,90]},\"Tail\":{\"rotation\":[0,0,45]}}");
        var skeleton = new CombatSkeleton(List.of(
                new CombatSkeleton.Bone("Arm", null, new Vector3f(0, 10, 0)),
                new CombatSkeleton.Bone("Hand", "Arm", new Vector3f(0, 4, 0))));
        var m = CombatPose.sample(c, 0).matrices(skeleton);
        assertFalse(m.containsKey("Tail"));
        // 手在臂枢轴下方 6 像素；臂绕 Z 转 90°（Bedrock 的 Z 不取反）后手转到 +X 一侧。
        var hand = m.get("Hand").transformPosition(new Vector3f(0, 4, 0));
        assertEquals(6, hand.x, 1e-4); assertEquals(10, hand.y, 1e-4);
    }
    @Test void blendEndpointsAreExactAndMidpointSlerps() {
        var a = CombatPose.sample(clip("{\"Head\":{\"rotation\":[0,0,0],\"position\":[0,0,0]}}"), 0);
        var b = CombatPose.sample(clip("{\"Head\":{\"rotation\":[0,-90,0],\"position\":[-4,0,0]}}"), 0);
        assertEquals(a.rotation("Head"), a.blend(b, 0).rotation("Head"));
        assertEquals(b.rotation("Head"), a.blend(b, 1).rotation("Head"));
        var mid = a.blend(b, .5f);
        assertEquals(45, mid.rotation("Head").y, 1e-3);
        assertEquals(2, mid.joint("Head").position().x, 1e-5);
        assertEquals(new Vector3f(), mid.rotation("Missing"));
    }
    @Test void mirrorSwapsSides() {
        assertEquals("LeftForeArm", CombatBones.mirror("RightForeArm"));
        assertEquals("RightFoot", CombatBones.mirror("LeftFoot"));
        assertEquals("Weapon", CombatBones.mirror("Weapon"));
    }
}
