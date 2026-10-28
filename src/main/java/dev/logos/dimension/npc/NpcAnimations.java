package dev.logos.dimension.npc;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.animation.object.LoopType;
import com.geckolib.animation.object.PlayState;
import com.geckolib.cache.GeckoLibResources;
import com.geckolib.cache.animation.BakedAnimations;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ChargedProjectiles;

/**
 * NPC 的 GeckoLib 动画驱动，沿用车万女仆（TLM）GeckoLib 女仆的控制器结构、动画名与优先级，
 * 使同一份动画文件在附属模组和 TLM 模型包里都能用（docs/NPC_ANIMATIONS.md）。
 *
 * <p>控制器（按注册顺序，后注册的覆盖先注册的同一骨骼）：
 * {@code pre_parallel_0..7}、{@code main}、{@code hold_offhand}、{@code hold_mainhand}、{@code swing}、
 * {@code use}、{@code misc}、{@code parallel_0..7}。
 *
 * <p>与 TLM 的差别：只播放动画文件里存在的动画名（主状态缺失时顺延到下一个成立的状态，
 * GeckoLib 遇到缺失的动画会每帧报错）；不含女仆专属的棋盘、椅子游戏、乞求、魔法、枪械与盔甲控制器；
 * {@code misc} 改为由 NPC 自己给出的剧情动作。
 */
public final class NpcAnimations {
    // ---- 主控制器的状态名（与 TLM 同名）
    public static final String DEATH = "death";
    public static final String SLEEP = "sleep";
    public static final String SWIM = "swim";
    public static final String LADDER_UP = "ladder_up";
    public static final String LADDER_STILLNESS = "ladder_stillness";
    public static final String LADDER_DOWN = "ladder_down";
    public static final String BOAT = "boat";
    public static final String CHAIR = "chair";
    public static final String SIT = "sit";
    public static final String SWIM_STAND = "swim_stand";
    public static final String ATTACKED = "attacked";
    public static final String JUMP = "jump";
    public static final String RUN = "run";
    public static final String WALK = "walk";
    public static final String IDLE = "idle";

    // ---- 手部控制器的默认名与条件名前缀
    public static final String HOLD_MAINHAND = "hold_mainhand";
    public static final String HOLD_OFFHAND = "hold_offhand";
    public static final String SWING = "swing";
    public static final String SWING_OFFHAND = "swing_offhand";
    public static final String SWING_HAND_DEFAULT = "swing_hand";
    public static final String USE_MAINHAND = "use_mainhand";
    public static final String USE_OFFHAND = "use_offhand";

    /** 与 TLM 相同：肢体摆动速度超过它才算行走。 */
    public static final float MIN_WALK_SPEED = 0.05f;
    public static final int PARALLEL_COUNT = 8;

    private NpcAnimations() {
    }

    // ---------------------------------------------------------------- 状态判定（纯函数，便于测试）

    /** 主控制器判定所需的实体状态快照。 */
    public record Snapshot(boolean dead, boolean sleeping, boolean swimming, boolean climbing, double verticalSpeed,
                           boolean inBoat, boolean passenger, boolean sitting, boolean inWater, boolean hurt,
                           boolean onGround, boolean sprinting, float walkSpeed) {
        /**
         * 客户端的「在地面上」只在收到移动包时更新，一直站着不动的 NPC 刚进入视野时会一直是假，
         * 看起来像悬空跳跃；所以这一刻没有竖直移动也算在地面上。
         */
        public static Snapshot of(LivingEntity e, boolean sitting) {
            double vy = 20 * (e.getY() - e.yo);
            return new Snapshot(e.isDeadOrDying(), e.getPose() == Pose.SLEEPING, e.isVisuallySwimming(), e.onClimbable(),
                    vy, e.getVehicle() instanceof AbstractBoat, e.isPassenger(), sitting,
                    e.isInWater(), e.hurtTime > 0, e.onGround() || vy == 0, e.isSprinting(), e.walkAnimation.speed());
        }
    }

