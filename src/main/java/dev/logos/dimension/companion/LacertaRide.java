package dev.logos.dimension.companion;

import dev.logos.dimension.LogosDimension;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 骑巨龙（接入方案 6.2）：巨龙形态时空手右键她就骑上去（潜行右键照样说话），Shift 下龙。骑着时由你的客户端算她怎么走（同骑马）：
 * <ul>
 *   <li>地上：W/A/S/D 走，疾跑跑；空格蹬地起飞。</li>
 *   <li>飞着：按住 W 顺着你的视线飞（抬头爬升、低头俯冲），疾跑更快；松开 W 惯性滑翔、慢慢往下沉；A/D 往两边挪；按住空格往上抬；
 *       碰到地面就落地。不穿方块。</li>
 *   <li>左键挥龙爪、按住右键喷龙炎（客户端把按键发过来，{@link Command}）。</li>
 *   <li>在半空下龙（自己按 Shift，或者你的魔力不够她变回人形）：这一摔只受 {@link #DISMOUNT_KEEP}，至少留半颗心。</li>
 * </ul>
 * 她的速度由这一侧每刻直接给（不关重力：关重力要同步，两边会打架；原版的移动是先按速度走、再减重力，下一刻再给就是了）。
 */
final class LacertaRide {
    /** 你坐在哪：她脚下往上、往前多少格（颈肩后方）。 */
    static final double SEAT_UP = 2.15, SEAT_FORWARD = .35;
    /** 地上走多快；飞多快（格/刻）、疾跑再乘多少；空格往上抬多快；松开 W 往下沉多快；每刻往想要的速度靠多少；起飞往上蹬多猛。 */
    static final float GROUND_SPEED = .3f;
    static final double FLY_SPEED = 1.1, SPRINT = 1.6, CLIMB = .55, SINK = .12, ACCEL = .12, TAKEOFF = .8, STRAFE = .35;
    /** 按住 S 时每刻往停下靠多少。 */
    static final double BRAKE = .2;
    /** 骑着时第三人称镜头离你多远（原版 4 格会埋在龙身里）。 */
    static final double CAMERA = 10;
    /** 在半空下龙：这一摔只受多少。 */
    static final double DISMOUNT_KEEP = .2;

    private final LacertaCompanion c;
    private boolean airborne;
    private int airTicks;
    private Vec3 vel = Vec3.ZERO;

    LacertaRide(LacertaCompanion c) {
        this.c = c;
    }

    boolean airborne() {
        return airborne;
    }

    /** 地上走的输入（飞着时由 {@link #tick} 直接给速度）。 */
    Vec3 input(Player rider) {
        if (!c.isDragonForm() || airborne) return Vec3.ZERO;
        return new Vec3(rider.xxa * .4, 0, rider.zza > 0 ? rider.zza : rider.zza * .5);
    }

    float speed(Player rider) {
        return GROUND_SPEED * (rider.isSprinting() ? 1.5f : 1f);
    }

    /** 每刻（两侧都调；只有算她怎么走的那一侧——你的客户端——给速度）。 */
    void tick(Player rider) {
        if (!c.isDragonForm()) return;
        float yaw = rider.getYRot();
        c.setYRot(yaw);
        c.yRotO = yaw;
        c.yBodyRot = yaw;
        c.setYHeadRot(yaw);
        if (!c.canSimulateMovement()) return;
        if (!airborne) {
            if (rider.isJumping() && c.onGround()) {
                airborne = true;
                airTicks = 0;
                Vec3 v = c.getDeltaMovement();
                vel = new Vec3(v.x, TAKEOFF, v.z);
                c.setDeltaMovement(vel);
            }
            return;
        }
        airTicks++;
        if (c.onGround() && airTicks > 8 && vel.y <= 0) {
            airborne = false;
            c.setDeltaMovement(vel.multiply(.5, 0, .5));
            return;
        }
        vel = fly(vel, rider.getLookAngle(), yaw, rider.zza, rider.xxa, rider.isJumping(), rider.isSprinting());
        c.setDeltaMovement(vel);
    }

    /**
     * 飞着时下一刻的速度：{@code forward}（W 为正、S 为负）、{@code strafe}（A 为正）、按着空格、疾跑。按住 W 顺着视线 {@code look}，
     * 松开惯性滑翔慢慢下沉，S 刹住（每刻往停下靠 {@link #BRAKE}，约半秒多停稳）；别的时候每刻往想要的速度靠 {@link #ACCEL}。
     * A/D 横移固定 {@link #STRAFE}（换掉横着的那一份，叠在惯性上会越按越快）。
     */
    static Vec3 fly(Vec3 vel, Vec3 look, float yaw, float forward, float strafe, boolean up, boolean sprint) {
        Vec3 want;
        if (forward > 0) want = look.scale(sprint ? FLY_SPEED * SPRINT : FLY_SPEED);
        else if (forward < 0) want = new Vec3(0, -SINK, 0);
        else want = new Vec3(vel.x * .985, -SINK, vel.z * .985);
        if (strafe != 0) {
            double r = Math.toRadians(yaw);
            Vec3 left = new Vec3(Math.cos(r), 0, Math.sin(r));
            want = want.subtract(left.scale(want.dot(left))).add(left.scale(strafe * STRAFE));
        }
        if (up) want = new Vec3(want.x, Math.max(want.y, CLIMB), want.z);
        return vel.add(want.subtract(vel).scale(forward < 0 ? BRAKE : ACCEL));
    }

    /** 你下来了：在半空就护着你这一摔。 */
    void dismounted(Entity passenger) {
        airborne = false;
        vel = Vec3.ZERO;
        if (!(passenger instanceof ServerPlayer p) || !(c.level() instanceof ServerLevel level)) return;
        // 从这一刻才开始往下掉（骑着的时候身上会攒下一截掉落距离，清掉）
        p.resetFallDistance();
        if (c.onGround() || LacertaFlight.groundBelow(level, p.position(), 4) < 3) return;
        LacertaFlight.protectFall(p, level.getGameTime(), DISMOUNT_KEEP);
    }

    // ---------------------------------------------------------------- 骑着时的按键

    /** 骑在巨龙背上按的键：{@link #CLAW} 左键挥爪、{@link #BREATH} 按住右键喷龙炎、{@link #BREATH_STOP} 松开。 */
    public record Command(int action) implements CustomPacketPayload {
        public static final int CLAW = 0, BREATH = 1, BREATH_STOP = 2;
        public static final Type<Command> TYPE = new Type<>(LogosDimension.id("lacerta_ride"));
        public static final StreamCodec<ByteBuf, Command> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Command::action, Command::new);

        @Override
        public Type<Command> type() {
            return TYPE;
        }
    }

    static void register(IEventBus bus) {
        bus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1").playToServer(Command.TYPE, Command.CODEC,
                (payload, context) -> {
                    if (context.player() instanceof ServerPlayer p) command(p, payload.action());
                }));
    }

    private static void command(ServerPlayer p, int action) {
        if (!(p.getVehicle() instanceof LacertaCompanion l) || !l.isDragonForm() || l.getControllingPassenger() != p
                || !(l.level() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        switch (action) {
            case Command.CLAW -> l.dragon.claw(now);
            case Command.BREATH -> l.dragon.startBreath(p::getLookAngle);
            case Command.BREATH_STOP -> l.dragon.stopBreath(level);
            default -> {
            }
        }
    }
}
