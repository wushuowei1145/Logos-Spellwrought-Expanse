package dev.logos.dimension.client;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.foxfire.FoxfireClient;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.item.CrimsonFlameLongswordItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 炽焰长剑冲刺时身上的龙炎（docs/CRIMSON_FLAME_LONGSWORD.md）：按同步来的招式和开始的刻自己画，不另发网络包。
 * 时段同服务端的位移（{@code CrimsonFlameSkills.combatTick}）：突刺第 2–5 刻、旋风第 2–10 刻、坠星升空第 1–4 刻和下砸第 7–10 刻。
 * 全部是龙炎的火舌和熔金火星（{@link FoxfireClient#ember}），不用原版粒子。第一人称时自己身上的不画（会糊在镜头上），只画旋风的那一圈。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class CrimsonFlameClient {
    private static final FoxfireFlame FLAME = FoxfireFlame.DRAGON;
    /** 测试用：放过几点。 */
    private static int embers;

    private CrimsonFlameClient() {}

    public static int embers() { return embers; }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.isPaused()) return;
        int id = CrimsonFlameLongswordItem.ITEM.get().combatWeapon().id();
        for (Player p : level.players()) {
            var pose = LongswordClient.pose(p.getUUID());
            if (pose == null || pose.cleared() || pose.variant() != id) continue;
            long t = (long) Math.floor(level.getGameTime() - pose.started());
            boolean self = p == mc.player && mc.options.getCameraType().isFirstPerson();
            switch (pose.move()) {
                case "crimson_thrust" -> { if (t >= 2 && t <= 5 && !self) trail(level, p, 4); }
                case "vortex_rush" -> { if (t >= 2 && t <= 10) ring(level, p, t); }
                case "skyfall_judgement" -> { if ((t >= 1 && t <= 4 || t >= 7 && t <= 10) && !self) trail(level, p, 5); }
                default -> {}
            }
        }
    }

    /** 冲刺拖出的火舌：身后一串，往上飘。 */
    private static void trail(ClientLevel level, Player p, int n) {
        RandomSource r = level.getRandom();
        Vec3 v = p.getDeltaMovement();
        for (int i = 0; i < n; i++) {
            Vec3 at = p.position().add((r.nextDouble() - .5) * .6, .3 + r.nextDouble() * 1.2, (r.nextDouble() - .5) * .6);
            Vec3 drift = v.scale(-.08).add(0, .03 + r.nextDouble() * .03, 0);
            FoxfireClient.ember(FLAME, false, at, drift, 8 + r.nextInt(6), .16f + r.nextFloat() * .1f);
            if (r.nextInt(2) == 0) FoxfireClient.ember(FLAME, true, at, drift.add((r.nextDouble() - .5) * .08, .05, (r.nextDouble() - .5) * .08),
                    10 + r.nextInt(10), .035f + r.nextFloat() * .02f);
            embers++;
        }
    }

    /** 旋风斩绕身的一圈火：四个点绕着转，同原来的转速（每刻 1.15 弧度）。 */
    private static void ring(ClientLevel level, Player p, long t) {
        RandomSource r = level.getRandom();
        for (int i = 0; i < 4; i++) {
            double a = t * 1.15 + i * Math.PI / 2;
            Vec3 at = new Vec3(p.getX() + Math.cos(a) * 1.4, p.getY() + .9 + .15 * Math.sin(a), p.getZ() + Math.sin(a) * 1.4);
            Vec3 tangent = new Vec3(-Math.sin(a), 0, Math.cos(a)).scale(.06);
            FoxfireClient.ember(FLAME, false, at, tangent.add(0, .02, 0), 7 + r.nextInt(5), .2f + r.nextFloat() * .08f);
            FoxfireClient.ember(FLAME, true, at, tangent.scale(1.6).add(0, .04, 0), 10 + r.nextInt(8), .04f);
            embers++;
        }
    }
}
