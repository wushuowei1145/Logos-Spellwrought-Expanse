package dev.logos.dimension.shiraume;

import dev.logos.dimension.LogosDimension;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 被白梅弹开的弹射物归她所有：原版弹开时把主人设回原来的射手（{@code Projectile.deflect}），
 * 所以下一刻再改，弹回去打中射手时算是她打的。
 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class ShiraumeDeflect {
    private record Claim(Projectile projectile, Entity by) {}
    private static final List<Claim> PENDING = new ArrayList<>();

    private ShiraumeDeflect() {}

    static void claim(Projectile projectile, Entity by) {
        PENDING.add(new Claim(projectile, by));
    }

    @SubscribeEvent
    static void tick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) return;
        for (Claim c : List.copyOf(PENDING)) if (!c.projectile.isRemoved() && c.by.isAlive()) c.projectile.setOwner(c.by);
        PENDING.clear();
    }
}
