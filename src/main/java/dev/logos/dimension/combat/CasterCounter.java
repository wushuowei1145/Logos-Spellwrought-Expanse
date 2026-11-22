package dev.logos.dimension.combat;

import dev.logos.runtime.Charging;
import dev.logos.runtime.FreeManaEntity;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * Boss 对付术师时共用的几样（白梅的斩法、剑祟的断咏）：看一个人此刻蓄了多少魔力，以及不出提示地打断他的蓄力。
 *
 * <p>Logos 自己的 {@code Charging.interrupt} 会在动作栏提示「蓄力被打断」，附属不出状态文字（只靠画面和声音交代），
 * 所以这里照它的做法自己拼一个：先把蓄力拿走、蓄进去的魔力照 Logos 的规矩散成游离魔力，再让他放下法杖——
 * 放下时已经没有蓄力，Logos 就不会再提示。语音念咒正在汇集的魔力同样散掉。
 */
public final class CasterCounter {
    private CasterCounter() {}

    /** 这个人此刻用法杖蓄进去的魔力，或者语音念咒正在汇集的魔力；没在蓄时为 0。 */
    public static double charging(ServerPlayer player) {
        PlayerMagic m = PlayerMagic.of(player);
        double mana = 0;
        if (m.charge != null) mana = Math.max(mana, m.charge.injected);
        if (m.listen != null) mana = Math.max(mana, m.listen.injected());
        return mana;
    }

    /** 不出提示地打断蓄力和语音念咒；返回散掉了多少魔力。 */
    public static double interrupt(ServerPlayer player) {
        PlayerMagic m = PlayerMagic.of(player);
        double spilled = 0;
        Charging.Charge c = m.charge;
        if (c != null) {
            m.charge = null;
            spilled += c.injected;
        }
        var listen = m.listen;
        if (listen != null) {
            m.listen = null;
            spilled += listen.injected();
        }
        m.chanting = null;
        if (c != null || listen != null) Charging.hide(player);
        if (spilled > 0) {
            Vec3 at = player.getEyePosition().add(player.getLookAngle().scale(.8));
            FreeManaEntity.release(player.level(), at, spilled);
            player.level().playSound(null, at.x, at.y, at.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, .4f, 1.6f);
        }
        if (player.isUsingItem()) player.stopUsingItem();
        return spilled;
    }
}
