package dev.logos.dimension.companion;

import dev.logos.dimension.companion.KohakuLife.Plan;
import dev.logos.dimension.companion.KohakuLife.Pose;
import dev.logos.dimension.companion.KohakuLife.Spot;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 琥珀睡觉（docs/KOHAKU_COMPANION.md 第 9 节）。睡的时候走 {@link KohakuLife} 的姿势（心智的 LOUNGE，分比通用的「休息」高）：
 * <ul>
 *   <li>跟着你睡：你躺下，她也睡。旁边有一张空床就占那张；你叫出来的白梅蜷在床边，她贴着白梅的背蜷着；不然贴着你的床边睡在地上，脸朝着你。
 *       羁绊到「亲近」以后一成会挤上你的床，缩在床边（彩蛋，见下）。</li>
 *   <li>睡姿（一晚定一次）：经典侧卧（默认）；猫饼三成；羁绊到「信赖」或第五枚爪之契以后，露肚皮四成。</li>
 *   <li>赖床：你起来以后她再睡 3–6 秒，然后（侧卧时）动一动、翻个身，慢慢起来，伸个懒腰，之后一阵子是困的。
 *       挤上你床的那一晚，你醒来时她已经横着摊成「大」字（你被挤下了床），再多睡几秒。</li>
 *   <li>一起睡过一整夜（这一晚真的跳过去了、她睡够了）：你醒来就有「琥珀相伴」；七成（羁绊每段 +5%，最多九成）她起来以后递给你一样东西。</li>
 *   <li>你挂机时：每次挂机判一次（三成），12 格内有空床就过去在床上打盹（多半是猫饼）。</li>
 * </ul>
 */
final class KohakuSleep {
    /** 她躺下以后睡了多久（刻）才算一起睡过（你躺下 5 秒就跳过夜晚，她走过来躺下要一会）；这一晚跳过去了：时钟比经过的刻数多走了这么多。 */
    static final int SLEEP_TICKS = 20, NIGHT_SKIPPED = 1000;
    /** 赖床多久（刻）；「大」字那一晚多睡一会。 */
    static final int LINGER_MIN = 60, LINGER_MAX = 120, SPRAWL_LINGER = 120;
    /** 醒来以后困多久（刻）。 */
    static final int SLEEPY = 600;
    /** 睡姿：露肚皮（羁绊「信赖」或第五枚以后）、猫饼；挤上你的床（羁绊「亲近」以后）。 */
    static final float BELLY_CHANCE = .4f, LOAF_CHANCE = .3f, YOURS_CHANCE = .1f;
    static final CompanionBond.Stage BELLY_STAGE = CompanionBond.Stage.TRUSTED, YOURS_STAGE = CompanionBond.Stage.CLOSE;
    static final int BELLY_SEALS = 5;
    /** 旁边的空床：离你的床头几格内；挂机时去床上打盹：几成、多远内的床、猫饼几成。 */
    static final int SPARE_RANGE = 3, NAP_RANGE = 12;
    static final float NAP_CHANCE = .3f, NAP_LOAF = .7f;
    /** 醒来的礼物：七成起、羁绊每段 +5%、最多九成；药水、猫币、稀有各占几份（收了零花钱的第二天早上稀有翻倍）。 */
    static final float GIFT_CHANCE = .7f, GIFT_STAGE = .05f, GIFT_MAX = .9f;
    static final int[] GIFT_WEIGHTS = {45, 30, 25};
    /** 稀有里各占几份：她自带术式的拓本、空白的载页（她手里长杖同档）、魔力结晶、魔力晶石、炼金材料、符文家族书。 */
    static final int[] RARE_WEIGHTS = {40, 10, 15, 5, 20, 10};
    /** 拓本只拓你也能用的页（要用到「主人」「她自己」的页不拓）。 */
    static final List<KohakuSpells> COPIES = List.of(KohakuSpells.ONE_DROP, KohakuSpells.NAP_WARD, KohakuSpells.GREENHOUSE, KohakuSpells.TIME_AMBER,
            KohakuSpells.MISTY_UMBRELLA);
    static final List<String> ALCHEMY = List.of("philosophers_mercury", "rebis", "raven_head", "red_lion", "aether", "quintessence", "starlight_moss",
            "mandrake_root");
    static final List<String> RUNE_BOOKS = List.of("form_book", "targeting_book", "change_book", "law_book", "number_book", "motion_book");
    static final List<String> ELEMENTS = List.of("physical", "fire", "frost", "lightning", "void", "arcane", "life");

