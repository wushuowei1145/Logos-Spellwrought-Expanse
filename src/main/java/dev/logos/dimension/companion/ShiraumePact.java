package dev.logos.dimension.companion;

import dev.logos.dimension.camera.CameraPoint;
import dev.logos.dimension.camera.CameraShot;
import dev.logos.dimension.camera.CameraShots;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.item.RyukaItem;
import dev.logos.dimension.shiraume.FoxfireBolt;
import java.util.Collection;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * 立契（docs/SHIRAUME_COMPANION.md 第 8.1、8.2 节）：Boss 战里狐形倒下、化作花瓣之后，花瓣在杀生石前聚回少女，
 * 每位参战的玩家各一位（分霊），跪坐着等他来说话。等待时她只同步给她的那位玩家，别人看不见、碰不到。
 *
 * <p>说话之后是一段过场（遮幅、两个机位、字幕）：睁眼抬头 → 双手托起流火献上 → 用断掉的御札折成形代递上 → 起身。
 * 第一次立契的人得到写着自己名字的形代，她从此跟着他；已经立过契的人得到空白形代（盖契印用），这位分霊化作花瓣回到他的形代里。
 * 时刻和动画 {@code pact} 的时间标记一致（模型 README「第 8 版」）。
 */
public final class ShiraumePact {
    /** 过场的时刻（刻），同 tools/life_clips.py 的 PACT。 */
    static final int LINE_WAKE = 2, LINE_1 = 18, SWORD = 34, PRESENT = 58, GIVE_SWORD = 76, BACK = 86, LINE_3 = 92, GIVE_KATASHIRO = 148,
            LINE_4 = 132, STAND = 178, END = 188;
    /** 每位等待的白梅离杀生石多远。 */
    static final double RING = 2.6;
    /**
     * 过场的两个机位和看的地方（相对她：沿她面朝主人的方向、她的右手边、往上，格）。机位一在右前方，看她托刀献上；
     * 机位二在同一侧（不越轴）、近一点低一点，看她折形代、递过来、起身。两个机位都偏在她和主人连线的一侧，
     * 主人站在 {@link #TOO_CLOSE} 格以外时只在画面边上，挡不住她（CompanionPactTest 量过）。
     */
    static final double[] SHOT_A = {2.4, 1.3, 1.0}, LOOK_A = {0, 0, .62}, SHOT_B = {1.4, .95, .78}, LOOK_B = {.25, 0, .62};
    /** 主人贴得比这还近：先让他退到 {@link #STAND_OFF} 格（那里站得下的话），不然机位会被他自己（连同胳膊）挡住。 */
    public static final double TOO_CLOSE = 1.6, STAND_OFF = 2.0;

    private ShiraumePact() {}

    // ---------------------------------------------------------------- 等你

    /** Boss 战击败后：给每位参战的玩家在 {@code center}（杀生石脚下）周围放一位跪坐等他的白梅。 */
    public static void spawnWaiting(ServerLevel level, Vec3 center, Collection<UUID> players) {
        int i = 0, n = Math.max(1, players.size());
        for (UUID id : players) {
            double a = Math.PI * 2 * i++ / n + Math.PI / 2;
            Vec3 at = ground(level, center.add(Math.cos(a) * RING, 0, Math.sin(a) * RING));
            float yaw = (float) (Mth.atan2(at.z - center.z, at.x - center.x) * Mth.RAD_TO_DEG) - 90;
            ShiraumeCompanion c = spawn(level, id, at, yaw);
            if (c != null && level.getServer().getPlayerList().getPlayer(id) instanceof ServerPlayer p && p.level() == level) gather(level, p, c.position());
        }
    }

    /** 放一位等 {@code owner} 的白梅（命令和测试也从这里进）。 */
    public static ShiraumeCompanion spawn(ServerLevel level, UUID owner, Vec3 at, float yaw) {
        ShiraumeCompanion c = Companions.SHIRAUME.get().create(level, EntitySpawnReason.TRIGGERED);
        if (c == null) return null;
        c.snapTo(at.x, at.y, at.z, yaw, 0);
        c.setYHeadRot(yaw);
        c.setYBodyRot(yaw);
        c.waitFor(owner);
        level.addFreshEntity(c);
        return c;
    }

