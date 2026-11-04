package dev.logos.dimension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.logos.dimension.combat.CombatWeapon;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class CombatWeaponTest {
    private static final Identifier MOVESET = Identifier.fromNamespaceAndPath("logos_dimension", "longsword");

    /** 不给张角的武器用默认 60°；给了的用自己的；越界的张角在注册时就报错。 */
    @Test void coneDefaultsAndOverrides() {
        assertEquals(60, CombatWeapon.DEFAULT_CONE);
        assertEquals(CombatWeapon.DEFAULT_CONE, CombatWeapon.register(9000, "test_plain", "longsword", MOVESET).cone());
        assertEquals(75, CombatWeapon.register(9001, "test_wide", "longsword", MOVESET).withCone(75).cone());
        var odd = CombatWeapon.register(9002, "test_odd", "longsword", MOVESET);
        assertThrows(IllegalArgumentException.class, () -> odd.withCone(0));
        assertThrows(IllegalArgumentException.class, () -> odd.withCone(181));
        assertEquals(CombatWeapon.DEFAULT_CONE, odd.cone());
    }
}