    /** 她睡在哪。 */
    enum Where { FLOOR, SPARE, SHIRAUME, YOURS }

    /** 测试用：强行定这一晚的睡姿、睡哪（null 照常判）；一起睡过几整夜、给过几次醒来的礼物、在床上打过几次盹。 */
    static @Nullable Pose forcePose;
    static @Nullable Where forceWhere;
    static int fullNights, morningGifts, bedNaps;

    private final KohakuCompanion c;
    private final KohakuLife life;
    /** 这一晚：你睡着了没有、睡下那一刻的时钟和刻数、定好的睡姿、要不要挤上你的床、睡在哪、她什么时候睡着的。 */
    private boolean ownerAsleep, yours;
    private long clockAtBed, tickAtBed, asleepSince = -1;
    private @Nullable Pose nightPose;
    private @Nullable Where where;
    private @Nullable Plan night;
    /** 赖床到哪一刻（没在赖为 -1）；这一晚算不算一整夜。 */
    private long lingerUntil = -1;
    private boolean fullNight;
    /** 挂机时去床上打盹：这一回挂机判过没有、判成了没有、躺哪种。 */
    private boolean napRolled, napWanted;
    private Pose napPose = Pose.LOAF;

    KohakuSleep(KohakuCompanion c, KohakuLife life) {
        this.c = c;
        this.life = life;
    }

    /** 测试用：这一晚睡在哪（没在跟你睡为空）。 */
    String where() {
        return where == null || !holds() ? "" : where.name().toLowerCase(java.util.Locale.ROOT);
    }

    String describe() {
        return ownerAsleep ? "night(" + where + "," + nightPose + ")" : lingerUntil >= 0 ? "linger" : "";
    }

    /** 赖床或睡着的那一晚（这时陪你的打算就是它）。 */
    boolean holds() {
        return ownerAsleep || lingerUntil >= 0;
    }

    /** 她正睡在那一晚的地方（姿势摆好了、闭着眼）。 */
    private boolean asleep() {
        return life.asleep() && life.posedAt() == Spot.NIGHT;
    }

    /**
     * 你睡着、赖床时她怎么睡（每半秒）。你刚躺下时定这一晚的睡姿；你醒来那一刻开始赖床，赖完起来（伸懒腰），一起睡过一整夜就给你
     * 琥珀相伴、有概率递礼物。没睡在一起返回 null。
     */
    @Nullable Plan night(ServerLevel level, ServerPlayer p, long now, boolean calm) {
        boolean sleeping = p.isSleeping();
        if (sleeping && !ownerAsleep) startNight(level, p, now);
        if (!sleeping && ownerAsleep) {
            ownerAsleep = false;
            wake(level, p, now);
        }
        if (lingerUntil >= 0) {
            if (now < lingerUntil) return night;
            finishLinger(level, p, now);
            return null;
        }
        if (!ownerAsleep || !calm) return null;
        if (asleep() && night != null) {
            if (asleepSince < 0) asleepSince = now;
            return night;
        }
        night = place(level, p);
        return night;
    }

    private void startNight(ServerLevel level, ServerPlayer p, long now) {
        ownerAsleep = true;
        clockAtBed = level.getOverworldClockTime();
        tickAtBed = now;
        asleepSince = -1;
        fullNight = false;
        CompanionBond.Stage stage = CompanionBond.Stage.of(c.bond(p).affection());
        RandomSource r = c.getRandom();
        yours = forceWhere == Where.YOURS || forceWhere == null && stage.ordinal() >= YOURS_STAGE.ordinal() && r.nextFloat() < YOURS_CHANCE;
        nightPose = forcePose != null ? forcePose : pose(stage, c.seals(), r.nextFloat(), r.nextFloat());
        night = null;
    }

    /** 这一晚的睡姿：露肚皮（羁绊「信赖」或第五枚以后四成）、猫饼三成、不然侧卧。 */
    static Pose pose(CompanionBond.Stage stage, int seals, float belly, float loaf) {
        if ((stage.ordinal() >= BELLY_STAGE.ordinal() || seals >= BELLY_SEALS) && belly < BELLY_CHANCE) return Pose.BELLY;
        return loaf < LOAF_CHANCE ? Pose.LOAF : Pose.CURL;
    }

