package dev.logos.dimension;

import dev.logos.dimension.item.NatureCharge;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NatureChargeTest {
    @Test void chargeBoundaries() {
        assertEquals(1, NatureCharge.stage(19));
        assertEquals(2, NatureCharge.stage(20));
        assertEquals(2, NatureCharge.stage(29));
        assertEquals(3, NatureCharge.stage(30));
        assertEquals(3, NatureCharge.stage(39));
        assertEquals(4, NatureCharge.stage(40));
        assertEquals(4, NatureCharge.stage(72000));
    }

    @Test void onlyFourthStageHasFastFlight() {
        assertEquals(0, NatureCharge.speed(0));
        assertEquals(2.7f, NatureCharge.speed(20));
        assertEquals(3f, NatureCharge.speed(30));
        assertEquals(8f, NatureCharge.speed(40));
        assertFalse(NatureCharge.empowered(39));
        assertTrue(NatureCharge.empowered(40));
    }

    @Test void manaAccumulatesOnlyForMagicStages() {
        assertEquals(0, NatureCharge.mana(20));
        assertEquals(0, NatureCharge.mana(29));
        assertEquals(5, NatureCharge.mana(30));
        assertEquals(5, NatureCharge.mana(39));
        assertEquals(10, NatureCharge.mana(40));
    }
}
