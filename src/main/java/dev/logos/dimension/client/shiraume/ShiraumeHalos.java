package dev.logos.dimension.client.shiraume;

import dev.logos.client.VfxParts;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 封印全断之后，封印之间里所有光源的光晕变成不祥的红色（docs/SHIRAUME_BOSS.md 第 2 节）：
 * 每个发光方块（狐火灯笼、高石灯笼、倒挂町屋的灯、发光地衣……）上加一团柔和的红晕，交给泛光，轻轻闪烁；
 * 从杀生石往外一圈圈亮起来（{@link ShiraumeClient#spread}）。画面高光转红在 {@link ShiraumeScreen}。
 * 光源每隔几秒分几刻重新找一遍，不一次扫完整个封印之间。
 */
final class ShiraumeHalos {
    /** 多远以内的光源才画，一帧最多画几团。 */
    static final double RANGE = 72;
    static final int MAX = 320;
    /** 分几刻扫完一遍、隔多久重扫。 */
    static final int SLICES = 12, RESCAN = 120;
    private static final List<BlockPos> FOUND = new ArrayList<>(), SCANNING = new ArrayList<>();
    private static @Nullable AABB scanned;
    private static long nextScan;
    private static int slice = -1;

    private ShiraumeHalos() {}

    static void submit(ClientLevel level, VfxParts.View view, float pt, SoftGlow glow) {
        AABB box = ShiraumeClient.moodBox();
        if (box == null) {
            FOUND.clear();
            scanned = null;
            return;
        }
        scan(level, box);
        float mood = ShiraumeClient.mood(view.cam(), pt);
        if (mood <= .003f || FOUND.isEmpty()) return;
        Vec3 cam = view.cam();
        Vec3 forward = view.up().cross(view.right()).normalize();
        float t = level.getGameTime() + pt;
        int drawn = 0;
        for (BlockPos pos : FOUND) {
            Vec3 at = Vec3.atCenterOf(pos);
            Vec3 to = at.subtract(cam);
            double d2 = to.lengthSqr();
            if (d2 > RANGE * RANGE || to.dot(forward) < -1) continue;
            float spread = ShiraumeClient.spread(at, pt);
            if (spread <= 0) continue;
            int light = level.getBlockState(pos).getLightEmission(level, pos);
            if (light <= 0) continue;
            long h = pos.asLong() * 0x9E3779B97F4A7C15L;
            float flicker = .82f + .18f * Mth.sin(t * .23f + (h & 1023) * .0061f) * Mth.sin(t * .51f + (h >>> 20 & 1023) * .0043f);
            float r = (.55f + light / 15f * 1.35f) * flicker;
            glow.disc(at, r, VfxParts.argb(mood * spread * .62f * flicker, 0xE32630));
            glow.disc(at, r * .38f, VfxParts.argb(mood * spread * .55f, 0xFF7A70));
            if (++drawn >= MAX) break;
        }
    }

    /** 分 {@link #SLICES} 刻扫一遍范围里的发光方块，扫完按离范围中心的远近排好换上。 */
    private static void scan(ClientLevel level, AABB box) {
        long now = level.getGameTime();
        if (scanned == null || !scanned.equals(box)) {
            scanned = box;
            FOUND.clear();
            slice = 0;
            SCANNING.clear();
        } else if (slice < 0 && now >= nextScan) {
            slice = 0;
            SCANNING.clear();
        }
        if (slice < 0) return;
        int minX = Mth.floor(box.minX), maxX = Mth.floor(box.maxX);
        int span = maxX - minX + 1, per = Math.max(1, (span + SLICES - 1) / SLICES);
        int x0 = minX + slice * per, x1 = Math.min(maxX, x0 + per - 1);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) {
            for (int z = Mth.floor(box.minZ); z <= Mth.floor(box.maxZ); z++) {
                for (int y = Mth.floor(box.minY); y <= Mth.floor(box.maxY); y++) {
                    p.set(x, y, z);
                    BlockState state = level.getBlockState(p);
                    if (!state.isAir() && state.getLightEmission(level, p) >= 6) SCANNING.add(p.immutable());
                }
            }
        }
        if (++slice >= SLICES) {
            Vec3 c = box.getCenter();
            SCANNING.sort(Comparator.comparingDouble(b -> b.distToCenterSqr(c)));
            FOUND.clear();
            FOUND.addAll(SCANNING);
            SCANNING.clear();
            slice = -1;
            nextScan = now + RESCAN;
        }
    }
}