    /** 睡哪：旁边的空床、贴着白梅的背、挤上你的床、贴着你的床边（按这个先后，彩蛋那一晚直接挤上你的床）。 */
    private @Nullable Plan place(ServerLevel level, ServerPlayer p) {
        BlockPos bed = p.getSleepingPos().orElse(null);
        if (bed == null || !(level.getBlockState(bed).getBlock() instanceof BedBlock)) return null;
        Direction facing = level.getBlockState(bed).getValue(BedBlock.FACING);
        Vec3 yourCenter = bedCenter(bed, facing);
        Pose pose = nightPose == null ? Pose.CURL : nightPose;
        if (yours) {
            where = Where.YOURS;
            // 缩在你的床边上，脸朝着你
            Direction side = facing.getClockWise();
            Vec3 at = yourCenter.add(new Vec3(side.getStepX(), 0, side.getStepZ()).scale(.32));
            return plan(at, Pose.CURL, side.getOpposite().toYRot());
        }
        BlockPos spare = forceWhere == null || forceWhere == Where.SPARE ? spareBed(level, bed) : null;
        if (spare != null) {
            where = Where.SPARE;
            Direction f = level.getBlockState(spare).getValue(BedBlock.FACING);
            Vec3 at = bedCenter(spare, f);
            return plan(at, pose, onBedYaw(pose, f, at, yourCenter));
        }
        ShiraumeCompanion s = forceWhere == null || forceWhere == Where.SHIRAUME ? Companions.out(p) : null;
        if (s != null && s.mood.curled() && s.distanceTo(p) < 4) {
            where = Where.SHIRAUME;
            // 贴着白梅的背，和她朝同一边蜷着
            Vec3 back = Vec3.directionFromRotation(0, s.yBodyRot).scale(-.6);
            Vec3 at = life.standAt(level, s.position().add(back));
            if (at != null) return plan(at, Pose.CURL, s.yBodyRot);
        }
        where = Where.FLOOR;
        // 贴着你的床边：床头、床脚两侧里离她近的、站得住的那一格，往床那边靠一点；脸朝着你
        BlockPos foot = bed.relative(facing.getOpposite());
        Vec3 best = null;
        Direction bestSide = null;
        for (BlockPos part : new BlockPos[] {bed, foot}) {
            for (Direction side : new Direction[] {facing.getClockWise(), facing.getCounterClockWise()}) {
                Vec3 at = life.standAt(level, Vec3.atBottomCenterOf(part.relative(side)).subtract(new Vec3(side.getStepX(), 0, side.getStepZ()).scale(.2)));
                if (at != null && (best == null || at.distanceToSqr(c.position()) < best.distanceToSqr(c.position()) - (part == bed ? 1 : 0))) {
                    best = at;
                    bestSide = side;
                }
            }
        }
        if (best == null) return null;
        // 侧卧、猫饼：脸朝着床（你）；露肚皮：头朝床头那边
        float yaw = pose == Pose.BELLY ? facing.getOpposite().toYRot() : bestSide.getOpposite().toYRot();
        return plan(best, pose, yaw);
    }

    private static Plan plan(Vec3 at, Pose pose, float yaw) {
        return new Plan(Spot.NIGHT, at, null, true, false, pose, yaw);
    }

