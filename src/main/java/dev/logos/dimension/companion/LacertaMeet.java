package dev.logos.dimension.companion;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.camera.CameraPoint;
import dev.logos.dimension.camera.CameraShot;
import dev.logos.dimension.camera.CameraShots;
import dev.logos.dimension.foxfire.FoxfireCue;
import dev.logos.dimension.foxfire.FoxfireFlame;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * 初遇（docs/LACERTA_COMPANION.md 第 3.1 节）：一场由烤肉引发的「天降誓约」。还没和她立契的你在高山（群系标签
 * {@code logos_dimension:lacerta_meet}：尖峭山峰、雪坡、裸岩山峰、冰封山峰、云岫峰林；或者海拔 {@link #HIGH} 以上）的露天营火上烤着生的东西，
 * 每个游戏日判定一次、三成几率，她循着香味从天上落下来，落在营火另一边：
 * <ol>
 *   <li>天降龙影：俯冲、展翅急刹落地（{@code summon}），营火的火星被热风卷起来；鼻尖先不争气地抽动（{@code act_sniff}、{@code face_foodie}），
 *       嘴硬一句「只是巡视领地刚好路过」。</li>
 *   <li>舌尖沦陷：你拿她最爱吃的右键她——吃（{@code eat}），红着脸硬拗一句（{@code act_hmph}），然后神色骤然庄重，平生第一次单膝跪下
 *       （{@code act_oath}，过场镜头），把一片烙着你名字的龙鳞递进你掌心。立契完成，她跟你走。给别的吃的她不吃（{@code refuse}），
 *       生的更不吃（「烤熟了再拿来！」），但脚底钉在营火边不挪。</li>
 *   <li>赌气离场：一分钟里没喂到她爱吃的，鼓着腮帮哼出两股烟，展翼飞走（明天闻到营火香还会来）。</li>
 * </ol>
 * 这场初遇只认你：别的玩家拿吃的给她，她连眼皮都懒得抬。跳过剧情的路：空白龙鳞（创造模式物品栏、山地远行小遗迹的宝箱）右键用，
 * 或命令 {@code /alchemy companion lacerta summon}。
 */
public final class LacertaMeet {
    /** 高山群系。 */
    public static final TagKey<Biome> MOUNTAINS = TagKey.create(Registries.BIOME, LogosDimension.id("lacerta_meet"));
    /** 不在高山群系时，脚下有多高也算高山。 */
    static final int HIGH = 120;
    /** 营火离你多远以内、上下几格；多久看一次（刻）；几率。 */
    static final int FIRE_NEAR = 5, FIRE_UP = 2, CHECK = 40;
    static final float CHANCE = .3f;
    /** 她落在营火另一边多远（格）。 */
    static final double LAND = 2.2;
    /** 等你喂多久（1 分钟）；隔多久再抽抽鼻子。 */
    static final int WAIT = 1200, SNIFF_AGAIN = 220;
    /** 吃完红着脸硬拗（act_hmph 2.8 秒）、单膝立誓（act_oath 4.6 秒）多长，立誓第几刻把龙鳞递给你。 */
    static final int PROUD = 56, OATH = 92, GIVE = 70;
    /** 赌气离场：哼完多久起飞、飞多久散去。 */
    static final int LEAVE_TAKEOFF = 34, LEAVE_FLY = 46;
    /** 你离开多远她就不等了。 */
    static final double GONE = 32;
    /** 立誓的机位和看的地方（相对她：沿她面朝你的方向、她的右手边、往上，格）：右前方，比跪着的她高一点。 */
    static final double[] SHOT = {2.2, 1.35, 1.05}, LOOK = {0, 0, .62};

    enum Stage { ARRIVE, WAIT, EAT, PROUD, OATH, LEAVE }

    private final LacertaCompanion c;
    private final BlockPos fire;
    private Stage stage = Stage.ARRIVE;
    private long stageAt, waitFrom, lastSniff;

    /** 测试用：判定必定成功；落下来过几次、立契几次、拒绝几次、飞走几次。 */
    public static boolean alwaysForTest;
    /** 测试用：等多久就飞走（0 照常一分钟）。 */
    public static int waitForTest;
    static int arrivals, pacts, refusals, leaves;

    LacertaMeet(LacertaCompanion c, BlockPos fire, long now) {
        this.c = c;
        this.fire = fire;
        this.stageAt = now;
    }

    // ---------------------------------------------------------------- 判定

    static void onPlayerTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p.tickCount % CHECK != 0 || p.isSpectator() || !p.isAlive()) return;
        if (Companions.bond(p, Companions.LACERTA_DEF).pact()) return;
        ServerLevel level = p.level();
        if (!mountain(level, p.blockPosition())) return;
        BlockPos at = cooking(level, p.blockPosition());
        if (at == null || waiting(level, p) != null) return;
        long day = level.getOverworldClockTime() / 24000;
        LacertaDays d = LacertaDays.of(p);
        if (d.meetDay() == day) return;
        // 这一天判定过了（中没中都算）
        p.setData(Companions.LACERTA_DAYS, d.withMeetDay(day));
        if (!alwaysForTest && p.getRandom().nextFloat() >= CHANCE) return;
        spawn(level, p, at);
    }

    /** 高山：群系在 {@link #MOUNTAINS} 里，或者海拔 {@link #HIGH} 以上。 */
    static boolean mountain(ServerLevel level, BlockPos at) {
        return at.getY() >= HIGH || level.getBiome(at).is(MOUNTAINS);
    }

    /** 你身边 {@link #FIRE_NEAR} 格内露天、点着、上面烤着东西的营火（没有为 null）。 */
    static @Nullable BlockPos cooking(ServerLevel level, BlockPos near) {
        for (BlockPos bp : BlockPos.betweenClosed(near.offset(-FIRE_NEAR, -FIRE_UP, -FIRE_NEAR), near.offset(FIRE_NEAR, FIRE_UP, FIRE_NEAR))) {
            BlockState s = level.getBlockState(bp);
            if (!(s.getBlock() instanceof CampfireBlock) || !s.getValue(CampfireBlock.LIT) || !level.canSeeSky(bp.above())) continue;
            if (!(level.getBlockEntity(bp) instanceof CampfireBlockEntity fire)) continue;
            for (ItemStack item : fire.getItems()) if (!item.isEmpty()) return bp.immutable();
        }
        return null;
    }

    /** 已经在等 {@code p} 的那一位。 */
    static @Nullable LacertaCompanion waiting(ServerLevel level, ServerPlayer p) {
        for (LacertaCompanion l : level.getEntitiesOfClass(LacertaCompanion.class, p.getBoundingBox().inflate(GONE))) {
            if (l.meeting() && p.getUUID().equals(l.ownerId())) return l;
        }
        return null;
    }

    /** 她循着 {@code fire} 的香味落下来，落在营火另一边、面朝 {@code p}（命令、测试也从这里进）。 */
    public static @Nullable LacertaCompanion spawn(ServerLevel level, ServerPlayer p, BlockPos fire) {
        LacertaCompanion c = Companions.LACERTA.get().create(level, EntitySpawnReason.TRIGGERED);
        if (c == null) return null;
        Vec3 center = Vec3.atBottomCenterOf(fire);
        Vec3 away = center.subtract(p.position()).multiply(1, 0, 1);
        away = away.lengthSqr() < 1e-4 ? Vec3.directionFromRotation(0, p.getYRot()) : away.normalize();
        Vec3 at = spot(level, c, center, away);
        float yaw = (float) (Mth.atan2(p.getZ() - at.z, p.getX() - at.x) * Mth.RAD_TO_DEG) - 90;
        c.snapTo(at.x, at.y, at.z, yaw, 0);
        c.setYHeadRot(yaw);
        c.setYBodyRot(yaw);
        long now = level.getGameTime();
        c.meetFor(p, new LacertaMeet(c, fire, now));
        c.mood.play("summon", LacertaCompanion.SUMMON_TICKS);
        level.addFreshEntity(c);
        // 头顶先掠过一道翼影：热风卷起营火的火星
        level.playSound(null, center.x, center.y, center.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.NEUTRAL, 1.4f, .8f);
        level.sendParticles(ParticleTypes.LAVA, center.x, center.y + .6, center.z, 10, .3, .2, .3, 0);
        level.sendParticles(ParticleTypes.FLAME, center.x, center.y + .5, center.z, 16, .4, .4, .4, .06);
        arrivals++;
        return c;
    }

    /** 落点：营火另一边 {@link #LAND} 格、脚下有地、站得下；不行就绕着营火换个方向。 */
    private static Vec3 spot(ServerLevel level, LacertaCompanion c, Vec3 center, Vec3 away) {
        for (int k = 0; k < 8; k++) {
            double a = (k % 2 == 0 ? 1 : -1) * Math.PI / 4 * ((k + 1) / 2);
            Vec3 dir = away.yRot((float) a);
            Vec3 at = ground(level, center.add(dir.scale(LAND)));
            if (at != null && level.noCollision(c, c.getDimensions(net.minecraft.world.entity.Pose.STANDING).makeBoundingBox(at))
                    && level.canSeeSky(BlockPos.containing(at).above())) return at;
        }
        Vec3 at = ground(level, center.add(away.scale(LAND)));
        return at != null ? at : center.add(away.scale(LAND));
    }

    private static @Nullable Vec3 ground(ServerLevel level, Vec3 near) {
        BlockPos at = BlockPos.containing(near);
        for (int dy = 2; dy >= -3; dy--) {
            BlockPos feet = at.above(dy);
            if (!level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty() && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                    && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) {
                return new Vec3(near.x, feet.getY(), near.z);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 每刻

    void tick(ServerLevel level, long now) {
        c.getNavigation().stop();
        ServerPlayer p = c.ownerId() == null ? null : level.getServer().getPlayerList().getPlayer(c.ownerId());
        boolean here = p != null && p.isAlive() && p.level() == level && p.distanceTo(c) <= GONE;
        if (!here && stage != Stage.LEAVE) {
            leave(level, null, now);
        }
        long t = now - stageAt;
        switch (stage) {
            case ARRIVE -> {
                c.summonEffects(level, t);
                if (t >= LacertaCompanion.SUMMON_TICKS) {
                    to(Stage.WAIT, now);
                    waitFrom = now;
                    sniff(now);
                    if (p != null) CompanionLines.say(c, p, "meet_sniff", 110);
                }
            }
            case WAIT -> {
                if (p != null) c.getLookControl().setLookAt(p, 30, 30);
                if (now - lastSniff >= SNIFF_AGAIN) sniff(now);
                if (now - waitFrom >= (waitForTest > 0 ? waitForTest : WAIT)) leave(level, p, now);
            }
            case EAT -> {
                if (p != null) {
                    c.getLookControl().setLookAt(p, 30, 30);
                    c.tickEating(level, now, p);
                }
                if (!c.eating()) {
                    to(Stage.PROUD, now);
                    c.mood.act("act_hmph");
                    c.mood.talk("face_flustered", now, PROUD + 20);
                    if (p != null) CompanionLines.say(c, p, "meet_fed", 120);
                }
            }
            case PROUD -> {
                if (p != null) c.getLookControl().setLookAt(p, 30, 30);
                if (t >= PROUD && p != null) oath(level, p, now);
            }
            case OATH -> {
                if (t == GIVE && p != null) give(level, p);
                if (t >= OATH && p != null) {
                    Companions.grantPact(p, Companions.LACERTA_DEF);
                    c.joinAfterMeet(p);
                    pacts++;
                }
            }
            case LEAVE -> tickLeave(level, t);
        }
        c.mood.tick(now);
    }

    private void to(Stage s, long now) {
        stage = s;
        stageAt = now;
    }

    /** 鼻尖抽动、两眼放光（朝着营火）。 */
    private void sniff(long now) {
        lastSniff = now;
        c.mood.act("act_sniff");
        c.mood.talk("face_foodie", now, 140);
    }

    /** 神色骤然庄重：单膝跪下立誓（过场镜头），第 {@link #GIVE} 刻把鳞递给你。 */
    private void oath(ServerLevel level, ServerPlayer p, long now) {
        to(Stage.OATH, now);
        standOff(level, p);
        float yaw = (float) (Mth.atan2(p.getZ() - c.getZ(), p.getX() - c.getX()) * Mth.RAD_TO_DEG) - 90;
        c.setYRot(yaw);
        c.setYBodyRot(yaw);
        c.setYHeadRot(yaw);
        c.mood.act("act_oath");
        c.mood.talk("face_poise", now, OATH);
        Vec3 fwd = Vec3.directionFromRotation(0, yaw);
        CameraShots.play(p, CameraShot.of(CameraPoint.on(c, ShiraumePact.local(SHOT, fwd)), CameraPoint.on(c, ShiraumePact.local(LOOK, fwd)))
                .timing(12, OATH - 24, 12).letterbox());
        level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, 1, .8f);
    }

    /** 你贴着她（她碰不到人）：退开到 {@link ShiraumePact#STAND_OFF} 格再立誓，不然机位被你挡住（同白梅的立契）。 */
    private void standOff(ServerLevel level, ServerPlayer p) {
        Vec3 flat = p.position().subtract(c.position()).multiply(1, 0, 1);
        if (flat.length() >= ShiraumePact.TOO_CLOSE) return;
        Vec3 dir = flat.lengthSqr() < 1e-4 ? Vec3.directionFromRotation(0, c.getYRot()) : flat.normalize();
        Vec3 to = c.position().add(dir.scale(ShiraumePact.STAND_OFF));
        BlockPos ground = BlockPos.containing(to.x, p.getY() - .3, to.z);
        if (level.noCollision(p, p.getBoundingBox().move(to.x - p.getX(), 0, to.z - p.getZ()).deflate(.01))
                && !level.getBlockState(ground).getCollisionShape(level, ground).isEmpty()) p.teleportTo(to.x, p.getY(), to.z);
    }

    /** 从心口的鳞隙间摘下一片，吹一口龙息烙上你的名字，递进你掌心（放进背包里手上以外的空格）。 */
    private void give(ServerLevel level, ServerPlayer p) {
        ItemStack scale = DragonScaleItem.bound(p);
        var inv = p.getInventory();
        var items = inv.getNonEquipmentItems();
        boolean put = false;
        for (int i = 0; i < items.size() && !put; i++) {
            if (i != inv.getSelectedSlot() && items.get(i).isEmpty()) {
                inv.setItem(i, scale);
                put = true;
            }
        }
        if (!put && !inv.add(scale)) p.drop(scale, false);
        Vec3 hand = c.position().add(Vec3.directionFromRotation(0, c.yBodyRot).scale(.4)).add(0, .7, 0);
        FoxfireCue.burst(level, hand, FoxfireFlame.DRAGON, .5f);
        level.sendParticles(new DustParticleOptions(LacertaCompanion.GOLD, .9f), hand.x, hand.y, hand.z, 10, .12, .1, .12, 0);
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1, 1.3f);
    }

    /** 不等了：你在的话鼓着腮帮哼出两股烟、撂下一句，展翼飞走。 */
    private void leave(ServerLevel level, @Nullable ServerPlayer p, long now) {
        if (stage == Stage.LEAVE) return;
        to(Stage.LEAVE, now);
        c.stopEating();
        c.mood.cut();
        c.mood.pouted(now);
        c.mood.act("act_hmph");
        if (p != null) CompanionLines.say(c, p, "meet_leave", 60);
        leaves++;
    }

    private void tickLeave(ServerLevel level, long t) {
        if (t == 9 || t == 15) {
            Vec3 nose = c.getEyePosition().add(Vec3.directionFromRotation(0, c.yBodyRot).scale(.25));
            level.sendParticles(ParticleTypes.SMOKE, nose.x, nose.y, nose.z, 4, .04, .02, .04, .01);
        }
        if (t == LEAVE_TAKEOFF) {
            c.setNoGravity(true);
            c.setFlying(true);
            level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.ENDER_DRAGON_FLAP, SoundSource.NEUTRAL, 1, 1.2f);
        }
        if (t > LEAVE_TAKEOFF) {
            Vec3 f = Vec3.directionFromRotation(0, c.yBodyRot);
            c.setDeltaMovement(f.scale(-.12).add(0, .42, 0));
        }
        if (t >= LEAVE_TAKEOFF + LEAVE_FLY) {
            c.puff(level, c.position().add(0, .8, 0));
            c.discard();
        }
    }

    // ---------------------------------------------------------------- 喂她

    /** 等你喂的时候 {@code sp}（她等的那个人）右键她。 */
    InteractionResult interact(ServerPlayer sp) {
        if (stage != Stage.WAIT || sp.isShiftKeyDown()) return InteractionResult.SUCCESS;
        ItemStack held = sp.getMainHandItem();
        CompanionFood.Taste taste = CompanionFood.taste(LacertaCompanion.ID, held);
        if (taste == CompanionFood.Taste.NOT_FOOD) return InteractionResult.SUCCESS;
        long now = c.level().getGameTime();
        if (taste == CompanionFood.Taste.FAVORITE) {
            if (!c.startEating(held, true)) return InteractionResult.SUCCESS;
            if (!sp.getAbilities().instabuild) held.shrink(1);
            to(Stage.EAT, now);
            c.mood.talk("face_foodie", now, CompanionEntity.EAT_TICKS);
            return InteractionResult.SUCCESS;
        }
        // 不吃：生的「烤熟了再拿来」（鼻孔喷烟），别的「这种东西也敢端到真龙面前」。脚底照样钉在营火边，再等你换
        boolean raw = raw((ServerLevel) c.level(), held);
        c.mood.act("refuse");
        c.mood.pouted(now);
        if (raw) {
            Vec3 nose = c.getEyePosition().add(Vec3.directionFromRotation(0, c.yBodyRot).scale(.25));
            ((ServerLevel) c.level()).sendParticles(ParticleTypes.SMOKE, nose.x, nose.y, nose.z, 6, .04, .02, .04, .01);
        }
        CompanionLines.say(c, sp, raw ? "meet_raw" : "meet_refuse", 60);
        refusals++;
        return InteractionResult.SUCCESS;
    }

    /** 生的：烟熏炉烤得熟。 */
    static boolean raw(ServerLevel level, ItemStack s) {
        return !s.isEmpty() && level.recipeAccess().getRecipeFor(RecipeType.SMOKING, new SingleRecipeInput(s.copyWithCount(1)), level).isPresent();
    }

    String describe() {
        return stage.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** 测试用：落下来过几次、立契几次、拒绝几次、飞走几次。 */
    public static int[] counts() {
        return new int[]{arrivals, pacts, refusals, leaves};
    }
}
