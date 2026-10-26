package dev.logos.dimension.item;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.Vec3;

/** 复用单独安装的 Logos API；反射集中在边界并缓存，避免将主模组打进附属包。 */
public final class LogosNatureBridge {
    private static final Method MAGIC_OF, ALERT, DAMAGE, TRAIL, TRAIL_END;
    private static final Field MANA;
    private static final Constructor<?> INSTANCE;
    private static final Object LIFE, FORWARD, PLAIN, SPELL;
    static {
        try {
            var magic = Class.forName("dev.logos.runtime.PlayerMagic");
            MAGIC_OF = magic.getMethod("of", Player.class);
            ALERT = magic.getMethod("alert", ServerPlayer.class, Component.class);
            MANA = magic.getField("mana");
            var element = Class.forName("dev.logos.lang.Element");
            LIFE = element.getField("LIFE").get(null);
            var motion = Class.forName("dev.logos.lang.Motion");
            FORWARD = motion.getField("FORWARD").get(null);
            var guise = Class.forName("dev.logos.runtime.Vfx$Guise");
            PLAIN = guise.getField("PLAIN").get(null);
            var spell = Class.forName("dev.logos.lang.Spell");
            // 只作为一次命中的伤害上下文，不注册或执行额外术式。
            SPELL = spell.getConstructors()[0].newInstance("自然之力", "cast", true, false, 0d, 0d,
                    java.util.List.of(), java.util.List.of(), "", null);
            var instance = Class.forName("dev.logos.runtime.SpellInstance");
            INSTANCE = instance.getConstructor(spell, LivingEntity.class, ServerLevel.class, double.class, double.class);
            DAMAGE = Class.forName("dev.logos.runtime.Effects").getMethod("damage", instance, LivingEntity.class, double.class, element);
            var vfx = Class.forName("dev.logos.runtime.Vfx");
            TRAIL = vfx.getMethod("trail", ServerLevel.class, int.class, Vec3.class, Vec3.class, element, motion, double.class, guise);
            TRAIL_END = vfx.getMethod("trailEnd", ServerLevel.class, int.class, Vec3.class, element);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("自然之力无法接入当前 Logos 的魔力、生命伤害或特效 API", error);
        }
    }

    public static double mana(Player player) {
        try { return MANA.getDouble(MAGIC_OF.invoke(null, player)); }
        catch (ReflectiveOperationException error) { throw failure(error); }
    }

    public static void mana(Player player, double amount) {
        try { MANA.setDouble(MAGIC_OF.invoke(null, player), amount); }
        catch (ReflectiveOperationException error) { throw failure(error); }
    }

    public static boolean pay(ServerPlayer player, int cost) {
        if (player.hasInfiniteMaterials()) return true;
        double available = mana(player);
        if (available < cost) {
            try { ALERT.invoke(null, player, Component.translatable("message.logos_dimension.natures_force.no_mana", cost)); }
            catch (ReflectiveOperationException error) { throw failure(error); }
            return false;
        }
        mana(player, available - cost);
        return true;
    }

    public static void damage(LivingEntity caster, LivingEntity target) {
        try {
            Object instance = INSTANCE.newInstance(SPELL, caster, (ServerLevel) target.level(), 0d, 0d);
            DAMAGE.invoke(null, instance, target, 10d, LIFE);
        } catch (ReflectiveOperationException error) { throw failure(error); }
    }

    public static void trail(AbstractArrow arrow, boolean end) {
        if (!(arrow.level() instanceof ServerLevel level)) return;
        try {
            // Logos 术式的物体编号为正；负实体编号避免与它们复用同一条客户端拖尾。
            int id = -arrow.getId() - 1;
            if (end) TRAIL_END.invoke(null, level, id, arrow.position(), LIFE);
            else TRAIL.invoke(null, level, id, arrow.position(), arrow.getDeltaMovement(), LIFE, FORWARD, .12d, PLAIN);
        } catch (ReflectiveOperationException error) { throw failure(error); }
    }

    private static IllegalStateException failure(Exception error) { return new IllegalStateException("自然之力调用 Logos API 失败", error); }
    private LogosNatureBridge() {}
}