    /** 躺在一张床上的朝向：侧卧时脸朝着你的床；猫饼头朝床头；露肚皮、「大」字头枕在床头。 */
    static float onBedYaw(Pose pose, Direction facing, Vec3 at, @Nullable Vec3 you) {
        return switch (pose) {
            case LOAF -> facing.toYRot();
            case BELLY -> facing.getOpposite().toYRot();
            default -> {
                if (you == null) yield facing.getClockWise().toYRot();
                Vec3 d = you.subtract(at);
                yield (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            }
        };
    }

    /** 床的正中（两格之间），床面上（9/16 格高）。 */
    static Vec3 bedCenter(BlockPos head, Direction facing) {
        return Vec3.atBottomCenterOf(head).add(new Vec3(facing.getOpposite().getStepX(), 0, facing.getOpposite().getStepZ()).scale(.5)).add(0, 9 / 16.0, 0);
    }

    /** 你的床头 {@link #SPARE_RANGE} 格内另一张没人睡的床（床头那一格）。 */
    private static @Nullable BlockPos spareBed(ServerLevel level, BlockPos yours) {
        BlockPos best = null;
        for (BlockPos pos : BlockPos.betweenClosed(yours.offset(-SPARE_RANGE, -1, -SPARE_RANGE), yours.offset(SPARE_RANGE, 1, SPARE_RANGE))) {
            if (pos.equals(yours) || !emptyBedHead(level.getBlockState(pos))) continue;
            if (best == null || pos.distSqr(yours) < best.distSqr(yours)) best = pos.immutable();
        }
        return best;
    }

    static boolean emptyBedHead(BlockState s) {
        return s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.HEAD && !s.getValue(BedBlock.OCCUPIED);
    }

    // ---------------------------------------------------------------- 醒来

    /** 你醒了：她睡着的话开始赖床（挤上你床的那一晚换成「大」字、多睡一会）；这一晚真的跳过去了、她睡够了就算一整夜。 */
    private void wake(ServerLevel level, ServerPlayer p, long now) {
        boolean skipped = level.getOverworldClockTime() - clockAtBed - (now - tickAtBed) > NIGHT_SKIPPED;
        fullNight = asleep() && asleepSince >= 0 && now - asleepSince >= SLEEP_TICKS && skipped;
        if (!asleep()) {
            night = null;
            return;
        }
        if (where == Where.YOURS) {
            // 你被挤下了床：她已经横着摊成「大」字，躺在床正中
            BlockPos bed = p.getSleepingPos().orElse(null);
            Plan pl = night;
            if (pl != null) {
                Vec3 at = pl.at();
                float yaw = pl.yaw();
                if (bed == null && level.getBlockState(BlockPos.containing(at)).getBlock() instanceof BedBlock) bed = BlockPos.containing(at);
                if (bed != null && level.getBlockState(bed).getBlock() instanceof BedBlock) {
                    Direction f = level.getBlockState(bed).getValue(BedBlock.FACING);
                    at = bedCenter(bed, f);
                    yaw = f.getClockWise().toYRot();
                }
                night = plan(at, Pose.SPRAWL, yaw);
                life.switchPose(night, now);
            }
            lingerUntil = now + SPRAWL_LINGER;
        } else {
            lingerUntil = now + LINGER_MIN + c.getRandom().nextInt(LINGER_MAX - LINGER_MIN + 1);
        }
    }

    /** 赖够了：起来（侧卧动一动翻个身再起来），伸懒腰；一整夜的话给你琥珀相伴、有概率备好礼物（起来以后递）。 */
    private void finishLinger(ServerLevel level, ServerPlayer p, long now) {
        lingerUntil = -1;
        night = null;
        life.wakeUp(level, now, SLEEPY);
        if (!fullNight) return;
        fullNight = false;
        fullNights++;
        int stage = CompanionBond.Stage.of(c.bond(p).affection()).ordinal();
        p.addEffect(new MobEffectInstance(KohakuCompany.EFFECT, KohakuCompany.TICKS, stage, false, true, true));
        RandomSource r = c.getRandom();
        if (!KohakuLife.lucky && r.nextFloat() >= giftChance(stage)) return;
        long day = level.getOverworldClockTime() / 24000;
        boolean doubled = KohakuLife.days(p).allowanceDay() == day - 1;
        ItemStack gift = gift(level, p, r, category(r.nextInt(total(doubled)), doubled));
        if (gift.isEmpty()) return;
        morningGifts++;
        life.offer(gift, "morning_gift");
    }

    /** 醒来的礼物有多大概率：七成起、羁绊每段 +5%、最多九成。 */
    static float giftChance(int stage) {
        return Math.min(GIFT_MAX, GIFT_CHANCE + GIFT_STAGE * Math.max(0, stage));
    }

    /** 三类加起来几份（收了零花钱的第二天早上稀有翻倍）。 */
    static int total(boolean doubled) {
        return GIFT_WEIGHTS[0] + GIFT_WEIGHTS[1] + GIFT_WEIGHTS[2] * (doubled ? 2 : 1);
    }

    /** {@code roll}（0 到 {@link #total} - 1）落在哪一类：0 药水、1 猫币、2 稀有。 */
    static int category(int roll, boolean doubled) {
        if (roll < GIFT_WEIGHTS[0]) return 0;
        if (roll < GIFT_WEIGHTS[0] + GIFT_WEIGHTS[1]) return 1;
        return 2;
    }

    /** 按份数挑：{@code roll} 在 0 到份数之和之间。 */
    static int pick(int[] weights, int roll) {
        for (int i = 0; i < weights.length; i++) {
            roll -= weights[i];
            if (roll < 0) return i;
        }
        return weights.length - 1;
    }

    private ItemStack gift(ServerLevel level, ServerPlayer p, RandomSource r, int category) {
        return switch (category) {
            case 0 -> potion(r);
            case 1 -> {
                int k = r.nextInt(100);
                yield k < 60 ? new ItemStack(item("logos_dimension", "cat_penny"), 3 + r.nextInt(6))
                        : k < 93 ? new ItemStack(item("logos_dimension", "cat_shilling"), 1 + r.nextInt(2)) : new ItemStack(item("logos_dimension", "cat_sovereign"));
            }
            default -> rare(level, r);
        };
    }

    /** 药水：一半是魔力涌流（普通、强效、更强），一半是随机一种元素之力（普通、强效）。 */
    private static ItemStack potion(RandomSource r) {
        String id;
        if (r.nextBoolean()) {
            int k = r.nextInt(100);
            id = k < 50 ? "mana_flow" : k < 85 ? "strong_mana_flow" : "greater_mana_flow";
        } else {
            String element = ELEMENTS.get(r.nextInt(ELEMENTS.size())) + "_power";
            id = r.nextInt(100) < 65 ? element : "strong_" + element;
        }
        var holder = BuiltInRegistries.POTION.get(Identifier.fromNamespaceAndPath("logos", id));
        return holder.map(h -> PotionContents.createItemStack(Items.POTION, h)).orElse(ItemStack.EMPTY);
    }

    /** 稀有：她自带术式的拓本（你也能用的那几页里、她已经会的，纸和她手里的长杖同档）、空白载页、魔力结晶、魔力晶石、炼金材料、符文家族书。 */
    private ItemStack rare(ServerLevel level, RandomSource r) {
        PageTier tier = c.staff.pageTier();
        return switch (pick(RARE_WEIGHTS, r.nextInt(sum(RARE_WEIGHTS)))) {
            case 0 -> {
                List<KohakuSpells> known = COPIES.stream().filter(s -> s.unlocked(c.seals())).toList();
                var spell = known.get(r.nextInt(known.size())).spell(level.getServer(), false);
                yield spell == null ? new ItemStack(tier.blank()) : SpellPages.inscribed(tier, spell.source(), null);
            }
            case 1 -> new ItemStack(tier.blank());
            case 2 -> new ItemStack(item("logos", "mana_crystal"));
            case 3 -> new ItemStack(item("logos", "mana_gemstone"));
            case 4 -> new ItemStack(item("logos", ALCHEMY.get(r.nextInt(ALCHEMY.size()))));
            default -> new ItemStack(item("logos", RUNE_BOOKS.get(r.nextInt(RUNE_BOOKS.size()))));
        };
    }

    static int sum(int[] w) {
        int s = 0;
        for (int v : w) s += v;
        return s;
    }

    private static Item item(String ns, String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(ns, id));
    }

