package dev.logos.dimension.longsword;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.CombatWeapon;
import dev.logos.item.WandItem;
import dev.logos.runtime.CastPose;
import dev.logos.runtime.Charging;
import dev.logos.runtime.VoiceCasting;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 能施法的战斗武器（黑钢长剑，既是 Logos 的法杖也是本附属的战斗武器）：右键竖剑格挡，格挡中左键开始 Logos 蓄力，
 * 蓄满后松开右键施放。蓄力、施放本身全走 Logos（{@link Charging}、言出法随 {@link VoiceCasting}），这里只把结果接到招式上：
 * 蓄力时单手剑尖指向前（{@link #CHARGE}）；施放时按 Logos 的施法动作判定（{@link CastPose#gesture}，看启式后的第一条语句）：
 * 术式以光柱开头时一直前指，直到 Logos 的光柱姿势 {@link CastPose#BEAM} 结束（{@link #BEAM}）；否则向前一送再收回（{@link #THRUST}）。
 * 招式名对应招式文件 {@code combat/movesets/blacksteel_longsword.json}。
 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class SwordCasting {
    public static final String GUARD_RAISE = "guard_raise", GUARD = "guard",
            CHARGE = "cast_charge", BEAM = "cast_beam", THRUST = "cast_thrust", LOWER = "cast_lower";
    /** 蓄力开不起来（没挂载页、魔力不够）之后这么多刻内不再试：按住左键时每 3 刻补发一次，不要反复弹提示。 */
    private static final int RETRY_TICKS = 10;
    private static final Map<UUID, Long> REFUSED = new HashMap<>();
    /** 光柱的姿势从它在世界里的第一刻才开始；施放后这么多刻还没等到就当光柱没成形，收回。 */
    private static final int BEAM_GRACE = 5;
    /** 前指保持中的施法者：还在等光柱姿势出现的刻数；等到之后为 -1。 */
    private static final Map<UUID, Integer> BEAMING = new HashMap<>();

    private SwordCasting() {}

    public static boolean guarding(String move) { return GUARD_RAISE.equals(move) || GUARD.equals(move); }

    /** 左键：格挡中开始蓄力，开得起来才让招式转到蓄力；其他招式照常出刀。 */
    public static boolean allowsAttack(ServerPlayer player, String move) {
        if (!guarding(move)) return true;
        var stack = player.getUseItem();
        if (!player.isUsingItem() || !(stack.getItem() instanceof WandItem)) return false;
        long now = player.level().getGameTime();
        Long refused = REFUSED.get(player.getUUID());
        if (refused != null && now - refused < RETRY_TICKS) return false;
        boolean began = VoiceCasting.armed(stack) ? VoiceCasting.begin(player, stack, InteractionHand.MAIN_HAND)
                : Charging.begin(player, stack, InteractionHand.MAIN_HAND);
        if (began) REFUSED.remove(player.getUUID()); else REFUSED.put(player.getUUID(), now);
        return began;
    }

    /** 松开右键：正在蓄力时蓄满就施放并按结果选收势，返回 true；没在蓄力（只是格挡）时返回 false，交给招式的 use_release。 */
    public static boolean release(ServerPlayer player) {
        var charge = Charging.of(player);
        if (charge == null && !VoiceCasting.listening(player)) return false;
        boolean leadsWithBeam = charge != null && CastPose.gesture(charge.spell()) == null;
        boolean cast = Charging.release(player);
        if (cast && leadsWithBeam) BEAMING.put(player.getUUID(), 0);
        LongswordCombat.force(player, !cast ? LOWER : leadsWithBeam ? BEAM : THRUST);
        return true;
    }

    /** 每刻：蓄力被 Logos 中断时收剑；光柱姿势结束（或施放后一直没等到）时收回。 */
    public static void tick(ServerPlayer player, String move) {
        switch (move) {
            case CHARGE -> { if (Charging.of(player) == null && !VoiceCasting.listening(player)) LongswordCombat.force(player, LOWER); }
            case BEAM -> {
                int waited = BEAMING.getOrDefault(player.getUUID(), 0);
                if (beam(player)) BEAMING.put(player.getUUID(), -1);
                else if (waited < 0 || waited >= BEAM_GRACE) { BEAMING.remove(player.getUUID()); LongswordCombat.force(player, LOWER); }
                else BEAMING.put(player.getUUID(), waited + 1);
            }
            default -> BEAMING.remove(player.getUUID());
        }
    }

    /** 是否正保持着 Logos 的光柱姿势（术式以光柱开头，光柱还在）。 */
    private static boolean beam(ServerPlayer player) { return CastPose.holding(player).contains(CastPose.BEAM); }

    /** 只有竖剑格挡的招式挡得住：蓄力、施法时剑尖朝前，右键虽然还按着也不再格挡。 */
    @SubscribeEvent(priority = EventPriority.HIGH)
    static void blocked(LivingShieldBlockEvent event) {
        if (!event.getBlocked() || !(event.getEntity() instanceof ServerPlayer player)) return;
        var item = player.getUseItem().getItem();
        if (!(item instanceof WandItem) || !(item instanceof CombatWeapon.Holder)) return;
        var move = LongswordCombat.move(player);
        if (move == null || !guarding(move)) event.setBlocked(false);
    }

    @SubscribeEvent static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        REFUSED.remove(event.getEntity().getUUID());
        BEAMING.remove(event.getEntity().getUUID());
    }
}