    private record State(String name, Predicate<Snapshot> test) {
    }

    /** TLM 的优先级（HIGHEST → LOWEST），同级按此顺序；第一个成立的状态胜出。 */
    private static final List<State> MAIN = List.of(
            new State(DEATH, Snapshot::dead),
            new State(SLEEP, Snapshot::sleeping),
            new State(SWIM, Snapshot::swimming),
            new State(LADDER_UP, s -> s.climbing() && s.verticalSpeed() > 0),
            new State(LADDER_STILLNESS, s -> s.climbing() && s.verticalSpeed() == 0),
            new State(LADDER_DOWN, s -> s.climbing() && s.verticalSpeed() < 0),
            new State(BOAT, Snapshot::inBoat),
            new State(CHAIR, Snapshot::passenger),
            new State(SIT, Snapshot::sitting),
            new State(SWIM_STAND, Snapshot::inWater),
            new State(ATTACKED, Snapshot::hurt),
            new State(JUMP, s -> !s.onGround() && !s.inWater()),
            new State(RUN, s -> s.onGround() && s.sprinting()),
            new State(WALK, s -> s.onGround() && s.walkSpeed() > MIN_WALK_SPEED),
            new State(IDLE, s -> true));

    /**
     * 主控制器：按优先级返回第一个条件成立、且动画文件里存在的状态名。
     * 成立但缺失的状态顺延到下一个（例如没有 {@code run} 时奔跑用 {@code walk}）；全都缺失返回 null。
     */
    public static String mainState(Snapshot s, Predicate<String> has) {
        for (State st : MAIN) {
            if (st.test().test(s) && has.test(st.name())) return st.name();
        }
        return null;
    }

    /** 手上物品的判定信息。 */
    public record HandItem(boolean empty, String id, String classify, String useAnimation, boolean chargedCrossbow,
                           Predicate<String> inTag) {
        public static HandItem of(ItemStack stack) {
            if (stack.isEmpty()) return new HandItem(true, "", null, "none", false, t -> false);
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            ChargedProjectiles charged = stack.get(DataComponents.CHARGED_PROJECTILES);
            return new HandItem(false, id, NpcAnimations.classify(stack), stack.getUseAnimation().getSerializedName(),
                    charged != null && !charged.isEmpty(), tag -> NpcAnimations.inTag(stack, tag));
        }
    }

