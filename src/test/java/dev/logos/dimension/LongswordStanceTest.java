package dev.logos.dimension;

import dev.logos.dimension.longsword.LongswordStance;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 潜行时腰部必须保留原版前倾，双手剑不能将上身单独拉直。 */
class LongswordStanceTest {
    @Test void crouchedIdleKeepsVanillaWaistAndNeck() {
        var pose=LongswordStance.body(new Vector3f(),false,true);
        var waist=pose.transform().transformPosition(new Vector3f(0,12,0));
        assertEquals(3.2+12*Math.cos(.5),waist.y,1e-5);
        assertEquals(12*Math.sin(.5),waist.z,1e-5);
        assertEquals(.5,pose.transform().getEulerAnglesZYX(new Vector3f()).x,1e-5);
        assertEquals(0,pose.neckOffset().length(),1e-5);
    }
    @Test void standingAndLeftHandedPosesKeepTheirExistingPivot() {
        for(boolean left:new boolean[]{false,true}){
            var pose=LongswordStance.body(new Vector3f(12,27,-8),left,false);
            var center=pose.transform().transformPosition(new Vector3f(0,6,0));
            assertEquals(0,center.x,1e-5);assertEquals(6,center.y,1e-5);assertEquals(0,center.z,1e-5);
        }
        var right=LongswordStance.body(new Vector3f(12,27,-8),false,true).neckOffset();
        var left=LongswordStance.body(new Vector3f(12,27,-8),true,true).neckOffset();
        assertEquals(-right.x,left.x,1e-5);assertEquals(right.y,left.y,1e-5);assertEquals(right.z,left.z,1e-5);
    }
}
