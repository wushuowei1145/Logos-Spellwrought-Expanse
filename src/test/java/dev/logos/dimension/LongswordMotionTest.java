package dev.logos.dimension;

import com.google.gson.*;
import dev.logos.dimension.combat.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 动作循环边界、战斗骨架资源约束，以及迁移到 TLM 骨骼名后与旧渲染逐矩阵一致；招式时序见 MovesetTest。 */
class LongswordMotionTest {
    static JsonObject read(String path) throws Exception {
        try (var stream = LongswordMotionTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, path);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    static JsonObject combat(String path) throws Exception { return read("/assets/logos_dimension/combat/" + path + ".json"); }
    static Map<String, CombatClip> clips(String view) throws Exception {
        List<String> warnings = new ArrayList<>();
        var clips = CombatClip.parseFile(combat("animations/longsword_" + view + ".animation"), warnings);
        assertEquals(List.of(), warnings);
        return clips;
    }
    @Test void bothViewsHaveAllClipsAndRightEdgeRoot() throws Exception {
        for (var view : new String[]{"fp","tp"}) {
            var clips=clips(view);
            assertEquals(Set.of("draw","idle","attack_1","attack_2","attack_3","charge_start","charge_hold","charge_release","parried","parried_npc","sheathe"),clips.keySet());
            assertEquals(CombatClip.Loop.LOOP,clips.get("idle").loop());
            assertEquals(.625,clips.get("attack_1").length(),1e-6);
            // 动作只引用标准骨骼名，不再出现美术工程里的 snake_case 名。
            for (var clip : clips.values()) for (var bone : clip.bones())
                assertTrue(CombatBones.REQUIRED.contains(bone) || bone.equals(CombatBones.WEAPON) || bone.equals(CombatBones.SHEATH), bone);
        }
        var root=clips("fp").get("idle").sample(CombatBones.ROOT,CombatClip.Channel.POSITION,0,new Vector3f());
        assertTrue(root.x>=9.9,"待机根骨必须保留靠右位移");
    }
    @Test void skeletonsAndWeaponsFollowTheStandard() throws Exception {
        for (var view : new String[]{"fp","tp"}) {
            var json=combat("skeletons/player_"+view); var skeleton=CombatSkeleton.fromJson(json);
            assertEquals(List.of(),skeleton.missingRequired());
            assertTrue(skeleton.has(CombatBones.WEAPON) && skeleton.has(CombatBones.SHEATH));
            boolean hands=false;
            for (var value : json.getAsJsonArray("skin")) {
                var bone=value.getAsJsonObject().get("bone").getAsString();
                // 只导出手臂分段；头、躯干和腿仍由原版玩家模型绘制。
                assertTrue(bone.contains("Arm") || bone.endsWith("Hand"), bone);
                hands|=bone.equals(CombatBones.LEFT_HAND);
            }
            assertTrue(hands);
        }
        for (var sword : new String[]{"dawnvow","blacksteel_longsword"}) {
            var weapon=combat("weapons/"+sword); boolean sheath=false, blade=false;
            for (var value : weapon.getAsJsonArray("cubes")) {
                var cube=value.getAsJsonObject();
                assertEquals(6,cube.getAsJsonObject("uv").size());
                sheath|=cube.get("part").getAsString().equals("sheath"); blade|=cube.get("part").getAsString().equals("weapon");
            }
            assertTrue(sheath && blade);
        }
    }
    /** 参考矩阵由迁移前的 LongswordRig 算法对旧数据求得；新骨架与动作必须得到同样的骨骼位置。 */
    @Test void migratedClipsReproduceOldRigMatrices() throws Exception {
        var reference=read("/combat/longsword_reference.json");
        for (var value : reference.getAsJsonArray("cases")) {
            var c=value.getAsJsonObject(); var view=c.get("view").getAsString(); var clip=c.get("clip").getAsString();
            var skeleton=CombatSkeleton.fromJson(combat("skeletons/player_"+view));
            var pose=CombatPose.sample(clips(view).get(clip),c.get("time").getAsFloat());
            var matrices=pose.matrices(skeleton);
            var body=c.getAsJsonArray("body_rotation"); var rotation=pose.rotation(CombatBones.UP_BODY);
            assertEquals(body.get(0).getAsFloat(),rotation.x,1e-3f); assertEquals(body.get(1).getAsFloat(),rotation.y,1e-3f); assertEquals(body.get(2).getAsFloat(),rotation.z,1e-3f);
            for (var bone : c.getAsJsonObject("bones").entrySet()) {
                var expected=bone.getValue().getAsJsonArray(); var actual=matrices.get(bone.getKey());
                assertNotNull(actual,bone.getKey());
                for (int row=0;row<4;row++) for (int col=0;col<4;col++)
                    assertEquals(expected.get(row*4+col).getAsFloat(),actual.get(col,row),2e-3f,view+"/"+clip+"/"+bone.getKey()+"["+row+","+col+"]");
            }
        }
    }
    /** 黑钢长剑第三人称的施法前指：手臂从肩到拳伸直，水平朝前，剑顺着手臂的延长线。 */
    @Test void blacksteelHoldKeepsTheArmStraightAndInLineWithTheBlade() throws Exception {
        var skeleton=CombatSkeleton.fromJson(combat("skeletons/player_tp"));
        var clip=CombatClip.parseFile(combat("animations/blacksteel_longsword_tp.animation"),new ArrayList<>()).get("cast_aim");
        var bones=CombatPose.sample(clip,0).matrices(skeleton);
        var shoulder=bones.get(CombatBones.RIGHT_ARM).transformPosition(new Vector3f(skeleton.bone(CombatBones.RIGHT_ARM).pivot()));
        var elbow=bones.get(CombatBones.RIGHT_FORE_ARM).transformPosition(new Vector3f(skeleton.bone(CombatBones.RIGHT_FORE_ARM).pivot()));
        var hand=bones.get(CombatBones.RIGHT_HAND).transformPosition(new Vector3f(skeleton.bone(CombatBones.RIGHT_HAND).pivot()));
        var upper=new Vector3f(elbow).sub(shoulder).normalize(); var fore=new Vector3f(hand).sub(elbow).normalize();
        assertTrue(Math.toDegrees(upper.angle(fore))<2,"手肘弯了："+Math.toDegrees(upper.angle(fore)));
        var arm=new Vector3f(hand).sub(shoulder).normalize();
        var blade=bones.get(CombatBones.WEAPON).transformDirection(new Vector3f(0,1,0)).normalize();
        assertTrue(Math.toDegrees(arm.angle(blade))<1,"剑不在手臂的延长线上："+Math.toDegrees(arm.angle(blade)));
        assertEquals(0,arm.y,.02f,"手臂不是水平的");
        assertTrue(arm.z<-.9f,"手臂没有朝前："+arm);
    }
    @Test void weaponOriginKeepsTheOldItemSurfaceTransform() throws Exception {
        // 魔力缠绕旧算法：剑骨骼 × 平移 (0,1,0) × 视角缩放；新算法取骨架里的剑枢轴与 weapon_scale。
        for (var view : new String[]{"fp","tp"}) {
            var json=combat("skeletons/player_"+view); var skeleton=CombatSkeleton.fromJson(json);
            assertEquals(new Vector3f(0,1,0),skeleton.bone(CombatBones.WEAPON).pivot());
            assertEquals(view.equals("fp")?.58f:.8f,json.get("weapon_scale").getAsFloat());
            var bones=CombatPose.sample(clips(view).get("attack_2"),.3f).matrices(skeleton);
            var weapon=new Matrix4f(bones.get(CombatBones.WEAPON)).translate(skeleton.bone(CombatBones.WEAPON).pivot()).scale(json.get("weapon_scale").getAsFloat());
            var old=new Matrix4f(bones.get(CombatBones.WEAPON)).translate(0,1,0).scale(view.equals("fp")?.58f:.8f);
            assertTrue(weapon.equals(old,1e-5f));
        }
    }
}
