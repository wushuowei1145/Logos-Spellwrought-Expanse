package dev.logos.dimension;

import com.google.gson.*;
import dev.logos.dimension.longsword.LongswordPhase;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 动作的接触时序、循环边界与实际玩家素材约束。 */
class LongswordMotionTest {
    private static JsonObject read(String path) throws Exception {
        try (var stream = LongswordMotionTest.class.getResourceAsStream("/assets/logos_dimension/longsword/"+path+".json")) {
            assertNotNull(stream, path);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    @Test void cadenceAndChargeMatchContacts() {
        for (var phase : new LongswordPhase[]{LongswordPhase.ATTACK_1,LongswordPhase.ATTACK_2,LongswordPhase.ATTACK_3}) {
            assertEquals(12.5,phase.duration(1,0));
            assertTrue(phase.contact(1)>0 && phase.contact(1)<phase.duration(1,0));
            assertEquals(phase.contact(1)/2,phase.contact(2));
        }
        assertEquals("charge_hold",LongswordPhase.CHARGING.clip(11));
        assertEquals(0,LongswordPhase.CHARGING.seconds(43,1,0),.0001);
        assertEquals(.54,LongswordPhase.RELEASE.seconds(LongswordPhase.RELEASE.contact(1),1,0),.0001);
        assertEquals(0,LongswordPhase.CANCEL.seconds(5,1,5),.0001);
    }
    @Test void bothViewsHaveAllNineClipsAndRightEdgeRoot() throws Exception {
        for (var view : new String[]{"fp","tp"}) {
            var clips=read("motion_"+view);
            assertEquals(Set.of("draw","idle","attack_1","attack_2","attack_3","charge_start","charge_hold","charge_release","sheathe"),clips.keySet());
            for(var clip:clips.entrySet()) for(var track:clip.getValue().getAsJsonObject().getAsJsonObject("tracks").entrySet())
                for(var channel:track.getValue().getAsJsonObject().entrySet()) {
                    double previous=-1;
                    for(var key:channel.getValue().getAsJsonArray()) {
                        var values=key.getAsJsonArray(); assertEquals(4,values.size());
                        assertTrue(values.get(0).getAsDouble()>=previous); previous=values.get(0).getAsDouble();
                        for(var v:values)assertTrue(Double.isFinite(v.getAsDouble()));
                    }
                }
        }
        var root=read("motion_fp").getAsJsonObject("idle").getAsJsonObject("tracks").getAsJsonObject("root");
        assertTrue(root.getAsJsonArray("position").get(0).getAsJsonArray().get(1).getAsDouble()>=9.9,"待机根骨必须保留靠右位移");
    }
    @Test void runtimeMeshesExcludeDisplayMannequinAndHaveCompleteFaces() throws Exception {
        for(var sword:new String[]{"dawnvow","blacksteel_longsword"})for(var view:new String[]{"fp","tp"}){
            var model=read(sword+"_"+view); boolean sheath=false, hands=false;
            for(var value:model.getAsJsonArray("cubes")){
                var cube=value.getAsJsonObject(); var bone=cube.get("bone").getAsString();
                assertFalse(Set.of("head","body","left_leg","right_leg").contains(bone));
                assertEquals(6,cube.getAsJsonObject("uv").size());
                sheath|=bone.equals("scabbard_root"); hands|=bone.equals("left_hand");
            }
            assertTrue(hands); if(view.equals("tp"))assertTrue(sheath);
        }
    }
}