    // ---------------------------------------------------------------- 挂机时去床上打盹

    /** 你挂机了：这一回判一次（三成），12 格内有空床就去床上打盹（七成猫饼）；不挂机了就重新判。 */
    @Nullable Plan bedNap(ServerLevel level, ServerPlayer p, boolean afk) {
        if (!afk) {
            napRolled = false;
            napWanted = false;
            return null;
        }
        if (!napRolled) {
            napRolled = true;
            napWanted = KohakuLife.lucky || c.getRandom().nextFloat() < NAP_CHANCE;
            napPose = forcePose != null ? forcePose : c.getRandom().nextFloat() < NAP_LOAF ? Pose.LOAF : Pose.CURL;
        }
        if (!napWanted) return null;
        BlockPos best = null;
        BlockPos base = p.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(base.offset(-NAP_RANGE, -2, -NAP_RANGE), base.offset(NAP_RANGE, 2, NAP_RANGE))) {
            if (!emptyBedHead(level.getBlockState(pos))) continue;
            if (best == null || pos.distSqr(c.blockPosition()) < best.distSqr(c.blockPosition())) best = pos.immutable();
        }
        if (best == null) return null;
        Direction f = level.getBlockState(best).getValue(BedBlock.FACING);
        Vec3 at = bedCenter(best, f);
        return new Plan(Spot.BED, at, null, true, false, napPose, onBedYaw(napPose, f, at, null));
    }

    /** 在床上躺下了（数一次）。 */
    static void napped() {
        bedNaps++;
    }
}
