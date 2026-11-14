package dev.logos.dimension.client.music;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.logos.dimension.music.InstrumentKind;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

/**
 * 大提琴、低音提琴运弓：惯用手的手位就是画出来的弓根，离肩正好一臂远。
 * 以前给 NPC 的是弓根「该在」的点（常常比一臂远），IK 把手收回到肩的正前方，手只上下动、弓却在左右拉。
 */
class BowGripTest {
    /** 弓的物品像素里弓根的位置（HeldRig.seatedBlock 把它放在 grip 上），换成以 8 为中心的坐标。 */
    private static final Vector3f FROG = new Vector3f(8.1f - 8, 1.8f - 8, 0);

    @Test void bowHandHoldsTheFrogOneReachFromTheShoulder() {
        // 演奏者面朝北、双脚在 SeatedInstrumentBlock.feet：精灵的肩窄、手臂 7.3 像素，原版人形的肩宽、一臂 8.4 像素
        float[][] shoulders = {{10.1f, 18.1f, 12.5f, 7.3f}, {13.6f, 18.4f, 12.5f, 8.4f}, {10.1f, 20.3f, 11.5f, 7.3f}, {13.6f, 22.6f, 11.5f, 8.4f}};
        for (InstrumentKind kind : new InstrumentKind[]{InstrumentKind.CELLO, InstrumentKind.DOUBLE_BASS}) {
            for (float[] s : shoulders) {
                Vector3f right = new Vector3f(s[0], s[1], s[2]), left = new Vector3f(16 - s[0], s[1], s[2]);
                float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
                for (float t = 0; t < 21; t += 0.5f) {
                    HeldRig.Place p = HeldRig.seatedBlock(kind, right, left, s[3], t, 1);
                    Vector3f frog = p.bow().transformPosition(new Vector3f(FROG));
                    assertEquals(0, p.mainHand().distance(frog), 1e-3, kind + " bow hand is on the frog at t=" + t);
                    assertEquals(s[3], p.mainHand().distance(right), 1e-3, kind + " bow hand is one reach from the shoulder");
                    minX = Math.min(minX, p.mainHand().x);
                    maxX = Math.max(maxX, p.mainHand().x);
                }
                assertTrue(maxX - minX > 1.5f, kind + " bow hand travels across the strings with the bow: " + (maxX - minX));
            }
        }
    }
}
