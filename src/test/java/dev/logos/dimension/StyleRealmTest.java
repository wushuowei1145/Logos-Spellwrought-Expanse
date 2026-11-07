package dev.logos.dimension;

import com.mojang.serialization.JsonOps;
import dev.logos.dimension.worldstyle.StyleDoor;
import dev.logos.dimension.worldstyle.StyleRealm;
import dev.logos.dimension.worldstyle.StyleRealmsPayload;
import dev.logos.dimension.worldstyle.WorldStyle;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StyleRealmTest {
    /** 墙在 z=10 这一层，门洞三格宽四格高，朝北（-z）是外面。 */
    private static final StyleDoor DOOR = StyleDoor.opening(new BlockPos(-1, 100, 10), new BlockPos(1, 103, 10), Direction.NORTH);

    @Test void openingSitsOnTheOutwardFaceOfTheHole() {
        assertEquals(new Vec3(.5, 102, 10), DOOR.center());
        assertEquals(new Vec3(1.5, 0, 0), DOOR.right());
        assertEquals(new Vec3(0, 2, 0), DOOR.up());
        assertEquals(new Vec3(.5, 102, 11), StyleDoor.opening(new BlockPos(-1, 100, 10), new BlockPos(1, 103, 10), Direction.SOUTH).center());
    }

    @Test void openingWorksOnEverySide() {
        StyleDoor east = StyleDoor.opening(new BlockPos(5, 100, 0), new BlockPos(5, 101, 2), Direction.EAST);
        assertEquals(new Vec3(6, 101, 1.5), east.center());
        assertEquals(new Vec3(0, 0, 1.5), east.right());
        StyleDoor up = StyleDoor.opening(new BlockPos(0, 100, 0), new BlockPos(1, 100, 1), Direction.UP);
        assertEquals(new Vec3(1, 101, 1), up.center());
        assertEquals(new Vec3(1, 0, 0), up.right());
        assertEquals(new Vec3(0, 0, 1), up.up());
    }

    @Test void walkingThroughTheHoleCrossesTheDoor() {
        assertTrue(DOOR.crossedBy(new Vec3(.5, 101, 9.9), new Vec3(.5, 101, 10.1)));
        assertTrue(DOOR.crossedBy(new Vec3(.5, 101, 10.1), new Vec3(.5, 101, 9.9)), "两个方向都算");
    }

    @Test void missingTheHoleDoesNotCrossTheDoor() {
        assertFalse(DOOR.crossedBy(new Vec3(5, 101, 9), new Vec3(5, 101, 11)), "从门洞旁边穿过去");
        assertFalse(DOOR.crossedBy(new Vec3(.5, 110, 9), new Vec3(.5, 110, 11)), "从门洞上方穿过去");
        assertFalse(DOOR.crossedBy(new Vec3(.5, 101, 5), new Vec3(.5, 101, 9)), "还没走到");
        assertFalse(DOOR.crossedBy(new Vec3(-1, 101, 9), new Vec3(1, 101, 9)), "平行走过");
        assertFalse(DOOR.crossedBy(new Vec3(.5, 101, 9), new Vec3(.5, 101, 9)), "没有动");
    }

    /** 门在范围外面时要把范围撑到门：镜头穿过门的那一刻必须正好算进入。 */
    @Test void boundsGrowToReachEveryDoor() {
        StyleRealm realm = StyleRealm.of(new BlockPos(-5, 100, 12), new BlockPos(5, 110, 20), WorldStyle.FAIRY_TALE, DOOR);
        assertEquals(10, realm.bounds().minZ);
        assertTrue(realm.contains(new Vec3(.5, 101, 10.01)));
        assertFalse(realm.contains(new Vec3(.5, 101, 9.99)));
    }

    @Test void survivesTheSaveFile() {
        StyleRealm realm = StyleRealm.of(new BlockPos(-5, 100, 10), new BlockPos(5, 110, 20), WorldStyle.MONOCHROME, DOOR);
        var saved = StyleRealm.CODEC.encodeStart(JsonOps.INSTANCE, realm).getOrThrow();
        StyleRealm loaded = StyleRealm.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
        assertEquals(realm.bounds(), loaded.bounds());
        assertEquals(realm.style(), loaded.style());
        assertEquals(realm.doors(), loaded.doors());
    }

    @Test void survivesTheWire() {
        var payload = new StyleRealmsPayload(List.of(new StyleRealm(new AABB(0, 0, 0, 1, 1, 1), WorldStyle.FAIRY_TALE, List.of(DOOR))));
        var buffer = Unpooled.buffer();
        StyleRealmsPayload.CODEC.encode(buffer, payload);
        StyleRealmsPayload decoded = StyleRealmsPayload.CODEC.decode(buffer);
        assertEquals(payload.realms().getFirst().doors(), decoded.realms().getFirst().doors());
        assertEquals(0, buffer.readableBytes());
    }

    /** 着色器靠这些编号选算法，0 留给原画。 */
    @Test void stylesKeepTheirShaderIds() {
        assertEquals(1, WorldStyle.MONOCHROME.shaderId());
        assertEquals(2, WorldStyle.FAIRY_TALE.shaderId());
    }
}