    private static Vec3 ground(ServerLevel level, Vec3 near) {
        BlockPos at = BlockPos.containing(near);
        for (int dy = 3; dy >= -4; dy--) {
            BlockPos feet = at.above(dy);
            if (!level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty() && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                    && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) {
                return new Vec3(near.x, feet.getY(), near.z);
            }
        }
        return near;
    }

    /** 花瓣聚回少女：只发给她等的那个人。 */
    static void gather(ServerLevel level, ServerPlayer p, Vec3 at) {
        Vec3 c = at.add(0, .7, 0);
        for (int k = 0; k < 24; k++) {
            double a = k * Math.PI * 2 / 24, r = 2.2;
            Vec3 from = c.add(Math.cos(a) * r, (k % 3 - 1) * .5, Math.sin(a) * r);
            Vec3 v = c.subtract(from).scale(.09);
            level.sendParticles(p, ParticleTypes.END_ROD, true, false, from.x, from.y, from.z, 0, v.x, v.y, v.z, 1);
        }
        level.sendParticles(p, ParticleTypes.CHERRY_LEAVES, true, false, c.x, c.y, c.z, 50, .7, .6, .7, .02);
        level.sendParticles(p, new DustParticleOptions(FoxfireFlame.SHIRAUME.core, 1.5f), true, false, c.x, c.y, c.z, 30, .35, .5, .35, 0);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1.2f, 1.1f);
    }

    // ---------------------------------------------------------------- 过场

    /** {@code p} 来找她说话：过场开始。 */
    static void begin(ShiraumeCompanion c, ServerPlayer p) {
        if (!(c.level() instanceof ServerLevel level)) return;
        boolean again = p.getData(Companions.BOND).pact();
        c.startPact(again);
        standOff(level, c, p);
        float yaw = (float) (Mth.atan2(p.getZ() - c.getZ(), p.getX() - c.getX()) * Mth.RAD_TO_DEG) - 90;
        c.setYRot(yaw);
        c.setYBodyRot(yaw);
        c.setYHeadRot(yaw);
        Vec3 fwd = Vec3.directionFromRotation(0, yaw);
        CameraShots.play(p, CameraShot.of(CameraPoint.on(c, local(SHOT_A, fwd)), CameraPoint.on(c, local(LOOK_A, fwd))).timing(14, BACK - 20, 6).letterbox());
        later(c, BACK, () -> CameraShots.play(p, CameraShot.of(CameraPoint.on(c, local(SHOT_B, fwd)), CameraPoint.on(c, local(LOOK_B, fwd)))
                .timing(8, END - BACK - 22, 14).letterbox()));
        level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, .8f, 1.3f);
    }

    /** 机位、看的地方（{@link #SHOT_A} 那样的三个数）换成相对她的坐标；{@code fwd} 是她面朝主人的水平方向。 */
    static Vec3 local(double[] v, Vec3 fwd) {
        Vec3 side = new Vec3(-fwd.z, 0, fwd.x);
        return fwd.scale(v[0]).add(side.scale(v[1])).add(0, v[2], 0);
    }

    /**
     * 主人贴在她身上（等待时她碰不到人，走得进去）：退开到 {@link #STAND_OFF} 格再开始。那里站得下（身子周围是空的；
     * 站在地上的人脚底常常陷进地面一丝，所以盒子收一点再量）、脚下有地才挪，不然不动。
     */
    private static void standOff(ServerLevel level, ShiraumeCompanion c, ServerPlayer p) {
        Vec3 flat = p.position().subtract(c.position()).multiply(1, 0, 1);
        if (flat.length() >= TOO_CLOSE) return;
        Vec3 dir = flat.lengthSqr() < 1e-4 ? Vec3.directionFromRotation(0, c.getYRot()) : flat.normalize();
        Vec3 to = c.position().add(dir.scale(STAND_OFF));
        BlockPos ground = BlockPos.containing(to.x, p.getY() - .3, to.z);
        if (level.noCollision(p, p.getBoundingBox().move(to.x - p.getX(), 0, to.z - p.getZ()).deflate(.01))
                && !level.getBlockState(ground).getCollisionShape(level, ground).isEmpty()) p.teleportTo(to.x, p.getY(), to.z);
    }

    private static void later(ShiraumeCompanion c, int at, Runnable run) {
        c.pactLater(at, run);
    }

    /** 过场的第 {@code t} 刻（{@link ShiraumeCompanion} 每刻调）。 */
    static void tick(ShiraumeCompanion c, ServerLevel level, ServerPlayer p, int t, boolean again) {
        String who = again ? "again_" : "pact_";
        switch (t) {
            case LINE_WAKE -> CompanionLines.say(p, who + "wake", 30);
            case LINE_1 -> CompanionLines.say(p, who + "1", 46);
            case SWORD -> c.setBladeShown(true);
            case PRESENT -> CompanionLines.say(p, who + "2", 34);
            case GIVE_SWORD -> {
                c.setBladeShown(false);
                give(p, new ItemStack(RyukaItem.ITEM.get()));
                puff(level, c.position().add(Vec3.directionFromRotation(0, c.getYRot()).scale(.35)).add(0, .55, 0));
            }
            case LINE_3 -> CompanionLines.say(p, who + "3", 40);
            case LINE_4 -> CompanionLines.say(p, who + "4", 46);
            case GIVE_KATASHIRO -> {
                give(p, again ? KatashiroItem.blank() : KatashiroItem.bound(p));
                puff(level, c.position().add(Vec3.directionFromRotation(0, c.getYRot()).scale(.4)).add(0, .45, 0));
                level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BOOK_PAGE_TURN, SoundSource.NEUTRAL, 1, 1.3f);
            }
            case STAND -> CompanionLines.say(p, who + "5", 60);
            case END -> finish(c, level, p, again);
            default -> {
            }
        }
    }

    /** 过场结束：第一次立契，她跟你走；已经立过契的，这位分霊化作花瓣回到你的形代里。 */
    private static void finish(ShiraumeCompanion c, ServerLevel level, ServerPlayer p, boolean again) {
        if (again) {
            c.vanishInto(p);
            return;
        }
        Companions.grantPact(p);
        c.becomeCompanion(p);
        Companions.join(p, c);
    }

    /**
     * 献上的东西先放进背包里手上以外的空格：手是空的话，原版会塞到手上，过场里拿在手上的流火会横在机位二前面（又被自己挡住）。
     * 没有别的空格才照原版放。
     */
    private static void give(ServerPlayer p, ItemStack stack) {
        var inv = p.getInventory();
        var items = inv.getNonEquipmentItems();
        for (int i = 0; i < items.size(); i++) {
            if (i != inv.getSelectedSlot() && items.get(i).isEmpty()) {
                inv.setItem(i, stack);
                return;
            }
        }
        if (!inv.add(stack)) p.drop(stack, false);
    }

    private static void puff(ServerLevel level, Vec3 at) {
        level.sendParticles(ParticleTypes.CHERRY_LEAVES, at.x, at.y, at.z, 14, .2, .15, .2, .02);
        level.sendParticles(new DustParticleOptions(FoxfireFlame.SHIRAUME.core, 1.1f), at.x, at.y, at.z, 10, .15, .1, .15, 0);
    }

    /** 已经立过契的那位分霊：化作花瓣、一缕淡粉狐火飞回主人身边。 */
    static void spirit(ServerLevel level, ShiraumeCompanion c, ServerPlayer p) {
        Companions.dissolve(c, 12);
        FoxfireBolt.spirit(level, c, c.position().add(0, .6, 0), p);
    }
}
