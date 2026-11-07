package dev.logos.dimension;

import dev.logos.dimension.dissolve.DissolveShape;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DissolveShapeTest {
    /** 命令列出来的名字都要能解析，否则补全出来的是用不了的。 */
    @Test void everyNameTheCommandOffersParses() {
        for (String name : DissolveShape.NAMES) assertNotNull(DissolveShape.parse(name), name);
        assertNull(DissolveShape.parse("nonsense"));
    }

    @Test void implodeIsASphereTurnedRound() {
        assertFalse(DissolveShape.parse("sphere").inward());
        assertTrue(DissolveShape.parse("implode").inward());
        assertEquals(DissolveShape.Kind.SPHERE, DissolveShape.parse("implode").kind());
    }

    @Test void noiseHasNoFrontToFray() {
        assertEquals(1, DissolveShape.noise().rough());
        assertTrue(DissolveShape.sweep(0, -1, 0).rough() < 1);
    }

    @Test void survivesTheNetwork() {
        var buf = Unpooled.buffer();
        DissolveShape shape = DissolveShape.point(1.5, 2, -3).reversed().rough(.6f);
        DissolveShape.CODEC.encode(buf, shape);
        assertEquals(shape, DissolveShape.CODEC.decode(buf));
    }
}