    /** 与 TLM InnerClassify 对应的物品大类。 */
    static String classify(ItemStack stack) {
        if (stack.is(ItemTags.SWORDS)) return "sword";
        if (stack.is(ItemTags.AXES)) return "axe";
        if (stack.is(ItemTags.PICKAXES)) return "pickaxe";
        if (stack.is(ItemTags.SHOVELS)) return "shovel";
        if (stack.is(ItemTags.HOES)) return "hoe";
        if (stack.has(DataComponents.BLOCKS_ATTACKS)) return "shield";
        if (stack.has(DataComponents.PIERCING_WEAPON)) return "spear";
        if (stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION)) return "throwable_potion";
        return null;
    }

    private static boolean inTag(ItemStack stack, String tag) {
        Identifier id = Identifier.tryParse(tag);
        return id != null && stack.is(TagKey.create(Registries.ITEM, id));
    }

    /**
     * 手部条件动画名，按 TLM 的匹配顺序：{@code 前缀$物品ID} → {@code 前缀#物品标签} → {@code 前缀:大类}
     * → {@code 前缀:使用动画类型}；空手时为 {@code 前缀:empty}（只在 allowEmpty 时）。
     * 只返回动画文件里存在的名字；都没有返回 null。
     */
    public static String handAnimation(String prefix, HandItem item, Set<String> names, boolean allowEmpty) {
        if (item.empty()) {
            String n = prefix + ":empty";
            return allowEmpty && names.contains(n) ? n : null;
        }
        String byId = prefix + "$" + item.id();
        if (names.contains(byId)) return byId;
        String tagPrefix = prefix + "#";
        for (String n : names) {
            if (n.startsWith(tagPrefix) && item.inTag().test(n.substring(tagPrefix.length()))) return n;
        }
        if (item.classify() != null && names.contains(prefix + ":" + item.classify())) return prefix + ":" + item.classify();
        if (!"none".equals(item.useAnimation()) && names.contains(prefix + ":" + item.useAnimation())) {
            return prefix + ":" + item.useAnimation();
        }
        return null;
    }

    /** 持物：TLM 先判定已上弦的弩，再走条件名。 */
    public static String holdAnimation(boolean mainHand, HandItem item, Set<String> names) {
        String prefix = mainHand ? HOLD_MAINHAND : HOLD_OFFHAND;
        String charged = prefix + ":charged_crossbow";
        if (item.chargedCrossbow() && names.contains(charged)) return charged;
        return handAnimation(prefix, item, names, true);
    }

    /** 挥手：条件名优先，否则 {@code swing_hand} / {@code swing_offhand}。 */
    public static String swingAnimation(boolean mainHand, HandItem item, Set<String> names) {
        String n = handAnimation(mainHand ? SWING : SWING_OFFHAND, item, names, false);
        if (n != null) return n;
        String def = mainHand ? SWING_HAND_DEFAULT : SWING_OFFHAND;
        return names.contains(def) ? def : null;
    }

    /** 使用物品：条件名（与持物相同的写法）优先，否则 {@code use_mainhand} / {@code use_offhand}。 */
    public static String useAnimation(boolean mainHand, HandItem item, Set<String> names) {
        String prefix = mainHand ? USE_MAINHAND : USE_OFFHAND;
        String n = handAnimation(prefix, item, names, false);
        if (n != null) return n;
        return names.contains(prefix) ? prefix : null;
    }

    // ---------------------------------------------------------------- 动画文件里有哪些动画

    /**
     * 动画文件 {@code geckolib/animations/entity/<名>.animation.json} 中的动画名。
     * GeckoLib 的缓存键去掉了前后缀，即 {@code <命名空间>:entity/<名>}。只在客户端有内容。
     */
    public static Set<String> available(Identifier asset) {
        var cache = GeckoLibResources.getBakedAnimations();
        if (cache == null) return Set.of();
        BakedAnimations anims = cache.cache().get(asset.withPrefix("entity/"));
        return anims == null ? Set.of() : anims.animations().keySet();
    }

    // ---------------------------------------------------------------- 控制器

    private static final Map<String, RawAnimation> LOOP = new ConcurrentHashMap<>();
    private static final Map<String, RawAnimation> ONCE = new ConcurrentHashMap<>();
    private static final Map<String, RawAnimation> HOLD = new ConcurrentHashMap<>();

    static RawAnimation raw(String name) {
        return switch (name) {
            case DEATH -> HOLD.computeIfAbsent(name, n -> RawAnimation.begin().then(n, LoopType.HOLD_ON_LAST_FRAME));
            case ATTACKED -> ONCE.computeIfAbsent(name, n -> RawAnimation.begin().then(n, LoopType.PLAY_ONCE));
            default -> LOOP.computeIfAbsent(name, n -> RawAnimation.begin().then(n, LoopType.LOOP));
        };
    }

    private static final Map<String, RawAnimation> AS_FILE = new ConcurrentHashMap<>();
    /** 死亡：先播一遍受击，再播死亡并停在最后一帧。 */
    private static final RawAnimation DEATH_AFTER_HIT = RawAnimation.begin().thenPlay(ATTACKED).thenPlayAndHold(DEATH);

    /** 按动画文件里写的 loop 播放（剧情动作：一次性的播完就停，循环的一直循环）。 */
    private static RawAnimation asFile(String name) {
        return AS_FILE.computeIfAbsent(name, n -> RawAnimation.begin().then(n, LoopType.DEFAULT));
    }

    private static RawAnimation once(String name) {
        return ONCE.computeIfAbsent(name, n -> RawAnimation.begin().then(n, LoopType.PLAY_ONCE));
    }

    /** 在实体的 {@code registerControllers} 里调用。 */
    public static <T extends LivingEntity & NpcAnimated> void registerControllers(T npc, AnimatableManager.ControllerRegistrar controllers) {
        for (int i = 0; i < PARALLEL_COUNT; i++) {
            controllers.add(parallel(npc, String.format("pre_parallel_%d_controller", i), "pre_parallel" + i));
        }
        controllers.add(new AnimationController<T>("main", 2, test -> {
            Set<String> names = available(npc.npcAnimationAsset());
            String name = mainState(Snapshot.of(npc, npc.isNpcSitting()), names::contains);
            if (name == null) return PlayState.STOP;
            // 最后一击：先把受击动画播完，再接死亡动画
            return test.setAndContinue(DEATH.equals(name) && names.contains(ATTACKED) ? DEATH_AFTER_HIT : raw(name));
        }));
        controllers.add(hold(npc, "hold_offhand", false));
        controllers.add(hold(npc, "hold_mainhand", true));
        controllers.add(new AnimationController<T>("swing", 2, test -> {
            if (!npc.swinging || npc.isSleeping() || npc.isDeadOrDying()) return PlayState.STOP;
            boolean main = npc.swingingArm != InteractionHand.OFF_HAND;
            String name = swingAnimation(main, HandItem.of(main ? npc.getMainHandItem() : npc.getOffhandItem()),
                    available(npc.npcAnimationAsset()));
            if (name == null) return PlayState.STOP;
            if (npc.swingTime == 0) test.controller().reset();   // 每次挥手从头播放
            return test.setAndContinue(once(name));
        }));
        controllers.add(new AnimationController<T>("use", 2, test -> {
            if (!npc.isUsingItem() || npc.isSleeping() || npc.isDeadOrDying()) return PlayState.STOP;
            boolean main = npc.getUsedItemHand() == InteractionHand.MAIN_HAND;
            String name = useAnimation(main, HandItem.of(main ? npc.getMainHandItem() : npc.getOffhandItem()),
                    available(npc.npcAnimationAsset()));
            if (name == null) return PlayState.STOP;
            if (npc.getTicksUsingItem() <= 1) test.controller().reset();
            return test.setAndContinue(raw(name));
        }));
        controllers.add(new AnimationController<T>("misc", 2, test -> {
            String name = npc.isDeadOrDying() ? null : npc.npcMiscAnimation();
            if (name == null || !available(npc.npcAnimationAsset()).contains(name)) return PlayState.STOP;
            return test.setAndContinue(asFile(name));
        }));
        for (int i = 0; i < PARALLEL_COUNT; i++) {
            controllers.add(parallel(npc, String.format("parallel_%d_controller", i), "parallel" + i));
        }
    }

    private static <T extends LivingEntity & NpcAnimated> AnimationController<T> hold(T npc, String controller, boolean main) {
        return new AnimationController<T>(controller, 0, test -> {
            if (npc.isSleeping() || npc.isDeadOrDying()) return PlayState.STOP;
            String name = holdAnimation(main, HandItem.of(main ? npc.getMainHandItem() : npc.getOffhandItem()),
                    available(npc.npcAnimationAsset()));
            return name == null ? PlayState.STOP : test.setAndContinue(raw(name));
        });
    }

    /** 常驻叠加层：动画文件里有这个名字就一直循环播放。 */
    private static <T extends LivingEntity & NpcAnimated> AnimationController<T> parallel(T npc, String controller, String name) {
        return new AnimationController<T>(controller, 0, test -> available(npc.npcAnimationAsset()).contains(name)
                ? test.setAndContinue(raw(name)) : PlayState.STOP);
    }
}
