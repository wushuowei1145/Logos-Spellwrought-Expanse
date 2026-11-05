package dev.logos.dimension.fox;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

/** 聚落居民的落脚点：每处至多 20 个，都在街道或广场上，彼此隔开；每套布局方案都放得下一群神狐。 */
class SanctuaryResidentsTest {
    @Test
    void spotsAreFewEnoughOnStreetsAndSpreadOut() {
        for (int plan = 0; plan < SanctuaryLayout.plans(); plan++) {
            for (long seed : new long[] {1, 42, 20261105}) {
                SanctuaryFrame frame = new SanctuaryFrame(plan, 1000, -2000, Mirror.FRONT_BACK, Rotation.CLOCKWISE_90, 120, 80, seed);
                List<BlockPos> spots = SanctuaryResidents.spots(frame);
                assertTrue(spots.size() <= SanctuaryResidents.MAX, "方案 " + plan + " 落脚点太多：" + spots.size());
                assertTrue(spots.size() >= 12, "方案 " + plan + " 落脚点太少：" + spots.size());
                for (int i = 0; i < spots.size(); i++)
                    for (int j = i + 1; j < spots.size(); j++) {
                        BlockPos a = spots.get(i), b = spots.get(j);
                        assertTrue(Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ())) >= 9, a + " 与 " + b + " 太近");
                    }
                // 高度在主街标高附近（核心区整形过，起伏不大）
                for (BlockPos p : spots) assertTrue(Math.abs(p.getY() - 120) < 40, p.toString());
                assertEquals(spots, SanctuaryResidents.spots(frame), "同一处聚落的落脚点要固定");
            }
        }
    }
}
