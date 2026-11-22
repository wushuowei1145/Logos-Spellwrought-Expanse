package dev.logos.dimension.jiansui;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.camera.CameraPoint;
import dev.logos.dimension.camera.CameraShot;
import dev.logos.dimension.camera.CameraShots;
import dev.logos.dimension.dissolve.DissolveShape;
import dev.logos.dimension.dissolve.Dissolving;
import dev.logos.dimension.execution.ExecutionShot;
import dev.logos.dimension.execution.ExecutionShots;
import dev.logos.dimension.execution.ExecutionTimeline;
import dev.logos.dimension.longsword.LongswordCombat;
import dev.logos.dimension.screenfx.ScreenCover;
import dev.logos.dimension.screenfx.ScreenEffects;
import dev.logos.dimension.screenfx.ScreenFlash;
import dev.logos.dimension.taixuan.TaixuanSite;
import dev.logos.runtime.PlayerMagic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * 剑祟这一战（docs/JIAN_SUI_BOSS.md 第 1–14 节）：雾底场地上的残骸、登场、两条血、离体、裂地、第三阶段「坠渊」、终结和遗剑。
 *
 * <p>每处太玄灵境一份记录（存档 {@code jian_sui_arenas}，以结构起点区块为键）：状态、从哪一刻开始、剑祟是谁、地标的位置与旋转、
 * 进第三阶段时每个人原来在哪、每个人到过的最高落脚台、玩家放的方块、剑格的临时方块。长的过场都按「状态 + 开始的刻」逐刻算，
 * 短的定时由 {@link #later} 排（不存档）。读档时过场不重放，按第 14 节的表收拾（{@link #settle}）。
 * 命令召出来的剑祟（没有场地）只有两条血，打空第二条就散架。
 *
 * <p>场地范围（第 2.1 节）：离竖轴 60 格以内、世界 y −17 到 16 是上面的雾底场地；场地下面的空洞（半径 36、y −60 到 −19）
 * 也算。谁看得到血条、30 秒没人时重置、玩家放的方块，都按这个范围。
 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class JianSuiFight {
    public enum State { DORMANT, ENTRANCE, PHASE1, SEPARATION, PHASE2, RIFT, FALL, RIFT_WAIT, FINISHER, DEFEATED }

    static final double FIGHT_RADIUS = 60, CAVITY_RADIUS = 36;
    static final double ARENA_LOW = -17, ARENA_HIGH = 16, CAVITY_LOW = -60, CAVITY_HIGH = -19;
    /** 第三阶段：脚低于这里算掉进虚空（世界 y）。 */
    static final double VOID_FEET = -54;
    /** 走近多少格残骸醒过来；多远开始眼睛亮一下。 */
    static final double WAKE = 8, FLICKER = 16;
    /** 场地里多久没有活着的玩家就重置（刻）。 */
    static final int REST_TICKS = 600;
    // ---- 登场（第 3.2 节）
    static final int REASSEMBLE_AT = 30;
    /** 标题出现、登场结束开打（刻，从醒来算）；客户端的标题按这两个算淡出。 */
    public static final int TITLE_AT = 130, ENTRANCE_END = 160;
    // ---- 离体（第 7 节）：过场还没有动作，先停一下、换血条
    static final int SEPARATION_END = 80;
    // ---- 裂地（第 9 节）
    static final int PLUNGE_IMPACT = 30, CRACKS_FROM = 40, WHITE_AT = 90, WHITE_IN = 30, TELEPORT_AT = 120, RIFT_END = 220;
    /** 裂缝爬到多远（格）；起点碎块上那道回去的裂缝多大。 */
    static final float CRACKS_RADIUS = 52, EXIT_CRACK_RADIUS = 1.8f;
    /**
     * 裂缝还亮着时，每隔多少刻给附近的人重发一次（客户端隔了两倍这么久没收到就把它收掉）：
     * 重连、换维度回来的人也看得到，打完、重置后没收到「收起」的人也不会一直亮着。
     */
    public static final int CRACK_REFRESH = 200;
    // ---- 巨剑贯落（第 10.3 节）
    public static final int SWORD_AIM = 28, SWORD_FLIGHT = 5;
    /** 第三阶段开始后多久第一柄巨剑起手（刻）。 */
    public static final int FIRST_SWORD = 20;
    static final int SWORD_EVERY = 34, SWORD_SAME_TARGET = 80, SWORD_AFTER_RETURN = 40, SWORD_SECOND = 10, SWORD_STUCK = 160;
    /** 巨剑被弹反后飞回去要几刻；之后多久不射剑。 */
    public static final int REFLECT_FLIGHT = 6, SWORD_PARRY_REST = 100;
    static final float SWORD_DAMAGE = 10, VOID_DAMAGE = 4;
    /** 巨剑剑尖到剑格的长度（格）：手中古剑的网格 50 单位，渲染 1/8。 */
    public static final double SWORD_GUARD = 6.25;

    /**
     * 扎住的巨剑露在碎块面上的那一截（从落点沿剑往回到剑格，格）：剑格离碎块面一格高。剑尖往碎块里扎进
     * {@code SWORD_GUARD - guardBack} 格。服务端的剑格台阶和客户端画的剑都按它算。{@code unit} 是剑飞的方向（单位向量，往下）。
     */
    public static double guardBack(Vec3 unit) {
        return unit.y >= -1e-3 ? SWORD_GUARD : Math.min(SWORD_GUARD, 1 / -unit.y);
    }
    /** 离顶块多近的不留剑格。 */
    static final double GUARD_KEEP_OFF_TOP = 7;
    /** 剑祟在顶块上走的那一圈的半径（顶块半径 4.5）。 */
    static final double TOP_WALK = 3.0;
    // ---- 踩裂缝下去、蹲在起点的裂缝上回去（第 10.4、14 节）
    static final int PULL_TELEPORT = 12, EXIT_CROUCH = 40;
    // ---- 终结（第 11 节）：打中 → 黑白「飞身刺」里换到上面 → 彩色里撬三下 → 贯穿古剑崩出 → 散架、遗剑落下。时刻都从打中那一刻算
    /** 黑白的「飞身刺」第几刻发（不在打中的那一下里发：那时还在玩家这一刀的结算里），同时暂停打中的人的战斗骨架。 */
    public static final int FIN_SHOT = 1;
    /** 黑白定格里换场景：客户端从 {@link ExecutionTimeline#THRUST_FREEZE} 起定格，追踪晚发、远处的实体插值都盖在定格里（{@link ExecutionTimeline#THRUST_SWAP}）。 */
    public static final int FIN_SWAP = FIN_SHOT + ExecutionTimeline.THRUST_SWAP;
    /** 回到彩色：过场镜头硬切过来（黑白就此结束），剑祟开始被撬，客户端开始摆撬剑的姿势。 */
    public static final int FIN_COLOUR = FIN_SHOT + ExecutionTimeline.THRUST_COLOUR;
    /** 撬剑：从第几刻起收攻击键，两下至少隔几刻，窗口关上后还收几刻（路上的延迟）。 */
    public static final int PRY_OPEN = FIN_COLOUR + 4, PRY_GAP = 8, PRY_GRACE = 6;
    /** 不按也会自己撬：第 i 下最晚在这一刻（按了只会更早）。 */
    public static final int[] PRY_AUTO = {FIN_COLOUR + 16, FIN_COLOUR + 31, FIN_COLOUR + 46};
    public static final int PRY_CLOSE = PRY_AUTO[2];
    /** 第三下之后多久贯穿古剑崩出；崩出后多久落回插剑的地方（客户端动画：上飞 30、不见 20、下落 12），多久收尾（剑祟移除、镜头往回走）。 */
    public static final int BURST_AFTER = 8, LAND_AFTER = 62, GONE_AFTER = 80;
    /** 镜头回到玩家用几刻；崩出后几刻镜头退远一点（第 11 节「4 秒」那一行）。 */
    public static final int FIN_RETURN = 16, FIN_WIDE_AFTER = 12;
    /** 最晚在哪一刻收尾（三下都是自己撬的）：过场里的人护到这之后。 */
    static final int FIN_LAST = PRY_AUTO[2] + BURST_AFTER + GONE_AFTER;
    /** 换场景后打中的人站在剑祟面前多远（格）。 */
    static final double FIN_FRONT = 1.6;
    /** 拆玩家放的方块时淡青白的颜色（同光缝）。 */
    static final int SHATTER_COLOR = 0xC8F2FA;
    /** 第三阶段里玩家放下的方块过多久被斩碎；第一、二阶段够不到目标多久斩碎目标脚下的。 */
    static final int SHATTER_AFTER = 30, UNREACHABLE_TICKS = 60;

    // ---------------------------------------------------------------- 记录

    static final class Arena {
        final long key;
        State state;
        long since;
        @Nullable UUID boss, stage;
        BlockPos anchor;
        int rotation;
        /** 进空洞前每个人在哪（x, y, z, 偏航）。 */
        final Map<UUID, double[]> returns = new HashMap<>();
        /** 每个人到过的最高落脚台（0 起点，1、2 两个落脚台）。 */
        final Map<UUID, Integer> reached = new HashMap<>();
        /** 战斗范围里玩家放的方块；剑格的临时碰撞方块（位置 → 拆掉的刻）。都存档，读档时临时方块一律拆掉。 */
        final Set<Long> placed = new HashSet<>();
        /** 剑格的临时方块：拆掉的刻；0 是读档带回来的（收拾时拆），1 是那一块没加载、等加载出来再拆。 */
        final Map<Long, Long> guards = new HashMap<>();
        /** 遗剑周围换成裂纹瓷砖的地砖原来是什么（重置时还原）。 */
        final Map<Long, BlockState> scar = new HashMap<>();
        /** 运行时：读档后还没收拾、多久没人了、方块什么时候放的、正在掉回去的人、上一次被瞄准的刻、刚回到落脚台的刻、正在飞的巨剑。 */
        boolean loaded;
        int empty, unreachable, stuck;
        long cracksAt = Long.MIN_VALUE;
        final Map<Long, Long> placedAt = new HashMap<>();
        final Map<UUID, Long> falling = new HashMap<>(), aimed = new HashMap<>(), returned = new HashMap<>(), pulling = new HashMap<>(), leaving = new HashMap<>();
        final Map<UUID, Integer> crouch = new HashMap<>();
        /** 上一次给这个人发石台裂缝、起点裂缝的刻（隔 {@link #CRACK_REFRESH} 重发）。 */
        final Map<UUID, Long> crackSent = new HashMap<>(), exitSent = new HashMap<>();
        final List<Sword> swords = new ArrayList<>();
        @Nullable UUID finisher;
        /**
         * 终结（运行时，不存档：读档时终结直接收尾）：看黑白特写的人（之后的过场镜头也发给他们）和每人最后一次发的镜头，
         * 撬了几下、按了还没轮到的几下、上一下在第几刻，崩出在第几刻（还没崩是 -1）。时刻都从打中那一刻算。
         */
        final Set<UUID> finisherViewers = new HashSet<>();
        final Map<UUID, CameraShot> finisherShots = new HashMap<>();
        int pryBeats, pryQueued;
        long pryLast = Long.MIN_VALUE / 2, burstAt = -1;
        @Nullable Vec3 bossSpot, stuckAt;
        long nextSword;
        /** 第几轮巨剑；弹反时加一，排好的第二柄看到不是同一轮就不射。 */
        int volley;
        /** 读档时停在裂地前 6 秒、接成了第三阶段：上线时在场地里的人也送下去（第三阶段结束时清掉）。 */
        boolean pendingDrop;
        /** 转阶段「全部斩碎」时那一块没加载的方块：加载出来以后补碎。 */
        final Set<Long> pendingShatter = new HashSet<>();
        /** 有人回来时剑祟还没加载：它没人时静立了多久（刻），加载出来再按这个回血。 */
        int restOwed;

        /** 一个人离开这处场地的世界（下线、换维度）：清掉他正在进行的掉落、回程、吸下去、蹲着、发过的裂缝。 */
        void forget(UUID id) {
            falling.remove(id);
            leaving.remove(id);
            pulling.remove(id);
            crouch.remove(id);
            crackSent.remove(id);
            exitSent.remove(id);
        }

        Arena(long key, State state, long since, BlockPos anchor, int rotation) {
            this.key = key;
            this.state = state;
            this.since = since;
            this.anchor = anchor;
            this.rotation = rotation;
        }

        TaixuanSite site() {
            return new TaixuanSite(anchor, net.minecraft.world.level.block.Rotation.values()[rotation], ChunkPos.unpack(key));
        }
    }

    /** 一柄巨剑：瞄准的人、出手的地方、落点、开始的刻、扎不扎得住碎块。 */
    record Sword(UUID target, Vec3 from, Vec3 to, long start, boolean stuck) {}

    record Saved(String state, long since, String boss, long anchor, int rotation, Map<String, List<Double>> returns, Map<String, Integer> reached,
                 List<Long> placed, List<Long> guards, Map<String, BlockState> scar) {
        static final Codec<Saved> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("state").forGetter(Saved::state),
                Codec.LONG.fieldOf("since").forGetter(Saved::since),
                Codec.STRING.optionalFieldOf("boss", "").forGetter(Saved::boss),
                Codec.LONG.fieldOf("anchor").forGetter(Saved::anchor),
                Codec.INT.fieldOf("rotation").forGetter(Saved::rotation),
                Codec.unboundedMap(Codec.STRING, Codec.DOUBLE.listOf()).optionalFieldOf("returns", Map.of()).forGetter(Saved::returns),
                Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("reached", Map.of()).forGetter(Saved::reached),
                Codec.LONG.listOf().optionalFieldOf("placed", List.of()).forGetter(Saved::placed),
                Codec.LONG.listOf().optionalFieldOf("guards", List.of()).forGetter(Saved::guards),
                Codec.unboundedMap(Codec.STRING, BlockState.CODEC).optionalFieldOf("scar", Map.of()).forGetter(Saved::scar)
        ).apply(i, Saved::new));
    }

    public static final class Arenas extends SavedData {
        static final Codec<Arenas> CODEC = Codec.unboundedMap(Codec.STRING, Saved.CODEC).xmap(Arenas::new, Arenas::save);
        static final SavedDataType<Arenas> TYPE = new SavedDataType<>(LogosDimension.id("jian_sui_arenas"), Arenas::new, CODEC);
        final Map<Long, Arena> arenas = new HashMap<>();

        public Arenas() {}

        private Arenas(Map<String, Saved> saved) {
            for (var e : saved.entrySet()) {
                Saved s = e.getValue();
                Arena a = new Arena(Long.parseLong(e.getKey()), State.valueOf(s.state), s.since, BlockPos.of(s.anchor), s.rotation);
                a.boss = s.boss.isEmpty() ? null : UUID.fromString(s.boss);
                s.returns.forEach((k, v) -> a.returns.put(UUID.fromString(k), v.stream().mapToDouble(Double::doubleValue).toArray()));
                s.reached.forEach((k, v) -> a.reached.put(UUID.fromString(k), v));
                a.placed.addAll(s.placed);
                for (long g : s.guards) a.guards.put(g, 0L);
                s.scar.forEach((k, v) -> a.scar.put(Long.parseLong(k), v));
                a.loaded = true;
                arenas.put(a.key, a);
            }
        }

        private Map<String, Saved> save() {
            Map<String, Saved> out = new HashMap<>();
            for (Arena a : arenas.values()) {
                Map<String, List<Double>> returns = new HashMap<>();
                a.returns.forEach((k, v) -> returns.put(k.toString(), java.util.Arrays.stream(v).boxed().toList()));
                Map<String, Integer> reached = new HashMap<>();
                a.reached.forEach((k, v) -> reached.put(k.toString(), v));
                Map<String, BlockState> scar = new HashMap<>();
                a.scar.forEach((k, v) -> scar.put(Long.toString(k), v));
                out.put(Long.toString(a.key), new Saved(a.state.name(), a.since, a.boss == null ? "" : a.boss.toString(), a.anchor.asLong(), a.rotation,
                        returns, reached, List.copyOf(a.placed), List.copyOf(a.guards.keySet()), scar));
            }
            return out;
        }

        Arena put(Arena a) {
            arenas.put(a.key, a);
            setDirty();
            return a;
        }
    }

    static Arenas arenas(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Arenas.TYPE);
    }

    /** 排好的短定时；{@code arena} 是哪一处场地的（重置时一起取消），不属于场地的是 {@link Long#MIN_VALUE}。 */
    private record Task(ServerLevel level, long at, long arena, Runnable run) {}
    private static final List<Task> TASKS = new ArrayList<>();
    /** 过场里不受伤的玩家，到哪一刻为止。 */
    private static final Map<UUID, Long> PROTECTED = new HashMap<>();

    private JianSuiFight() {}

    static void later(ServerLevel level, long at, Runnable run) {
        TASKS.add(new Task(level, at, Long.MIN_VALUE, run));
    }

    static void later(ServerLevel level, Arena a, long at, Runnable run) {
        TASKS.add(new Task(level, at, a.key, run));
    }

    /** 单人模式换世界、关服：静态的定时和保护不带到下一个世界（里面的刻数是上一个世界的），也不留着上一个世界的引用。 */
    @SubscribeEvent
    static void serverStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        TASKS.clear();
        PROTECTED.clear();
    }

    /** 下线、换维度：清掉他在各处场地里正在进行的事（不然回程裂缝、巨剑瞄准都会被卡住）。 */
    @SubscribeEvent
    static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) forget(p);
    }

    @SubscribeEvent
    static void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) forget(p);
    }

    private static void forget(ServerPlayer p) {
        PROTECTED.remove(p.getUUID());
        var server = p.level().getServer();
        if (server == null) return;
        for (ServerLevel level : server.getAllLevels()) {
            Arenas data = level.getDataStorage().get(Arenas.TYPE);
            if (data != null) for (Arena a : data.arenas.values()) a.forget(p.getUUID());
        }
    }

    // ---------------------------------------------------------------- 几何

    /** 插剑、残骸、「回到石台」的那一点：石台闪长岩那层上、铁链旁边（第 2.1 节）。 */
    public static Vec3 plunge(TaixuanSite site) {
        return site.point(TaixuanSite.AXIS + .5 + 3.2, 11, TaixuanSite.AXIS + .5);
    }

    /** 遗剑方块的位置：插剑那一点所在的格子。 */
    public static BlockPos relicPos(TaixuanSite site) {
        return BlockPos.containing(plunge(site));
    }

    /** 第三阶段剑祟站的地方：顶块闪长岩那层上，玄铁心旁边。 */
    public static Vec3 top(TaixuanSite site) {
        var top = TaixuanSite.cavity().getAsJsonObject("top");
        return site.point(TaixuanSite.AXIS + .5, top.get("feet").getAsInt(), TaixuanSite.AXIS + .5 + 2.6);
    }

    /** 主路第 {@code index} 块（0 起点）脚站的地方。 */
    public static Vec3 stone(TaixuanSite site, int index) {
        var cavity = TaixuanSite.cavity();
        var s = index == 0 ? cavity.getAsJsonObject("start") : cavity.getAsJsonArray("route").get(index - 1).getAsJsonObject();
        var top = s.getAsJsonArray("top");
        return site.point(top.get(0).getAsDouble(), s.get("feet").getAsInt(), top.get(2).getAsDouble());
    }

    /** 落脚台：0 起点，1、2 爬过三分之一、三分之二那两块。 */
    public static Vec3 checkpoint(TaixuanSite site, int which) {
        if (which <= 0) return stone(site, 0);
        var list = TaixuanSite.cavity().getAsJsonArray("checkpoints");
        return stone(site, list.get(Math.min(which, list.size()) - 1).getAsInt());
    }

    /** 起点碎块正中那道回上面去的裂缝（脚站的高度）。 */
    public static Vec3 exitCrack(TaixuanSite site) {
        var c = TaixuanSite.cavity().getAsJsonArray("exit_crack");
        return site.point(c.get(0).getAsDouble(), c.get(1).getAsInt() + 1, c.get(2).getAsDouble());
    }

    static double flat(TaixuanSite site, Vec3 p) {
        Vec3 axis = site.axis(p.y);
        return Math.hypot(p.x - axis.x, p.z - axis.z);
    }

    static boolean inArena(TaixuanSite site, Vec3 p) {
        return flat(site, p) <= FIGHT_RADIUS && p.y >= ARENA_LOW && p.y <= ARENA_HIGH;
    }

    static boolean inCavity(TaixuanSite site, Vec3 p) {
        return flat(site, p) <= CAVITY_RADIUS + 2 && p.y >= CAVITY_LOW && p.y <= CAVITY_HIGH;
    }

    static boolean inFight(TaixuanSite site, Vec3 p) {
        return inArena(site, p) || inCavity(site, p);
    }

    private static boolean fighter(Player p) {
        return p.isAlive() && !p.isSpectator() && !p.isCreative();
    }

    static List<ServerPlayer> players(ServerLevel level, Arena a, boolean cavityOnly) {
        TaixuanSite site = a.site();
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (!p.isAlive() || p.isSpectator()) continue;
            if (cavityOnly ? inCavity(site, p.position()) : inFight(site, p.position())) out.add(p);
        }
        return out;
    }

    static @Nullable JianSui boss(ServerLevel level, Arena a) {
        return a.boss != null && level.getEntity(a.boss) instanceof JianSui b && b.isAlive() ? b : null;
    }

    static @Nullable Arena arenaOf(ServerLevel level, JianSui boss) {
        return boss.arena() == null ? null : arenas(level).arenas.get(boss.arena());
    }

    static @Nullable Arena arena(ServerLevel level, TaixuanSite site) {
        return arenas(level).arenas.get(site.start().pack());
    }

    // ---------------------------------------------------------------- 找人、血条

    /** 盯谁：已经盯着的人还在就继续盯，否则找最近的；创造、旁观模式的不算。 */
    static @Nullable LivingEntity pickTarget(ServerLevel level, JianSui boss) {
        Arena a = arenaOf(level, boss);
        LivingEntity current = boss.getTarget();
        if (a == null) {
            if (current instanceof Player p && fighter(p) && p.distanceToSqr(boss) < JianSui.FREE_RADIUS * JianSui.FREE_RADIUS) return current;
            Player best = null;
            for (ServerPlayer p : level.players()) {
                if (!fighter(p) || p.distanceToSqr(boss) > JianSui.FREE_RADIUS * JianSui.FREE_RADIUS) continue;
                if (best == null || p.distanceToSqr(boss) < best.distanceToSqr(boss)) best = p;
            }
            return best;
        }
        TaixuanSite site = a.site();
        if (current instanceof Player p && fighter(p) && inArena(site, p.position())) return current;
        Player best = null;
        for (ServerPlayer p : level.players()) {
            if (!fighter(p) || !inArena(site, p.position())) continue;
            if (best == null || p.distanceToSqr(boss) < best.distanceToSqr(boss)) best = p;
        }
        return best;
    }

    /** 第一、二阶段剑祟的活动范围（第 2.1 节）：离竖轴 40 格以内、脚在世界 y −15 到 −3。 */
    static final double ACTIVITY_RADIUS = 40, ACTIVITY_LOW = -15, ACTIVITY_HIGH = -3;

    /** 剑祟能不能走过去打 {@code p} 那里的人：在场地的活动范围里（命令召出来的没有场地，都行）。 */
    static boolean reachable(ServerLevel level, JianSui boss, Vec3 p) {
        Arena a = arenaOf(level, boss);
        if (a == null) return true;
        return flat(a.site(), p) <= ACTIVITY_RADIUS && p.y >= ACTIVITY_LOW - .5 && p.y <= ACTIVITY_HIGH + 2;
    }

    /** 没有目标时回去的地方：铁链旁插剑的那一点（命令召出来的没有）。 */
    static @Nullable Vec3 home(ServerLevel level, JianSui boss) {
        Arena a = arenaOf(level, boss);
        return a == null ? null : plunge(a.site());
    }

    /** 剑祟被挡住 2 秒：斩碎它身边 4 格内、以及朝目标那一路上（8 格内）玩家放的方块（第 5.7 节）。 */
    static void blocked(ServerLevel level, JianSui boss, @Nullable LivingEntity target) {
        Arena a = arenaOf(level, boss);
        if (a == null || a.placed.isEmpty()) return;
        Vec3 from = boss.position().add(0, 1, 0);
        Vec3 d = target == null ? Vec3.ZERO : target.position().add(0, 1, 0).subtract(from);
        double length = Math.min(8, d.length());
        Vec3 unit = length < 1e-3 ? Vec3.ZERO : d.normalize();
        List<BlockPos> hit = new ArrayList<>();
        for (long key : a.placed) {
            Vec3 c = Vec3.atCenterOf(BlockPos.of(key));
            double along = Mth.clamp(c.subtract(from).dot(unit), 0, length);
            if (c.distanceTo(from) <= 4 || c.distanceTo(from.add(unit.scale(along))) <= 1.6) hit.add(BlockPos.of(key));
        }
        if (!hit.isEmpty()) shatter(level, a, hit, boss);
    }

    /** 谁看得到血条：场地范围里的人（上面的场地和下面的空洞都算）。 */
    static boolean seesBar(ServerLevel level, JianSui boss, ServerPlayer p) {
        if (p.level() != level) return false;
        Arena a = arenaOf(level, boss);
        if (a == null) return p.distanceToSqr(boss) < 48 * 48;
        return inFight(a.site(), p.position());
    }

    /** 场地的记录没了、已经打败，或者记录里的剑祟不是它：多出来的一只（读档、重置之后），删掉。 */
    static boolean orphan(ServerLevel level, JianSui boss) {
        if (boss.arena() == null) return false;
        Arena a = arenas(level).arenas.get(boss.arena());
        return a == null || a.state == State.DEFEATED || a.boss != null && !a.boss.equals(boss.getUUID());
    }

    // ---------------------------------------------------------------- 残骸：有人走近太玄的雾底时放下

    @SubscribeEvent
    static void scout(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer p && p.tickCount % 40 == 0) scoutFor(p);
    }

    /**
     * 第三阶段的空洞里魔力不回复（第 10.5 节）：Logos 在玩家的每刻里回魔力（场地的每刻处理在它之前），
     * 这里排在所有处理之后再清零一次，这一刻结束、处理施法输入时手里还是 0；连「看到的魔力」一起清，不记成修行。
     */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    static void manaLock(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !(p.level() instanceof ServerLevel level) || !level.dimension().equals(LogosDimension.CLOUDSEA)) return;
        Arenas data = level.getDataStorage().get(Arenas.TYPE);
        if (data == null) return;
        for (Arena a : data.arenas.values()) {
            if (manaLocked(level, a) && inCavity(a.site(), p.position())) {
                PlayerMagic magic = PlayerMagic.of(p);
                magic.mana = 0;
                magic.manaSeen = 0;
                return;
            }
        }
    }

    /** 空洞里魔力锁着的时候：第三阶段，以及裂地里人已经被送下来以后（第三阶段开始前那 5 秒）。 */
    static boolean manaLocked(ServerLevel level, Arena a) {
        return a.state == State.FALL || a.state == State.RIFT && level.getGameTime() - a.since >= TELEPORT_AT;
    }

    /** 这个人在太玄的雾底附近（云海、y 40 以下、离竖轴 90 格以内）：这处地标还没有记录就开一份，放下残骸。 */
    public static void scoutFor(ServerPlayer p) {
        if (!(p.level() instanceof ServerLevel level) || !level.dimension().equals(LogosDimension.CLOUDSEA) || p.getY() > 40) return;
        TaixuanSite site = TaixuanSite.at(level, p.blockPosition());
        if (site == null || flat(site, p.position()) > 90) return;
        open(level, site);
    }

    /** 这处地标还没有记录时开一份（未开打），放下残骸。返回记录现在的状态。 */
    public static State open(ServerLevel level, TaixuanSite site) {
        Arenas data = arenas(level);
        Arena a = data.arenas.get(site.start().pack());
        if (a == null) {
            a = data.put(new Arena(site.start().pack(), State.DORMANT, level.getGameTime(), site.anchor(), site.rotation().ordinal()));
            spawnRemains(level, a);
        }
        return a.state;
    }

    static void spawnRemains(ServerLevel level, Arena a) {
        TaixuanSite site = a.site();
        Vec3 at = plunge(site);
        if (!level.isLoaded(BlockPos.containing(at))) return;
        for (JianSui old : level.getEntitiesOfClass(JianSui.class, new AABB(at, at).inflate(12))) old.discard();
        JianSui boss = JianSuiEntities.JIAN_SUI.get().create(level, EntitySpawnReason.TRIGGERED);
        if (boss == null) return;
        Vec3 axis = site.axis(at.y);
        float yaw = (float) Math.toDegrees(Math.atan2(-(at.x - axis.x), at.z - axis.z));
        boss.snapTo(at.x, at.y, at.z, yaw, 0);
        boss.setYBodyRot(yaw);
        boss.setYHeadRot(yaw);
        boss.setArena(a.key);
        boss.setPhase(0);
        boss.setScripted(true);
        level.addFreshEntity(boss);
        boss.play("remains");
        a.boss = boss.getUUID();
        arenas(level).setDirty();
    }

    /** 残骸被人碰了、打了，或者有人走得太近：醒过来（第 3.2 节）。 */
    static void wake(ServerLevel level, JianSui boss) {
        Arena a = arenaOf(level, boss);
        if (a == null || a.state != State.DORMANT) return;
        enter(level, a, State.ENTRANCE);
        TaixuanSite site = a.site();
        scaleHealth(level, a, boss);
        // 登场的运镜：高处远景看着石台，拼回去的时候低机位，最后回到玩家（遮幅、电影蒙版）
        Vec3 at = plunge(site);
        Vec3 axis = site.axis(at.y);
        for (ServerPlayer p : players(level, a, false)) {
            protect(level, p, ENTRANCE_END + 10);
            Vec3 side = p.position().subtract(axis).multiply(1, 0, 1);
            side = side.lengthSqr() < 1 ? new Vec3(0, 0, 1) : side.normalize();
            Vec3 high = CameraShots.reach(level, at.add(0, 2, 0), at.add(side.scale(16)).add(0, 12, 0));
            CameraShots.play(p, CameraShot.of(CameraPoint.world(high), CameraPoint.world(at.add(0, 1.5, 0))).timing(14, REASSEMBLE_AT + 70, 18).letterbox().cinema());
        }
        level.playSound(null, at.x, at.y, at.z, SoundEvents.CHAIN_PLACE, SoundSource.HOSTILE, 2f, .5f);
    }

    /** 按开场时在场的人数加血：每多一人 +50%，最多 ×3（同白梅）；第一条血补满。 */
    private static void scaleHealth(ServerLevel level, Arena a, JianSui boss) {
        int n = (int) Math.max(1, players(level, a, false).stream().filter(JianSuiFight::fighter).count());
        double factor = Math.min(3, 1 + .5 * (n - 1));
        var health = boss.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(JianSui.HEALTH_ONE);
            health.addOrReplacePermanentModifier(new AttributeModifier(LogosDimension.id("jian_sui_players"), factor - 1, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
        boss.setHealth(boss.getMaxHealth());
    }

    // ---------------------------------------------------------------- 转阶段

    /** 一条血清空了（血停在 1）：转阶段（命令召出来的：第一条换第二条，第二条打空就散架）。 */
    static void barEmptied(ServerLevel level, JianSui boss) {
        Arena a = arenaOf(level, boss);
        if (a == null) {
            freeBarEmptied(level, boss);
            return;
        }
        if (a.state == State.PHASE1) enter(level, a, State.SEPARATION);
        else if (a.state == State.PHASE2) enter(level, a, State.RIFT);
    }

    private static void freeBarEmptied(ServerLevel level, JianSui boss) {
        if (boss.phase() == 1) {
            boss.setPhase(2);
            var health = boss.getAttribute(Attributes.MAX_HEALTH);
            if (health != null) health.setBaseValue(JianSui.HEALTH_TWO);
            boss.showBar(true);
            boss.play("idle");
            level.playSound(null, boss.getX(), boss.getY() + 2, boss.getZ(), SoundEvents.TRIDENT_THUNDER.value(), SoundSource.HOSTILE, 1.2f, 1.4f);
            return;
        }
        if (boss.phase() == 2) {
            boss.setPhase(3);
            boss.setScripted(true);
            boss.play("collapse");
            boss.showBar(false);
            level.playSound(null, boss.getX(), boss.getY() + 2, boss.getZ(), SoundEvents.CHAIN_BREAK, SoundSource.HOSTILE, 1.6f, .7f);
            // 3 秒后由剑祟自己删掉（JianSui.tick 数着，读档后也接得上）
        }
    }

    static void enter(ServerLevel level, Arena a, State state) {
        State was = a.state;
        a.state = state;
        a.since = level.getGameTime();
        a.empty = 0;
        a.unreachable = 0;
        arenas(level).setDirty();
        JianSui boss = boss(level, a);
        // 每次转阶段，玩家放的方块全部斩碎（第 5.7 节，同白梅）；那一块没加载的记下来，加载出来以后补碎
        if (was != state && state != State.PHASE1 && state != State.ENTRANCE) {
            List<BlockPos> all = a.placed.stream().map(BlockPos::of).toList();
            shatter(level, a, new ArrayList<>(all), boss);
            a.pendingShatter.clear();
            for (BlockPos q : all) if (a.placed.contains(q.asLong())) a.pendingShatter.add(q.asLong());
        }
        switch (state) {
            case ENTRANCE -> {
                if (boss != null) {
                    boss.setScripted(true);
                    boss.setPhase(1);
                }
            }
            case PHASE1 -> {
                if (boss != null) fightingBoss(a, boss, 1);
            }
            case PHASE2 -> {
                if (boss != null) fightingBoss(a, boss, 2);
            }
            case SEPARATION -> {
                if (boss != null) {
                    boss.setScripted(true);
                    boss.play("idle");
                    for (ServerPlayer p : players(level, a, false)) protect(level, p, SEPARATION_END + 10);
                    level.playSound(null, boss.getX(), boss.getY() + 3, boss.getZ(), SoundEvents.TRIDENT_THUNDER.value(), SoundSource.HOSTILE, 1.4f, 1.6f);
                }
            }
            case RIFT -> {
                if (boss != null) {
                    boss.setScripted(true);
                    boss.setHidden(0);
                    Vec3 at = plunge(a.site());
                    boss.teleportTo(at.x, at.y, at.z);
                    boss.play("plunge");
                    boss.showBar(false);
                }
                for (ServerPlayer p : players(level, a, false)) protect(level, p, RIFT_END + 20);
            }
            default -> { }
        }
    }

    /**
     * 让剑祟进入第一、二阶段打着的样子：不再是过场、换上这一条血（第二条换 550 基础血、补满）、血条亮出来。
     * 已经是这个阶段的不重复补血（所以读档后、剑祟晚一点才加载出来时也能补上）。
     */
    private static void fightingBoss(Arena a, JianSui boss, int phase) {
        if (boss.phase() != phase) {
            boss.setPhase(phase);
            var health = boss.getAttribute(Attributes.MAX_HEALTH);
            if (health != null && phase == 2) health.setBaseValue(JianSui.HEALTH_TWO);
            boss.setHealth(boss.getMaxHealth());
        }
        if (a.empty < REST_TICKS) {
            boss.setScripted(false);
            if (boss.move().isEmpty() || boss.move().equals("plunge_hold") || boss.move().equals("reassemble") || boss.move().equals("remains")) boss.play("idle");
            boss.showBar(true);
        }
    }

    // ---------------------------------------------------------------- 每刻

    @SubscribeEvent
    static void tick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        if (!TASKS.isEmpty()) {
            List<Task> due = new ArrayList<>();
            TASKS.removeIf(t -> t.level == level && t.at <= now && due.add(t));
            for (Task t : due) t.run.run();
        }
        PROTECTED.values().removeIf(until -> until < now);
        Arenas data = level.getDataStorage().get(Arenas.TYPE);
        if (data == null || data.arenas.isEmpty()) return;
        for (Arena a : List.copyOf(data.arenas.values())) {
            if (a.loaded) settle(level, a);
            guards(level, a, now);
            switch (a.state) {
                case DORMANT -> dormant(level, a, now);
                case ENTRANCE -> entrance(level, a, now - a.since);
                case PHASE1, PHASE2 -> fighting(level, a, now);
                case SEPARATION -> { if (now - a.since >= SEPARATION_END) enter(level, a, State.PHASE2); }
                case RIFT -> rift(level, a, now - a.since);
                case FALL -> fall(level, a, now);
                case RIFT_WAIT -> riftWait(level, a);
                case FINISHER -> finisher(level, a, now - a.since);
                // 收尾时那一块没加载（比如读档时处在终结）：加载出来以后补插遗剑
                case DEFEATED -> { if (now % 100 == 0) placeRelic(level, a); }
                default -> { }
            }
        }
    }

    /**
     * 读档后第一次：过场不重放，按第 14 节的表收拾。登场、离体、裂地直接跳到这段过场结束的样子（裂地之后就是第三阶段）；
     * 坠渊退回「没人了」（剑插在地上，等人靠近再从裂地第 2 秒重放）；终结直接收尾。临时方块一律拆掉（那一块还没加载的，
     * 记着等加载出来再拆）。剑祟本身晚一点加载出来时，由各状态每刻的处理把它摆对。
     */
    static void settle(ServerLevel level, Arena a) {
        a.loaded = false;
        for (long g : List.copyOf(a.guards.keySet())) removeGuard(level, a, g);
        switch (a.state) {
            case ENTRANCE -> enter(level, a, State.PHASE1);
            case SEPARATION -> enter(level, a, State.PHASE2);
            case RIFT -> {
                a.pendingDrop = level.getGameTime() - a.since < TELEPORT_AT;
                startFall(level, a, boss(level, a));
            }
            case FALL -> waitInRift(level, a);
            case FINISHER -> finishNow(level, a);
            default -> { }
        }
        arenas(level).setDirty();
    }

    private static void dormant(ServerLevel level, Arena a, long now) {
        JianSui boss = boss(level, a);
        if (boss == null) {
            if (now % 100 == 0) spawnRemains(level, a);
            return;
        }
        if (now % 10 != 0) return;
        if (!boss.move().equals("remains") && !boss.move().isEmpty()) boss.play("remains");
        Vec3 at = boss.position();
        for (ServerPlayer p : level.players()) {
            if (!fighter(p)) continue;
            double d = p.position().distanceTo(at);
            if (d < WAKE) {
                wake(level, boss);
                return;
            }
            // 有人走近：残骸里两只眼睛亮一下
            if (d < FLICKER && now % 40 == 0) boss.flash(level);
        }
    }

    private static void entrance(ServerLevel level, Arena a, long t) {
        JianSui boss = boss(level, a);
        if (boss == null) return;
        if (t == REASSEMBLE_AT) {
            boss.play("reassemble");
            level.playSound(null, boss.getX(), boss.getY() + 1, boss.getZ(), SoundEvents.CHAIN_BREAK, SoundSource.HOSTILE, 1.4f, .6f);
        }
        if (t == REASSEMBLE_AT + 90) {
            Player near = level.getNearestPlayer(boss, 32);
            boss.lookAt(near);
            level.playSound(null, boss.getX(), boss.getEyeY(), boss.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.HOSTILE, 2f, .6f);
        }
        if (t == TITLE_AT) JianSuiCue.send(players(level, a, false), new JianSuiCue(JianSuiCue.TITLE, level.getGameTime(), boss.position(), boss.position(), 0));
        if (t >= ENTRANCE_END) enter(level, a, State.PHASE1);
    }

    /** 没人以后还要多久血就一定回满（每秒回 2%，50 秒）。 */
    static final int REST_HEAL_FULL = 1000;

    /**
     * 打着：30 秒里场地里没有活着的玩家时，剑祟回到铁链旁、剑插在地上静立，血慢慢回满，血条收起；有人进来就接着打（第 5.7 节）。
     * 没人的时间照算，剑祟没加载时也算（人走远了那一块会卸掉）：再加载出来时直接摆成静立、按过了多久回血。
     * 只有场地里真有人时才让它开打（读档后存着的静立样子不会被自己解开）。
     */
    private static void fighting(ServerLevel level, Arena a, long now) {
        JianSui boss = boss(level, a);
        int phase = a.state == State.PHASE1 ? 1 : 2;
        if (now % 20 == 0) {
            retryShatter(level, a, boss);
            boolean anyone = players(level, a, false).stream().anyMatch(JianSuiFight::fighter);
            if (anyone) {
                // 人回来时剑祟还没加载：记着它静立了多久，加载出来再一次回够血
                if (boss == null) {
                    if (a.empty >= REST_TICKS) a.restOwed = Math.max(a.restOwed, a.empty);
                } else if (a.restOwed > 0) {
                    healFor(boss, a.restOwed);
                    a.restOwed = 0;
                }
                a.empty = 0;
                if (boss != null) fightingBoss(a, boss, phase);
            } else {
                a.empty = Math.min(a.empty + 20, REST_TICKS + REST_HEAL_FULL);
                if (boss != null && a.empty >= REST_TICKS) rest(a, boss);
            }
        }
        if (boss == null || a.empty >= REST_TICKS) return;
        if (now % 20 == 0) shatterNear(level, a, boss);
        unreachable(level, a, boss);
    }

    /** 没人静立了 {@code rested} 刻该回的血：30 秒以后每秒 2%，再过 50 秒回满。 */
    private static void healFor(JianSui boss, int rested) {
        if (rested < REST_TICKS) return;
        if (rested >= REST_TICKS + REST_HEAL_FULL) boss.setHealth(boss.getMaxHealth());
        else boss.heal(boss.getMaxHealth() * .02f * (rested - REST_TICKS) / 20f);
    }

    /** 转阶段时没加载、没碎掉的方块，那一块加载出来了就补碎。 */
    private static void retryShatter(ServerLevel level, Arena a, @Nullable JianSui boss) {
        if (a.pendingShatter.isEmpty()) return;
        a.pendingShatter.retainAll(a.placed);
        List<BlockPos> loaded = a.pendingShatter.stream().map(BlockPos::of).filter(level::isLoaded).toList();
        if (loaded.isEmpty()) return;
        for (BlockPos q : loaded) a.pendingShatter.remove(q.asLong());
        shatter(level, a, new ArrayList<>(loaded), boss);
    }

    /** 静立：回到插剑的地方、剑插在地上、血条收起，每秒回 2% 的血；没人够久了直接回满。 */
    private static void rest(Arena a, JianSui boss) {
        Vec3 at = plunge(a.site());
        if (boss.position().distanceToSqr(at) > 1) boss.teleportTo(at.x, at.y, at.z);
        boss.setScripted(true);
        if (!boss.move().equals("plunge_hold")) boss.play("plunge_hold");
        if (boss.barShown()) boss.showBar(false);
        if (a.empty >= REST_TICKS + REST_HEAL_FULL) boss.setHealth(boss.getMaxHealth());
        else boss.heal(boss.getMaxHealth() * .02f);
    }

    // ---------------------------------------------------------------- 裂地（第 9 节）

    private static void rift(ServerLevel level, Arena a, long t) {
        JianSui boss = boss(level, a);
        TaixuanSite site = a.site();
        Vec3 at = plunge(site);
        if (t == PLUNGE_IMPACT) {
            JianSuiCue.near(level, at, 96, new JianSuiCue(JianSuiCue.IMPACT, level.getGameTime(), at, at, 1));
            // 插剑那一下黑白闪（第 9 节 1.5 秒、第 15 节）
            for (ServerPlayer p : players(level, a, false)) ScreenEffects.flash(p, ScreenFlash.at(CameraPoint.world(at.add(0, 1, 0))));
            level.playSound(null, at.x, at.y, at.z, SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 2f, .45f);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 1.2f, .5f);
            // 冲击波推开周围的人（不伤害）
            for (ServerPlayer p : players(level, a, false)) {
                Vec3 away = p.position().subtract(at).multiply(1, 0, 1);
                double d = away.length();
                if (d < 7 && d > .01) {
                    away = away.normalize().scale(1.3 * (1 - d / 7) + .3);
                    p.push(away.x, .35, away.z);
                    p.hurtMarked = true;
                }
            }
        }
        if (t == CRACKS_FROM) {
            a.cracksAt = level.getGameTime();
            a.crackSent.clear();
            for (ServerPlayer p : level.players()) if (p.position().distanceToSqr(at) < 128 * 128) showCracks(level, a, p, at);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.DEEPSLATE_BREAK, SoundSource.HOSTILE, 2f, .5f);
        }
        if (t > CRACKS_FROM && t < WHITE_AT && t % 8 == 0) level.playSound(null, at.x, at.y, at.z, SoundEvents.DEEPSLATE_BREAK, SoundSource.HOSTILE, 1.4f, .4f + level.getRandom().nextFloat() * .2f);
        if (t == WHITE_AT) {
            for (ServerPlayer p : players(level, a, false)) ScreenEffects.cover(p, ScreenCover.white(WHITE_IN, TELEPORT_AT - WHITE_AT - WHITE_IN + 10, 30));
        }
        if (t == TELEPORT_AT) dropIn(level, a, boss, RIFT_END - TELEPORT_AT + 20);
        if (t >= TELEPORT_AT) {
            // 送下来以后魔力就锁着（第 10.5 节），不等第三阶段正式开始
            for (ServerPlayer p : players(level, a, true)) {
                PlayerMagic magic = PlayerMagic.of(p);
                magic.mana = 0;
                magic.manaSeen = 0;
            }
        }
        if (t == TELEPORT_AT + 40) {
            // 镜头从玩家脚下沿着碎块往上摇，停在最高处剑祟的剪影上，再回到玩家（告诉玩家路在哪，不用文字）
            Vec3 top = top(site);
            for (ServerPlayer p : players(level, a, true)) {
                Vec3 eye = p.getEyePosition();
                CameraShots.play(p, CameraShot.of(CameraPoint.world(eye.add(0, .2, 0)), CameraPoint.world(top.add(0, 2, 0))).timing(16, 30, 14).letterbox().cinema());
            }
        }
        if (t >= RIFT_END) startFall(level, a, boss);
    }

    /** 第三阶段开始：剑祟在顶块上、只剩一丝血，第一柄巨剑一秒后起手。 */
    static void startFall(ServerLevel level, Arena a, @Nullable JianSui boss) {
        enter(level, a, State.FALL);
        if (boss != null) {
            boss.setPhase(3);
            boss.setScripted(true);
            boss.play("idle");
            boss.showBar(true);
            boss.barProgress(.02f);
        }
        a.nextSword = level.getGameTime() + FIRST_SWORD;
        a.bossSpot = null;
        Vec3 exit = exitCrack(a.site());
        for (ServerPlayer p : players(level, a, true)) showExit(level, a, p, exit, true);
    }

    /**
     * 白屏里：在场的人送到空洞最低那块碎块上方，剑祟送到最高那块上，摆出第三阶段的场景（第 9 节 6 秒）。
     * 送下去的人在 {@code protectTicks} 刻里不受伤（裂地里护到第三阶段开始后 1 秒，第一柄巨剑之前就放开）。
     */
    static void dropIn(ServerLevel level, Arena a, @Nullable JianSui boss, int protectTicks) {
        TaixuanSite site = a.site();
        // 场景实体只放进已经加载的那一块（没加载的由 fall() 等加载出来再放，不会放出看不见的一份、之后又重复一份）
        if ((a.stage == null || !(level.getEntity(a.stage) instanceof FallStage)) && level.isLoaded(BlockPos.containing(site.axis(FallStage.CENTER_Y))))
            a.stage = FallStage.spawn(level, site).getUUID();
        int i = 0;
        for (ServerPlayer p : players(level, a, false)) {
            if (p.isSpectator() || inCavity(site, p.position())) continue;
            sendDown(level, a, p, i++);
            protect(level, p, protectTicks);
        }
        if (boss != null) {
            Vec3 top = top(site);
            boss.teleportTo(top.x, top.y, top.z);
            boss.play("idle");
        }
        arenas(level).setDirty();
    }

    /** 把一个人送到起点碎块上方，记下他从哪来（回去时用；本来就在空洞里的，比如在里面上线的，留着原来记的）。 */
    private static void sendDown(ServerLevel level, Arena a, ServerPlayer p, int index) {
        TaixuanSite site = a.site();
        // 先结束附身和化身（第 9 节）：附身的人身体留在上面，传送下去会被拉回身体；化身会飞的能直接飞上顶块
        if (dev.logos.runtime.Possession.possessing(p)) dev.logos.runtime.Possession.end(p, false);
        if (dev.logos.runtime.Incarnation.active(p)) dev.logos.runtime.Incarnation.end(p);
        if (!inCavity(site, p.position())) a.returns.put(p.getUUID(), new double[] {p.getX(), p.getY(), p.getZ(), p.getYRot()});
        a.reached.putIfAbsent(p.getUUID(), 0);
        double angle = index * 2.4;
        Vec3 at = stone(site, 0).add(Math.cos(angle) * .9, 2, Math.sin(angle) * .9);
        p.teleportTo(level, at.x, at.y, at.z, Set.of(), p.getYRot(), 0, true);
        p.setDeltaMovement(Vec3.ZERO);
        p.resetFallDistance();
        arenas(level).setDirty();
    }

    /** 没人了、读档：删掉第三阶段的画面，剑祟回到插剑的地方摆成剑插在地上的样子，裂缝暗着（第 14 节）。 */
    private static void waitInRift(ServerLevel level, Arena a) {
        clearStage(level, a);
        a.state = State.RIFT_WAIT;
        a.since = level.getGameTime();
        Vec3 at = plunge(a.site());
        hideCracks(level, a, at);
        JianSui boss = boss(level, a);
        if (boss != null) restInRift(a, boss);
        arenas(level).setDirty();
    }

    private static void restInRift(Arena a, JianSui boss) {
        Vec3 at = plunge(a.site());
        if (boss.position().distanceToSqr(at) > 1) boss.teleportTo(at.x, at.y, at.z);
        boss.setScripted(true);
        if (boss.phase() < 2) boss.setPhase(2);
        if (!boss.move().equals("plunge_hold")) boss.play("plunge_hold");
        boss.showBar(false);
    }

    /** 第三阶段里一直没人：剑祟在上面的场地，剑插在地上，裂缝暗着；有人走近时从裂地第 2 秒重放（第 14 节）。 */
    private static void riftWait(ServerLevel level, Arena a) {
        JianSui boss = boss(level, a);
        if (boss == null || level.getGameTime() % 10 != 0) return;
        restInRift(a, boss);
        Vec3 at = plunge(a.site());
        for (ServerPlayer p : level.players()) {
            if (fighter(p) && p.position().distanceTo(at) < 12) {
                enter(level, a, State.RIFT);
                // 下一刻就是裂地的第 2 秒（裂缝爬开）；剑已经插在地上，不再演插剑
                a.since = level.getGameTime() + 1 - CRACKS_FROM;
                boss.play("plunge_hold");
                // enter 按「从头演」护了整段裂地；往前挪了 since，保护也跟着收到第三阶段开始后 1 秒，第一柄巨剑之前放开
                for (ServerPlayer q : players(level, a, false)) PROTECTED.put(q.getUUID(), a.since + RIFT_END + 20);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- 第三阶段「坠渊」（第 10 节）

    private static void fall(ServerLevel level, Arena a, long now) {
        TaixuanSite site = a.site();
        JianSui boss = boss(level, a);
        if (a.stage == null || !(level.getEntity(a.stage) instanceof FallStage)) {
            BlockPos center = BlockPos.containing(site.axis(FallStage.CENTER_Y));
            if (level.isLoaded(center)) a.stage = FallStage.spawn(level, site).getUUID();
        }
        // 剑祟在裂地的关键那一刻没加载（没人在附近）、读档后才加载出来：把它摆成第三阶段的样子，站到顶块上
        if (boss != null && (boss.phase() != 3 || !boss.scripted() || flat(site, boss.position()) > TOP_WALK + 1.5
                || Math.abs(boss.getY() - top(site).y) > 1)) {
            Vec3 t = top(site);
            boss.teleportTo(t.x, t.y, t.z);
            boss.setPhase(3);
            boss.setScripted(true);
            boss.setHidden(0);
            boss.play("idle");
            boss.showBar(true);
            boss.barProgress(.02f);
            a.bossSpot = null;
        }
        List<ServerPlayer> inside = players(level, a, true);
        Vec3 exit = exitCrack(site);
        for (ServerPlayer p : inside) {
            // 魔力设成 0、不回复（第 10.5 节）；连「看到的魔力」一起清零，Logos 才不会把清掉的那些记成修行
            PlayerMagic magic = PlayerMagic.of(p);
            magic.mana = 0;
            magic.manaSeen = 0;
            if (now % 20 == 0) showExit(level, a, p, exit, false);
            if (p.isCreative()) continue;
            reach(level, site, a, p);
            voidFall(level, a, p, now);
            leave(level, a, p, exit, now);
        }
        pullDown(level, a, now);
        petsFall(level, a);
        if (now % 10 == 0) {
            rescueDrops(level, site);
            shatterCavity(level, a, inside, now);
        }
        if (now % 20 == 0) retryShatter(level, a, boss);
        if (boss != null) {
            walkTop(a, boss);
            giantSwords(level, a, boss, inside, now);
        }
        // 30 秒里空洞里没有活着的玩家：退回「没人了」的样子
        if (inside.stream().noneMatch(JianSuiFight::fighter)) {
            a.empty++;
            if (a.empty >= REST_TICKS) waitInRift(level, a);
        } else a.empty = 0;
    }

    /** 记下这个人到过的最高落脚台。 */
    private static void reach(ServerLevel level, TaixuanSite site, Arena a, ServerPlayer p) {
        int have = a.reached.getOrDefault(p.getUUID(), 0);
        var list = TaixuanSite.cavity().getAsJsonArray("checkpoints");
        for (int i = list.size(); i > have; i--) {
            Vec3 c = checkpoint(site, i);
            if (p.onGround() && Math.abs(p.getY() - c.y) < .6 && Math.hypot(p.getX() - c.x, p.getZ() - c.z) < 2.6) {
                a.reached.put(p.getUUID(), i);
                arenas(level).setDirty();
                level.playSound(null, c.x, c.y, c.z, SoundEvents.RESPAWN_ANCHOR_SET_SPAWN, SoundSource.PLAYERS, .6f, 1.4f);
                break;
            }
        }
    }

    /** 黑下去之后多少刻送回落脚台（黑屏 6 刻盖满）。 */
    static final int VOID_RETURN = 6;

    /**
     * 掉进虚空：黑下去，送回到过的最高落脚台，受一点伤（不会因此死），再亮起来（第 10.4 节）。
     * 黑下去的这几刻里人还在往下掉，洞底只在下面三四格：这段时间的摔落距离一直清零，落到洞底也不摔伤（{@link #fallDamage}）。
     */
    private static void voidFall(ServerLevel level, Arena a, ServerPlayer p, long now) {
        Long started = a.falling.get(p.getUUID());
        if (started == null) {
            if (p.getY() < VOID_FEET) {
                a.falling.put(p.getUUID(), now);
                p.resetFallDistance();
                ScreenEffects.cover(p, ScreenCover.black(VOID_RETURN, 6, 10));
            }
            return;
        }
        p.resetFallDistance();
        // 过了好几刻还没送回去（中间不在空洞里、刚复活）：这一次作废，不再接着送
        if (now - started > VOID_RETURN + 2) {
            a.falling.remove(p.getUUID());
            return;
        }
        if (now - started >= VOID_RETURN) {
            Vec3 back = checkpoint(a.site(), a.reached.getOrDefault(p.getUUID(), 0)).add(0, .1, 0);
            p.teleportTo(level, back.x, back.y, back.z, Set.of(), p.getYRot(), 0, true);
            p.setDeltaMovement(Vec3.ZERO);
            p.resetFallDistance();
            float hurt = Math.min(VOID_DAMAGE, p.getHealth() - 1);
            if (hurt > 0) {
                p.invulnerableTime = 0;
                p.hurtServer(level, level.damageSources().fellOutOfWorld(), hurt);
            }
            a.returned.put(p.getUUID(), now);
            a.falling.remove(p.getUUID());
        }
    }

    /**
     * 第三阶段里掉进虚空的（人、同伴、宠物）落到洞底不摔伤。按位置判断，不等每刻的处理把人记进「正在掉」：
     * 玩家的移动包在两刻之间处理，从高处掉下来时，越过那条线和落到洞底可能在同一批包里。人在这里顺手记进「正在掉」，黑屏开始。
     */
    @SubscribeEvent
    static void fallDamage(net.neoforged.neoforge.event.entity.living.LivingFallEvent event) {
        LivingEntity e = event.getEntity();
        if (!(e.level() instanceof ServerLevel level)) return;
        Arenas data = level.getDataStorage().get(Arenas.TYPE);
        if (data == null) return;
        for (Arena a : data.arenas.values()) {
            if (a.state != State.FALL || !inCavity(a.site(), e.position())) continue;
            boolean falling = a.falling.containsKey(e.getUUID());
            if (!falling && e.getY() >= VOID_FEET) continue;
            event.setCanceled(true);
            if (!falling && e instanceof ServerPlayer p && !p.isCreative() && !p.isSpectator()) {
                a.falling.put(p.getUUID(), level.getGameTime());
                ScreenEffects.cover(p, ScreenCover.black(VOID_RETURN, 6, 10));
            }
            return;
        }
    }

    /** 死了：清掉他正在进行的掉落、回程、吸下去（复活后不再接着演）。 */
    @SubscribeEvent
    static void respawned(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) forget(p);
    }

    /**
     * 同伴和宠物掉进虚空（第 10.4 节）：它们自己「掉进虚空」的反应要到世界底才有，在那之前会摔在洞底。
     * 生物在服务端移动，越过那条线的那一刻就接住：送回主人到过的最高落脚台（没主人在空洞里就送起点），扣一点血，不摔伤。
     */
    private static void petsFall(ServerLevel level, Arena a) {
        TaixuanSite site = a.site();
        Vec3 axis = site.axis(VOID_FEET);
        AABB below = new AABB(axis.x - CAVITY_RADIUS, CAVITY_LOW, axis.z - CAVITY_RADIUS, axis.x + CAVITY_RADIUS, VOID_FEET, axis.z + CAVITY_RADIUS);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, below, e -> !(e instanceof Player) && e.isAlive() && owner(e) != null)) {
            if (flat(site, e.position()) > CAVITY_RADIUS + 2) continue;
            UUID owner = owner(e);
            Vec3 back = checkpoint(site, a.reached.getOrDefault(owner, 0)).add(.6, .1, .6);
            e.teleportTo(back.x, back.y, back.z);
            e.setDeltaMovement(Vec3.ZERO);
            e.resetFallDistance();
            float hurt = Math.min(VOID_DAMAGE, e.getHealth() - 1);
            if (hurt > 0) {
                e.invulnerableTime = 0;
                e.hurtServer(level, level.damageSources().fellOutOfWorld(), hurt);
            }
        }
    }

    /** 同伴、驯服的宠物的主人（不是这两类的返回 null）。 */
    private static @Nullable UUID owner(LivingEntity e) {
        if (e instanceof dev.logos.dimension.companion.CompanionEntity c) return c.ownerId();
        UUID master = dev.logos.runtime.Familiars.masterOf(e);
        if (master != null) return master;
        if (e instanceof net.minecraft.world.entity.OwnableEntity o && o.getOwnerReference() != null) return o.getOwnerReference().getUUID();
        return null;
    }

    /** 蹲在起点碎块正中的裂缝上 2 秒：白一下，回到上面的场地（第 10.4 节）。 */
    private static void leave(ServerLevel level, Arena a, ServerPlayer p, Vec3 exit, long now) {
        Long started = a.leaving.get(p.getUUID());
        if (started != null) {
            // 过了好几刻才轮到（中间不在空洞里）：这一次作废，不再接着送
            if (now - started > PULL_TELEPORT + 2) a.leaving.remove(p.getUUID());
            else if (now - started >= PULL_TELEPORT) {
                a.leaving.remove(p.getUUID());
                sendBack(level, a, p);
            }
            return;
        }
        boolean on = p.isShiftKeyDown() && p.onGround() && Math.abs(p.getY() - exit.y) < .6 && Math.hypot(p.getX() - exit.x, p.getZ() - exit.z) < 1.1;
        int held = on ? a.crouch.merge(p.getUUID(), 1, Integer::sum) : 0;
        if (!on) a.crouch.remove(p.getUUID());
        if (held >= EXIT_CROUCH) {
            a.crouch.remove(p.getUUID());
            a.leaving.put(p.getUUID(), now);
            ScreenEffects.cover(p, ScreenCover.white(10, PULL_TELEPORT, 20));
            protect(level, p, PULL_TELEPORT + 20);
            level.playSound(null, exit.x, exit.y, exit.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1f, 1.2f);
        }
    }

    /** 第三阶段里才来的人：踩到石台上发光的裂缝，白一下被吸下去（第 14 节）。也给走近的人补发裂缝的样子。 */
    private static void pullDown(ServerLevel level, Arena a, long now) {
        TaixuanSite site = a.site();
        Vec3 at = plunge(site);
        for (ServerPlayer p : level.players()) {
            if (!p.isAlive() || p.isSpectator() || !inArena(site, p.position())) continue;
            if (now % 20 == 0 && p.position().distanceToSqr(at) < 96 * 96) showCracks(level, a, p, at);
            Long started = a.pulling.get(p.getUUID());
            if (started != null) {
                if (now - started > PULL_TELEPORT + 2) a.pulling.remove(p.getUUID());
                else if (now - started >= PULL_TELEPORT) {
                    a.pulling.remove(p.getUUID());
                    sendDown(level, a, p, 0);
                    showExit(level, a, p, exitCrack(site), true);
                    a.returned.put(p.getUUID(), now);
                }
                continue;
            }
            if (!fighter(p) || !p.onGround() || Math.abs(p.getY() - at.y) > 1.2 || Math.hypot(p.getX() - at.x, p.getZ() - at.z) > 1.8) continue;
            a.pulling.put(p.getUUID(), now);
            ScreenEffects.cover(p, ScreenCover.white(10, PULL_TELEPORT, 24));
            protect(level, p, PULL_TELEPORT + 40);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1f, .7f);
        }
        a.pulling.keySet().removeIf(id -> level.getServer().getPlayerList().getPlayer(id) == null);
    }

    /** 掉下去的掉落物、经验球、弹开的箭和三叉戟也送回起点碎块（不然死一次东西就全丢在洞底）。 */
    private static void rescueDrops(ServerLevel level, TaixuanSite site) {
        Vec3 axis = site.axis(VOID_FEET);
        AABB below = new AABB(axis.x - CAVITY_RADIUS, CAVITY_LOW, axis.z - CAVITY_RADIUS, axis.x + CAVITY_RADIUS, VOID_FEET, axis.z + CAVITY_RADIUS);
        Vec3 start = stone(site, 0).add(0, .5, 0);
        // 方盒子的四个角伸到洞壁外面（天然洞穴里）：只管空洞这个圆柱里面的
        for (Entity e : level.getEntitiesOfClass(Entity.class, below, e -> (e instanceof ItemEntity || e instanceof ExperienceOrb || e instanceof Projectile)
                && flat(site, e.position()) <= CAVITY_RADIUS)) {
            e.teleportTo(start.x, start.y, start.z);
            e.setDeltaMovement(Vec3.ZERO);
        }
    }

    /** 剑祟沿顶块边缘走到离目标最近的那一侧（每刻最多 0.3 格）。 */
    private static void walkTop(Arena a, JianSui boss) {
        if (a.bossSpot == null) return;
        Vec3 d = a.bossSpot.subtract(boss.position()).multiply(1, 0, 1);
        double length = d.length();
        if (length < .05) return;
        boss.move(MoverType.SELF, d.scale(Math.min(.3, length) / length));
    }

    /**
     * 巨剑贯落（第 10.3 节）：剑祟沿顶块边缘转向离目标最近的那一侧、抬手指着他；从剑祟手边到他脚下连出一道光，
     * 光点沿这条线往里聚 {@value #SWORD_AIM} 刻（线在起手时就定死，走开就躲掉了），然后一柄巨剑射下来，
     * {@value #SWORD_FLIGHT} 刻到底：离线 0.8 格以内、离线尽头 1.2 格以内算打中，伤害 10，顺着剑的方向往下往外击退约 6 格。
     * 盾挡得住伤害、挡不住击退。同一个人两次被瞄准至少隔 4 秒，刚被送回起点的 2 秒内不瞄准；偏向爬得最高的人。
     * 爬过第一个落脚台的人，有时两柄先后射来（隔 0.5 秒）；过了第二个落脚台，三柄成扇形。
     */
    private static void giantSwords(ServerLevel level, Arena a, JianSui boss, List<ServerPlayer> inside, long now) {
        // 拷一份再结算（结算时可能改动 a.swords）。按「到了或过了」算，哪一刻没轮到也不会卡住；
        // 过了好几刻才轮到的（剑祟那一块卸掉过）不再结算——客户端那边的剑早就落完了
        for (Sword s : List.copyOf(a.swords)) {
            long late = now - s.start - (SWORD_AIM + SWORD_FLIGHT);
            if (late >= 0 && a.swords.remove(s) && late <= 2) strikeGiant(level, a, boss, s, now);
        }
        if (now < a.nextSword || !a.swords.isEmpty()) return;
        ServerPlayer target = null;
        TaixuanSite here = a.site();
        for (ServerPlayer p : inside) {
            // 这一刻刚从起点的裂缝送上去的人不在 inside 外面，但已经不在空洞里了
            if (!fighter(p) || !inCavity(here, p.position()) || a.falling.containsKey(p.getUUID()) || a.leaving.containsKey(p.getUUID())) continue;
            if (now - a.aimed.getOrDefault(p.getUUID(), -9999L) < SWORD_SAME_TARGET) continue;
            if (now - a.returned.getOrDefault(p.getUUID(), -9999L) < SWORD_AFTER_RETURN) continue;
            if (target == null || p.getY() > target.getY()) target = p;
        }
        if (target == null) return;
        TaixuanSite site = a.site();
        Vec3 axis = site.axis(boss.getY());
        Vec3 out = target.position().subtract(axis).multiply(1, 0, 1);
        out = out.lengthSqr() < .01 ? new Vec3(1, 0, 0) : out.normalize();
        a.bossSpot = new Vec3(axis.x + out.x * TOP_WALK, boss.getY(), axis.z + out.z * TOP_WALK);
        // 出手的地方：顶块边缘外、手的高度；剑在那里成形，不和顶块碰撞
        Vec3 from = axis.add(out.scale(7.2)).add(0, 2.6, 0);
        int progress = a.reached.getOrDefault(target.getUUID(), 0);
        List<Vec3> aims = new ArrayList<>();
        aims.add(target.position());
        if (progress >= 2) {
            // 三柄成扇形：落点在目标两侧各偏 2.2 格（横着偏）
            Vec3 side = new Vec3(-out.z, 0, out.x).scale(2.2);
            aims.add(target.position().add(side));
            aims.add(target.position().subtract(side));
        }
        for (Vec3 to : aims) launch(level, a, target, from, to, now);
        if (progress == 1 && level.getRandom().nextFloat() < .4f) {
            // 两柄先后射来：第二柄 0.5 秒后起手，瞄那时候他在哪；这期间被弹反了（换了一轮）就不射
            ServerPlayer again = target;
            int volley = a.volley;
            later(level, a, now + SWORD_SECOND, () -> {
                if (a.state == State.FALL && a.volley == volley && again.isAlive() && inCavity(site, again.position()) && !a.falling.containsKey(again.getUUID()))
                    launch(level, a, again, from, again.position(), level.getGameTime());
            });
        }
        a.aimed.put(target.getUUID(), now);
        a.nextSword = now + SWORD_EVERY;
        boss.face(target, 180);
        boss.lookAt(target);
        boss.play("point");
    }

    private static void launch(ServerLevel level, Arena a, ServerPlayer target, Vec3 from, Vec3 to, long now) {
        // 落点下面 1.5 格以内有碎块才扎得住，落点贴到碎块面上（人正跳在半空也扎在他脚下的碎块上）；再往下没有碎块的穿过去掉下去
        Vec3 surface = surface(level, to, 1.5);
        boolean stuck = surface != null;
        if (stuck) to = surface;
        Sword s = new Sword(target.getUUID(), from, to, now, stuck);
        a.swords.add(s);
        JianSuiCue.near(level, from, 96, new JianSuiCue(JianSuiCue.GIANT_SWORD, now, from, to, stuck ? 1 : 0));
        level.playSound(null, from.x, from.y, from.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.HOSTILE, 1.6f, .6f);
    }

    /** {@code at} 正下方 {@code depth} 格以内第一个能站的面（碰撞箱的顶）；没有时 null。只看已加载的。 */
    private static @Nullable Vec3 surface(ServerLevel level, Vec3 at, double depth) {
        BlockPos start = BlockPos.containing(at.x, at.y - 1e-3, at.z);
        for (int dy = 0; dy <= Math.ceil(depth) + 1; dy++) {
            BlockPos q = start.below(dy);
            if (!level.isLoaded(q)) return null;
            var shape = level.getBlockState(q).getCollisionShape(level, q);
            if (shape.isEmpty()) continue;
            double top = q.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
            return at.y - top <= depth ? new Vec3(at.x, top, at.z) : null;
        }
        return null;
    }

    private static void strikeGiant(ServerLevel level, Arena a, JianSui boss, Sword s, long now) {
        Vec3 dir = s.to.subtract(s.from);
        double length = dir.length();
        Vec3 unit = dir.scale(1 / Math.max(1e-6, length));
        level.playSound(null, s.to.x, s.to.y, s.to.z, SoundEvents.TRIDENT_HIT_GROUND, SoundSource.HOSTILE, 2f, .5f);
        for (ServerPlayer p : level.getEntitiesOfClass(ServerPlayer.class, new AABB(s.from, s.to).inflate(2))) {
            if (!fighter(p)) continue;
            Vec3 c = p.position().add(0, .9, 0);
            double along = c.subtract(s.from).dot(unit);
            Vec3 nearest = s.from.add(unit.scale(Math.max(0, Math.min(length, along))));
            boolean hit = c.distanceTo(nearest) <= .8 + .3 || c.distanceTo(s.to.add(0, .9, 0)) <= 1.2;
            if (!hit) continue;
            // 盾挡得住伤害，挡不住击退，也不破盾；拿带弹刀的武器在剑到的那一刻弹反，剑被弹回去
            var source = level.damageSources().mobAttack(boss);
            p.invulnerableTime = 0;
            if (dev.logos.dimension.combat.BossStrike.blocks(p, source, SWORD_DAMAGE)) {
                boss.takeParried();
                p.hurtServer(level, source, SWORD_DAMAGE);
                if (boss.takeParried()) {
                    reflect(level, a, boss, s, p, now);
                    return;
                }
            } else p.hurtServer(level, source, SWORD_DAMAGE); // 原版的生物近战伤害自己按难度缩放，这里不再乘
            Vec3 push = new Vec3(unit.x, 0, unit.z);
            push = push.lengthSqr() < 1e-4 ? Vec3.ZERO : push.normalize();
            p.setDeltaMovement(push.x * 1.35, .28, push.z * 1.35);
            p.hurtMarked = true;
        }
        if (s.stuck) placeGuard(level, a, s, unit, now);
    }

    /**
     * 巨剑被弹反：这一柄原路飞回去打在剑祟身上，它晃一下，{@value #SWORD_PARRY_REST} 刻里不射剑；人不被击退，也不留剑格。
     * 同一轮里已经射出的别的剑照常落下（客户端也照常画）；还没起手的第二柄不再射。
     */
    private static void reflect(ServerLevel level, Arena a, JianSui boss, Sword s, ServerPlayer by, long now) {
        Vec3 chest = boss.position().add(0, 2.4, 0);
        // value 是这柄剑起手到现在过了几刻：客户端用它认出是哪一柄（同一个落点可能先后有两柄）
        JianSuiCue.near(level, s.to, 128, new JianSuiCue(JianSuiCue.REFLECT, now, s.to, chest, now - s.start));
        later(level, a, now + REFLECT_FLIGHT, () -> {
            // 弹回去的剑飞到之前进了终结：不再打断黑白里摆好的样子
            if (!boss.isAlive() || a.state != State.FALL) return;
            boss.play("parried");
            level.playSound(null, chest.x, chest.y, chest.z, SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 1.4f, 1.2f);
            level.playSound(null, chest.x, chest.y, chest.z, SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.HOSTILE, 1.6f, .6f);
        });
        level.playSound(null, s.to.x, s.to.y + 1, s.to.z, SoundEvents.TRIDENT_RETURN, SoundSource.PLAYERS, 1.6f, .8f);
        a.volley++;
        a.nextSword = Math.max(a.nextSword, now + SWORD_PARRY_REST);
        arenas(level).setDirty();
    }

    /**
     * 扎住的巨剑在剑格那一格临时放一格看不见的碰撞方块，顶面比碎块面高一格，8 秒后随剑散掉一起拆掉；
     * 借它能跳上高 2 格的地方（第 10.1 节的两处）。离顶块 7 格以内的不放，格子里有人、有东西的不放。
     */
    private static void placeGuard(ServerLevel level, Arena a, Sword s, Vec3 unit, long now) {
        if (unit.y > -.2) return;
        // 剑格在落点沿剑往回 guardBack 格（剑尖扎进碎块里），离碎块面一格；客户端画的剑按同一个数往下扎
        Vec3 guard = s.to.subtract(unit.scale(guardBack(unit)));
        BlockPos cell = BlockPos.containing(guard.x, s.to.y + .5, guard.z);
        if (Vec3.atCenterOf(cell).distanceTo(top(a.site())) < GUARD_KEEP_OFF_TOP) return;
        // 不压在起点那道回去的裂缝上：蹲的那一圈（离正中 1.1 格以内）碰到的格子都不放，不然 8 秒里蹲不上去
        if (Vec3.atBottomCenterOf(cell).distanceTo(exitCrack(a.site())) < 1.6) return;
        if (!level.isLoaded(cell) || !level.getBlockState(cell).isAir() || !level.getEntities((Entity) null, new AABB(cell)).isEmpty()) return;
        level.setBlock(cell, JianSuiBlocks.SWORD_GUARD.get().defaultBlockState(), Block.UPDATE_ALL);
        a.guards.put(cell.asLong(), now + SWORD_STUCK);
        arenas(level).setDirty();
    }

    private static void guards(ServerLevel level, Arena a, long now) {
        if (a.guards.isEmpty() || now % 5 != 0) return;
        for (var e : List.copyOf(a.guards.entrySet())) if (e.getValue() > 0 && now >= e.getValue()) removeGuard(level, a, e.getKey());
    }

    /** 拆一格剑格。那一块没加载时不能拆：记成「等加载出来再拆」（{@link #guards} 每 5 刻重试），不从记录里丢掉。 */
    private static void removeGuard(ServerLevel level, Arena a, long key) {
        BlockPos pos = BlockPos.of(key);
        if (!level.isLoaded(pos)) {
            a.guards.put(key, 1L);
            arenas(level).setDirty();
            return;
        }
        if (level.getBlockState(pos).is(JianSuiBlocks.SWORD_GUARD.get())) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        a.guards.remove(key);
        arenas(level).setDirty();
    }

    static void clearStage(ServerLevel level, Arena a) {
        if (a.stage != null && level.getEntity(a.stage) instanceof FallStage stage) stage.discard();
        Vec3 axis = a.site().axis(FallStage.CENTER_Y);
        for (FallStage stage : level.getEntitiesOfClass(FallStage.class, new AABB(axis, axis).inflate(40))) stage.discard();
        a.stage = null;
        a.swords.clear();
        a.falling.clear();
        a.leaving.clear();
        a.pulling.clear();
        a.crouch.clear();
        a.bossSpot = null;
        a.pendingDrop = false;
        for (long g : List.copyOf(a.guards.keySet())) removeGuard(level, a, g);
    }

    // ---------------------------------------------------------------- 裂缝

    /**
     * 给这个人发石台裂缝的样子，隔 {@link #CRACK_REFRESH} 刻重发一次（客户端隔两倍时间没收到就收掉：重连、换维度回来的人
     * 也看得到，没收到「收起」的人也不会一直亮着）。时间用裂缝开始爬的那一刻，后来的人看到的是已经爬满的。
     */
    private static void showCracks(ServerLevel level, Arena a, ServerPlayer p, Vec3 at) {
        long now = level.getGameTime();
        if (now - a.crackSent.getOrDefault(p.getUUID(), Long.MIN_VALUE / 2) < CRACK_REFRESH) return;
        a.crackSent.put(p.getUUID(), now);
        if (a.cracksAt == Long.MIN_VALUE) a.cracksAt = now - 200;
        JianSuiCue.send(List.of(p), new JianSuiCue(JianSuiCue.CRACKS, a.cracksAt, at, at, CRACKS_RADIUS));
    }

    /** 起点碎块上那道回去的裂缝，同样隔 {@link #CRACK_REFRESH} 刻重发；{@code now} 为真时不管上次什么时候发的，马上发。 */
    private static void showExit(ServerLevel level, Arena a, ServerPlayer p, Vec3 exit, boolean now) {
        long t = level.getGameTime();
        if (!now && t - a.exitSent.getOrDefault(p.getUUID(), Long.MIN_VALUE / 2) < CRACK_REFRESH) return;
        a.exitSent.put(p.getUUID(), t);
        JianSuiCue.send(List.of(p), new JianSuiCue(JianSuiCue.CRACKS, a.since - 200, exit, exit, EXIT_CRACK_RADIUS));
    }

    /** 裂缝暗下去（客户端删掉这一处的裂缝；离得远、不在线的人那边的，隔一阵没再收到也会自己收掉）。 */
    private static void hideCracks(ServerLevel level, Arena a, Vec3 at) {
        a.crackSent.clear();
        a.exitSent.clear();
        a.cracksAt = Long.MIN_VALUE;
        Vec3 exit = exitCrack(a.site());
        for (ServerPlayer p : level.players()) {
            if (p.position().distanceToSqr(at) > 160 * 160) continue;
            JianSuiCue.send(List.of(p), new JianSuiCue(JianSuiCue.CRACKS, level.getGameTime(), at, at, 0));
            JianSuiCue.send(List.of(p), new JianSuiCue(JianSuiCue.CRACKS, level.getGameTime(), exit, exit, 0));
        }
    }

    // ---------------------------------------------------------------- 玩家放的方块（第 5.7、10.5 节）

    /** 战斗进行时（登场到终结），战斗范围里玩家放的方块都记下来。 */
    @SubscribeEvent
    static void placed(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Player)) return;
        Arenas data = level.getDataStorage().get(Arenas.TYPE);
        if (data == null) return;
        BlockPos pos = event.getPos();
        for (Arena a : data.arenas.values()) {
            if (a.state == State.DORMANT || a.state == State.DEFEATED || !inFight(a.site(), Vec3.atCenterOf(pos))) continue;
            a.placed.add(pos.asLong());
            a.placedAt.put(pos.asLong(), level.getGameTime());
            data.setDirty();
        }
    }

    /**
     * 剑祟一剑把这些方块斩碎：方块淡青白地消散，不掉落（同白梅烧方块的做法）。
     * 只碎玩家放的、还在的；已经没了的从记录里删掉。
     */
    static int shatter(ServerLevel level, Arena a, List<BlockPos> positions, @Nullable JianSui by) {
        List<BlockPos> gone = new ArrayList<>();
        for (BlockPos pos : positions) {
            // 那一块没加载：记录留着，等加载出来、下一次再碎
            if (!level.isLoaded(pos)) continue;
            a.placed.remove(pos.asLong());
            a.placedAt.remove(pos.asLong());
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.is(JianSuiBlocks.SWORD_GUARD.get()) || state.is(JianSuiBlocks.AXIAL_SWORD.get())) continue;
            gone.add(pos);
        }
        arenas(level).setDirty();
        if (gone.isEmpty()) return 0;
        Vec3 at = by != null ? by.position().add(0, 2, 0) : Vec3.atCenterOf(gone.getFirst());
        Dissolving.blocks(level, gone, DissolveShape.point(at.x, at.y, at.z), 16, SHATTER_COLOR);
        Vec3 first = Vec3.atCenterOf(gone.getFirst());
        level.playSound(null, first.x, first.y, first.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 1.6f, .6f);
        level.playSound(null, first.x, first.y, first.z, SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 1f, .7f);
        return gone.size();
    }

    /** 第一、二阶段：剑祟身边 3 格内玩家放的方块、倒的水和岩浆，斩碎。 */
    private static void shatterNear(ServerLevel level, Arena a, JianSui boss) {
        List<BlockPos> hit = new ArrayList<>();
        for (long key : a.placed) if (Vec3.atCenterOf(BlockPos.of(key)).distanceToSqr(boss.position().add(0, 1, 0)) <= 9 + 4) hit.add(BlockPos.of(key));
        fluids(level, boss.blockPosition(), 3, hit);
        if (!hit.isEmpty()) shatter(level, a, hit, boss);
    }

    /** 第一、二阶段：目标在剑祟够不到的高处（垫方块、搭柱子）站了 3 秒：斩碎他脚下周围玩家放的方块。 */
    private static void unreachable(ServerLevel level, Arena a, JianSui boss) {
        LivingEntity target = boss.getTarget();
        boolean high = target != null && target.getY() > boss.getY() + 2.2 && Math.hypot(target.getX() - boss.getX(), target.getZ() - boss.getZ()) < 8;
        a.unreachable = high ? a.unreachable + 1 : 0;
        if (a.unreachable < UNREACHABLE_TICKS) return;
        a.unreachable = 0;
        List<BlockPos> hit = new ArrayList<>();
        for (long key : a.placed) {
            BlockPos pos = BlockPos.of(key);
            if (Math.hypot(pos.getX() + .5 - target.getX(), pos.getZ() + .5 - target.getZ()) <= 4 && pos.getY() <= target.getY() + 1) hit.add(pos);
        }
        fluids(level, target.blockPosition(), 3, hit);
        if (!hit.isEmpty()) shatter(level, a, hit, boss);
    }

    /** 第三阶段：空洞里玩家放的方块放下 1.5 秒就被斩碎；每个人身边倒的水、岩浆也是。 */
    private static void shatterCavity(ServerLevel level, Arena a, List<ServerPlayer> inside, long now) {
        TaixuanSite site = a.site();
        List<BlockPos> hit = new ArrayList<>();
        for (long key : a.placed) {
            BlockPos pos = BlockPos.of(key);
            if (inCavity(site, Vec3.atCenterOf(pos)) && now - a.placedAt.getOrDefault(key, 0L) >= SHATTER_AFTER) hit.add(pos);
        }
        for (ServerPlayer p : inside) fluids(level, p.blockPosition(), 4, hit);
        if (!hit.isEmpty()) shatter(level, a, hit, null);
    }

    /** {@code at} 周围玩家倒的水、岩浆（流体源方块）。 */
    private static void fluids(ServerLevel level, BlockPos at, int radius, List<BlockPos> out) {
        BlockPos.betweenClosedStream(at.offset(-radius, -2, -radius), at.offset(radius, 4, radius))
                .filter(p -> !level.getFluidState(p).isEmpty() && level.getFluidState(p).isSource())
                .map(BlockPos::immutable).limit(64).forEach(out::add);
    }

    // ---------------------------------------------------------------- 终结（第 11 节）

    /**
     * 第三阶段里有人近战打中剑祟：进终结。只认真正的近战（手里的东西直接打的，不在术式里）。
     * 这一下在玩家这一刀的结算里，所以这里只记下来、停住往上流的东西、护住空洞里的人；黑白的「飞身刺」和暂停战斗骨架下一刻再做。
     */
    static boolean finisherHit(ServerLevel level, JianSui boss, ServerPlayer by) {
        Arena a = arenaOf(level, boss);
        if (a == null || a.state != State.FALL) return false;
        a.finisher = by.getUUID();
        enter(level, a, State.FINISHER);
        a.finisherViewers.clear();
        a.finisherShots.clear();
        a.pryBeats = a.pryQueued = 0;
        a.pryLast = Long.MIN_VALUE / 2;
        a.burstAt = -1;
        FallStage stage = a.stage == null ? null : level.getEntity(a.stage) instanceof FallStage s ? s : null;
        // 打中的那一下，四周往上流的东西一下子停住（不再往下掉了）；0.15 秒的顿帧由黑白特写开头那几刻做
        if (stage != null) stage.stopNow();
        for (ServerPlayer p : players(level, a, true)) protect(level, p, FIN_LAST + 20);
        level.playSound(null, boss.getX(), boss.getY() + 2, boss.getZ(), SoundEvents.TRIDENT_HIT, SoundSource.HOSTILE, 2f, .6f);
        return true;
    }

    /**
     * 终结按「从打中算起的第几刻」逐刻走（第 11 节，时刻见 {@link #FIN_SHOT} 起的常量）；打中的人死了、下线了、走开了，照样走完。
     * 撬三下：打中的人按的（{@link #pryPress}）在撬剑的窗口里排队，每刻最多撬一下、两下至少隔 {@link #PRY_GAP} 刻；
     * 不按的话第 i 下最晚在 {@link #PRY_AUTO}[i] 自己撬。第三下之后 {@link #BURST_AFTER} 刻崩出，落剑、收尾都从崩出算。
     */
    private static void finisher(ServerLevel level, Arena a, long t) {
        JianSui boss = boss(level, a);
        Vec3 at = plunge(a.site());
        ServerPlayer hitter = hitter(level, a);
        if (t == FIN_SHOT) thrust(level, a, boss, hitter);
        if (t == FIN_SWAP) swap(level, a, boss, hitter, at);
        if (boss == null) {
            // 剑祟没了（不该有）：等不到崩出，回到上面以后直接收尾
            if (t >= FIN_COLOUR) finishNow(level, a);
            return;
        }
        if (t == FIN_COLOUR) colour(level, a, boss, hitter, at);
        if (a.burstAt < 0) {
            if (t >= PRY_OPEN && a.pryBeats < PRY_AUTO.length && t - a.pryLast >= PRY_GAP && (a.pryQueued > 0 || t >= PRY_AUTO[a.pryBeats])) {
                if (a.pryQueued > 0) a.pryQueued--;
                a.pryLast = t;
                pryBeat(level, a, boss, hitter, a.pryBeats++);
            }
            if (a.pryBeats >= PRY_AUTO.length && t >= a.pryLast + BURST_AFTER) burst(level, a, boss, hitter, at, t);
            return;
        }
        long since = t - a.burstAt;
        if (since == FIN_WIDE_AFTER) {
            // 退远一点：所有人的镜头退到同一个机位（看得到散架、遗剑落下），正好在收尾那一刻往回走
            Vec3 out = outward(a.site());
            CameraShot shot = wideShot(level, at, out, side(out, hitter)).timing(14, GONE_AFTER - FIN_WIDE_AFTER - 14, FIN_RETURN);
            for (ServerPlayer p : finisherViewers(level, a)) camera(a, p, shot);
        }
        if (since == LAND_AFTER) {
            placeRelic(level, a);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 1.4f, .8f);
        }
        if (since >= GONE_AFTER) finishNow(level, a);
    }

    /**
     * 黑白的「飞身刺」：空洞里的人都看特写，打中的人是出手的那个（附近别的人只看到两个人的姿势）。同一刻暂停打中的人的战斗骨架
     * （原版手臂和手里的东西照常画），告诉附近的客户端谁在撬、撬哪一只（撬剑的姿势、打中的人的攻击键都按它）。
     */
    private static void thrust(ServerLevel level, Arena a, @Nullable JianSui boss, @Nullable ServerPlayer hitter) {
        List<ServerPlayer> viewers = players(level, a, true);
        if (hitter != null && !viewers.contains(hitter)) viewers.add(hitter);
        for (ServerPlayer p : viewers) a.finisherViewers.add(p.getUUID());
        if (boss != null) {
            ExecutionShots.play(viewers, hitter, hitter, boss, ExecutionShot.thrust(level.getRandom().nextInt()));
            if (hitter != null) JianSuiPry.Start.near(level, boss.position(), 128, new JianSuiPry.Start(hitter.getId(), boss.getId(), a.since));
        }
        if (hitter != null) LongswordCombat.pause(hitter);
    }

    /**
     * 黑白定格里换场景：空洞里的人回到上面各自记下的位置，剑祟摆到插剑的地方、朝外，打中它的人站在它面前、面朝它，
     * 空洞里的场景收掉，裂缝暗下去。
     */
    private static void swap(ServerLevel level, Arena a, @Nullable JianSui boss, @Nullable ServerPlayer hitter, Vec3 at) {
        for (ServerPlayer p : players(level, a, true)) sendBack(level, a, p);
        hideCracks(level, a, at);
        if (boss != null) {
            Vec3 out = outward(a.site());
            float yaw = (float) Math.toDegrees(Math.atan2(-out.x, out.z));
            boss.setYRot(yaw);
            boss.setYBodyRot(yaw);
            boss.setYHeadRot(yaw);
            boss.yBodyRotO = yaw;
            boss.teleportTo(at.x, at.y, at.z);
            boss.showBar(false);
            if (hitter != null) {
                // 剑祟面前一步：石台闪长岩那层到这里可能已经是外面低一格的平滑石，按脚下的面放
                Vec3 front = at.add(out.scale(FIN_FRONT));
                Vec3 feet = surface(level, front.add(0, 1.2, 0), 3);
                hitter.teleportTo(level, front.x, feet == null ? at.y : feet.y, front.z, Set.of(), yaw + 180, 0, true);
                hitter.setDeltaMovement(Vec3.ZERO);
                hitter.resetFallDistance();
            }
            // 开始被撬（伸手抓）、眼珠盯着打中的人：换场景时就摆上，旁观的人这时已经不按黑白特写摆，回到彩色时也已经到了客户端
            boss.play("pried");
            boss.lookAt(hitter);
        }
        clearStage(level, a);
    }

    /**
     * 回到彩色：剑祟开始被撬（伸手抓），眼珠盯着打中它的人。打中的人看侧面近景，别的人看远一点的机位，都硬切过来，
     * 黑白就此结束；镜头先停到最晚可能收尾的时候，之后撬一下、崩出、退远时再换。
     */
    private static void colour(ServerLevel level, Arena a, JianSui boss, @Nullable ServerPlayer hitter, Vec3 at) {
        level.playSound(null, at.x, at.y + 2, at.z, SoundEvents.ANVIL_USE, SoundSource.HOSTILE, 1.2f, .5f);
        Vec3 out = outward(a.site()), side = side(out, hitter);
        for (ServerPlayer p : finisherViewers(level, a))
            camera(a, p, (p == hitter ? closeShot(level, at, out, side, 0) : wideShot(level, at, out, side)).timing(0, FIN_LAST - FIN_COLOUR, FIN_RETURN));
    }

    /**
     * 撬一下（第 {@code index} 下，从 0 起；按的或者自己撬的）：一声金属摩擦、剑柄那里咔一下；剑祟抽一下、贯穿古剑顶出一截、
     * 面具裂一道、撬的人往下一压、顿帧和镜头一抖都由客户端按 {@link JianSuiCue#PRY_BEAT} 做。打中的人的镜头推近一点。
     */
    private static void pryBeat(ServerLevel level, Arena a, JianSui boss, @Nullable ServerPlayer hitter, int index) {
        Vec3 chest = boss.position().add(0, 2.4, 0), hilt = boss.position().add(0, 3.9, 0);
        level.playSound(null, chest.x, chest.y, chest.z, SoundEvents.GRINDSTONE_USE, SoundSource.HOSTILE, 1.6f, .6f + .08f * index);
        level.playSound(null, chest.x, chest.y, chest.z, SoundEvents.CHAIN_HIT, SoundSource.HOSTILE, 1.2f, .5f);
        level.playSound(null, hilt.x, hilt.y, hilt.z, SoundEvents.ANVIL_HIT, SoundSource.HOSTILE, .7f, 1.4f + .1f * index);
        JianSuiCue.near(level, chest, 128, new JianSuiCue(JianSuiCue.PRY_BEAT, level.getGameTime(), chest, hilt, index));
        if (hitter != null && a.finisherViewers.contains(hitter.getUUID())) {
            Vec3 out = outward(a.site());
            camera(a, hitter, closeShot(level, plunge(a.site()), out, side(out, hitter), index + 1).timing(3, FIN_LAST - FIN_COLOUR, FIN_RETURN));
        }
    }

    /**
     * 第三下之后：贯穿古剑从头顶崩飞出去，旋转着飞进雾里，再落下来插在插剑的地方（客户端按 {@link JianSuiCue#AXIAL_BURST} 画）；
     * 剩下的散架，双眼熄灭。打中的人被震退一步，是客户端的姿势，落回他一直站着的地方。打中的人的镜头切到仰拍。
     */
    private static void burst(ServerLevel level, Arena a, JianSui boss, @Nullable ServerPlayer hitter, Vec3 at, long t) {
        a.burstAt = t;
        boss.setHidden(boss.hidden() | JianSui.HIDE_AXIAL);
        boss.play("collapse_noaxial");
        boss.lookAt(null);
        Vec3 head = boss.position().add(0, 3.6, 0);
        Vec3 land = Vec3.atBottomCenterOf(relicPos(a.site()));
        JianSuiCue.near(level, head, 128, new JianSuiCue(JianSuiCue.AXIAL_BURST, level.getGameTime(), head, land, 1));
        level.playSound(null, head.x, head.y, head.z, SoundEvents.TRIDENT_RIPTIDE_3.value(), SoundSource.HOSTILE, 2f, .7f);
        level.playSound(null, head.x, head.y, head.z, SoundEvents.CHAIN_BREAK, SoundSource.HOSTILE, 1.6f, .6f);
        if (hitter != null) {
            level.playSound(null, hitter.getX(), hitter.getY() + 1, hitter.getZ(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 1f, .7f);
            if (a.finisherViewers.contains(hitter.getUUID())) {
                Vec3 out = outward(a.site());
                camera(a, hitter, lowShot(level, at, out, side(out, hitter)).timing(0, FIN_WIDE_AFTER + 20, FIN_RETURN));
            }
        }
    }

    /**
     * 打中剑祟的人在终结的过场里按了一下攻击键（客户端发来的 {@link JianSuiPry}）：撬剑的窗口里、三下以内才算，排着等轮到。
     * 别的人、别的时候按的都不算。
     */
    public static void pryPress(ServerPlayer p) {
        if (!(p.level() instanceof ServerLevel level)) return;
        Arenas data = level.getDataStorage().get(Arenas.TYPE);
        if (data == null) return;
        for (Arena a : data.arenas.values()) {
            if (a.state != State.FINISHER || !p.getUUID().equals(a.finisher)) continue;
            long t = level.getGameTime() - a.since;
            // 回到彩色以后按的就收下（客户端的钟可能比服务端快一点），排着，最早到撬剑的窗口开了才撬
            if (t >= FIN_COLOUR && t < PRY_CLOSE + PRY_GRACE && a.burstAt < 0 && a.pryBeats + a.pryQueued < PRY_AUTO.length) a.pryQueued++;
            return;
        }
    }

    /** 打中剑祟的人（在线、活着、在这个世界里），否则 null。 */
    private static @Nullable ServerPlayer hitter(ServerLevel level, Arena a) {
        ServerPlayer p = a.finisher == null ? null : level.getServer().getPlayerList().getPlayer(a.finisher);
        return p != null && p.isAlive() && p.level() == level ? p : null;
    }

    /** 看这一次终结的人（发过黑白特写的），还在线、活着、在这个世界里的。 */
    private static List<ServerPlayer> finisherViewers(ServerLevel level, Arena a) {
        List<ServerPlayer> out = new ArrayList<>();
        for (UUID id : a.finisherViewers) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
            if (p != null && p.isAlive() && p.level() == level && inFight(a.site(), p.position())) out.add(p);
        }
        return out;
    }

    /** 给这个人换一个过场镜头，记下来（过场被提前截断时按它往回走）。 */
    private static void camera(Arena a, ServerPlayer p, CameraShot shot) {
        CameraShots.play(p, shot);
        a.finisherShots.put(p.getUUID(), shot);
    }

    /** 从竖轴指向插剑那一点的方向（水平、单位长）：终结时剑祟朝着它，送回上面的人往这边挪开。 */
    static Vec3 outward(TaixuanSite site) {
        Vec3 plunge = plunge(site);
        Vec3 out = plunge.subtract(site.axis(plunge.y)).multiply(1, 0, 1);
        return out.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : out.normalize();
    }

    /**
     * 彩色的机位都放在打中的人拿东西那只手的一侧：和黑白「飞身刺」同一侧（左撇子换到另一边），切回彩色不越轴，
     * 手里的东西和刺进去的地方朝着镜头。打中的人面朝剑祟，他的右手边是剑祟（朝 {@code out}）的左手边。人不在了按右手算。
     */
    private static Vec3 side(Vec3 out, @Nullable ServerPlayer hitter) {
        Vec3 bossRight = out.cross(new Vec3(0, 1, 0));
        return hitter != null && hitter.getMainArm() == HumanoidArm.LEFT ? bossRight : bossRight.scale(-1);
    }

    /** 打中的人看的侧面近景：{@code side} 那一侧、胸口高度，看着它胸口和打中的人的手之间；撬了 {@code beats} 下就推近几成。 */
    private static CameraShot closeShot(ServerLevel level, Vec3 at, Vec3 out, Vec3 side, int beats) {
        Vec3 look = at.add(out.scale(.5)).add(0, 2.5, 0);
        Vec3 from = at.add(out.scale(1.9)).add(side.scale(3.4)).add(0, 2.6, 0);
        from = look.add(from.subtract(look).scale(1 - .12 * beats));
        return CameraShot.of(CameraPoint.world(CameraShots.reach(level, look, from)), CameraPoint.world(look)).letterbox().cinema();
    }

    /** 远一点的机位（同一侧）：别的人从回到彩色起看这个，崩出后所有人退到这里，看得到散架、遗剑落回插剑的地方。 */
    private static CameraShot wideShot(ServerLevel level, Vec3 at, Vec3 out, Vec3 side) {
        Vec3 look = at.add(out.scale(.6)).add(0, 2, 0);
        Vec3 from = at.add(out.scale(5.5)).add(side.scale(5)).add(0, 3.4, 0);
        return CameraShot.of(CameraPoint.world(CameraShots.reach(level, look, from)), CameraPoint.world(look)).letterbox().cinema();
    }

    /** 崩出那一下的仰拍：低处、剑祟前面偏 {@code side} 一点，往上看着头顶（贯穿古剑从那里飞出去）。 */
    private static CameraShot lowShot(ServerLevel level, Vec3 at, Vec3 out, Vec3 side) {
        Vec3 look = at.add(0, 4.8, 0);
        Vec3 from = at.add(out.scale(3)).add(side.scale(1.5)).add(0, .6, 0);
        return CameraShot.of(CameraPoint.world(CameraShots.reach(level, look, from)), CameraPoint.world(look)).letterbox().cinema();
    }

    /**
     * 终结结束（走完、读档收尾、命令跳过或重置都走这里）：打中的人恢复战斗骨架（下线了的，下线时就清掉了）。
     * 过场被提前截断时，还在看过场的人镜头马上往回走；走完时镜头按计划正好在这一刻往回走，不再发。
     */
    private static void releaseFinisher(ServerLevel level, Arena a) {
        var list = level.getServer().getPlayerList();
        ServerPlayer hitter = a.finisher == null ? null : list.getPlayer(a.finisher);
        if (hitter != null) LongswordCombat.resume(hitter);
        boolean cut = a.state == State.FINISHER && (a.burstAt < 0 || level.getGameTime() - a.since < a.burstAt + GONE_AFTER);
        if (cut) {
            for (var e : a.finisherShots.entrySet()) {
                ServerPlayer p = list.getPlayer(e.getKey());
                if (p != null && p.level() == level) CameraShots.play(p, e.getValue().timing(0, 0, FIN_RETURN));
            }
            JianSui boss = boss(level, a);
            if (boss != null) {
                // 剑祟留着（命令跳回前面的状态）：客户端不再摆撬剑、收掉每一下的反应；崩出过的贯穿古剑放回去
                Vec3 chest = boss.position().add(0, 2.4, 0);
                JianSuiCue.near(level, chest, 128, new JianSuiCue(JianSuiCue.FINISHER_END, level.getGameTime(), chest, chest, 0));
                boss.setHidden(boss.hidden() & ~JianSui.HIDE_AXIAL);
            }
        }
        // 过场里的保护到收尾为止（不留到原定的最晚时刻）
        long until = level.getGameTime() + 20;
        for (UUID id : a.finisherViewers) PROTECTED.computeIfPresent(id, (k, v) -> Math.min(v, until));
        a.finisherViewers.clear();
        a.finisherShots.clear();
        a.pryQueued = 0;
    }

    /** 测试用：这处地标的终结已经撬了几下（不在终结里时是 0）。 */
    public static int pryBeats(ServerLevel level, TaixuanSite site) {
        Arena a = arena(level, site);
        return a == null || a.state != State.FINISHER ? 0 : a.pryBeats;
    }

    /** 收尾：空洞里还有人的送回上面，遗剑插好、留疤，剑祟和场景删掉，记成已打败（读档时处在终结、命令跳过来也走这里）。 */
    static void finishNow(ServerLevel level, Arena a) {
        TaixuanSite site = a.site();
        releaseFinisher(level, a);
        for (ServerPlayer p : players(level, a, true)) sendBack(level, a, p);
        clearStage(level, a);
        placeRelic(level, a);
        JianSui boss = boss(level, a);
        if (boss != null) boss.discard();
        a.boss = null;
        a.state = State.DEFEATED;
        a.since = level.getGameTime();
        a.placed.clear();
        a.placedAt.clear();
        hideCracks(level, a, plunge(site));
        arenas(level).setDirty();
    }

    /**
     * 遗剑插在插剑的地方；落剑的地方一圈地砖换成裂纹瓷砖，留个疤（第 12 节），原来的方块记进记录（重置时还原）。
     * 已经插着的不重放；那几格没加载的跳过（不在每刻的处理里同步加载区块）。
     */
    static void placeRelic(ServerLevel level, Arena a) {
        BlockPos pos = relicPos(a.site());
        if (!level.isLoaded(pos) || level.getBlockState(pos).is(JianSuiBlocks.AXIAL_SWORD.get())) return;
        // 疤那一圈要整片都加载了才一起放（不然插好剑以后，没加载的那几格永远补不上；已打败时每 5 秒再试）
        if (!level.hasChunksAt(pos.below().offset(-2, 0, -2), pos.below().offset(2, 0, 2))) return;
        level.setBlock(pos, JianSuiBlocks.AXIAL_SWORD.get().defaultBlockState(), Block.UPDATE_ALL);
        if (level.getBlockEntity(pos) instanceof AxialSwordBlockEntity sword) sword.setPlaced(level.getGameTime());
        var rng = net.minecraft.util.RandomSource.create(pos.asLong());
        BlockPos floor = pos.below();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > 2.3 || d > .5 && rng.nextFloat() > Mth.clamp(1.4f - (float) d * .4f, 0, 1)) continue;
                BlockPos q = floor.offset(dx, 0, dz);
                if (!level.isLoaded(q)) continue;
                BlockState state = level.getBlockState(q);
                if (state.is(Blocks.CRACKED_DEEPSLATE_TILES) || !state.isCollisionShapeFullBlock(level, q)
                        || !level.getBlockState(q.above()).getCollisionShape(level, q.above()).isEmpty() && !q.above().equals(pos)) continue;
                a.scar.putIfAbsent(q.asLong(), state);
                level.setBlock(q, Blocks.CRACKED_DEEPSLATE_TILES.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        arenas(level).setDirty();
    }

    /** 送回进空洞前记下的位置（没记的送到雾底石台外侧）；记下的位置落在裂缝上的，往外挪开，免得一回去又被吸下去。 */
    static void sendBack(ServerLevel level, Arena a, ServerPlayer p) {
        TaixuanSite site = a.site();
        Vec3 plunge = plunge(site);
        // 往外挪（跟着地标的旋转走；石台正中是玄铁心，不能往里送）
        Vec3 out = outward(site);
        double[] r = a.returns.get(p.getUUID());
        Vec3 to = r == null ? plunge.add(out.scale(4)) : new Vec3(r[0], r[1], r[2]);
        if (Math.hypot(to.x - plunge.x, to.z - plunge.z) < 3) to = plunge.add(out.scale(4));
        p.teleportTo(level, to.x, to.y, to.z, Set.of(), r == null ? p.getYRot() : (float) r[3], 0, true);
        p.setDeltaMovement(Vec3.ZERO);
        p.resetFallDistance();
    }

    // ---------------------------------------------------------------- 上线、保护、命令

    /** 在空洞里上线：第三阶段还在进行就送回起点碎块（场景、雾、风声跟着场景实体回来），否则送回记下的位置（第 14 节）。 */
    @SubscribeEvent
    static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !(p.level() instanceof ServerLevel level)) return;
        Arenas data = level.getDataStorage().get(Arenas.TYPE);
        if (data == null) return;
        long now = level.getGameTime();
        for (Arena a : data.arenas.values()) {
            TaixuanSite site = a.site();
            boolean dropped = a.state == State.RIFT && now - a.since >= TELEPORT_AT;
            // 读档时停在裂地前 6 秒（人还在上面）、接成了第三阶段：上线时在场地里的人也一起送下去
            boolean owedDrop = a.pendingDrop && a.state == State.FALL && inArena(site, p.position());
            if (!inCavity(site, p.position()) && !owedDrop) continue;
            if (a.state == State.FALL || dropped) {
                sendDown(level, a, p, 0);
                if (a.state == State.FALL) showExit(level, a, p, exitCrack(site), true);
                a.returned.put(p.getUUID(), now);
            } else sendBack(level, a, p);
            return;
        }
    }

    static void protect(ServerLevel level, ServerPlayer p, int ticks) {
        PROTECTED.merge(p.getUUID(), level.getGameTime() + ticks, Math::max);
    }

    /** 过场里的玩家不受伤（世界不暂停，锁着操作挨打不公平）。 */
    @SubscribeEvent
    static void protectEvent(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        Long until = PROTECTED.get(p.getUUID());
        if (until != null && p.level().getGameTime() <= until) event.setCanceled(true);
    }

    /** 命令：重置脚下这处地标的剑祟（同白梅：正常一处只能打一次，用它重来）。 */
    public static boolean reset(ServerLevel level, BlockPos near) {
        TaixuanSite site = TaixuanSite.at(level, near);
        if (site == null) return false;
        reset(level, site);
        return true;
    }

    /**
     * 重置：删掉记录、剑祟、场景、遗剑和剑格，空洞里的人送回去，裂缝收起，排好的定时取消，留的疤还原。
     * 下次有人走近时重新放残骸。
     */
    public static void reset(ServerLevel level, TaixuanSite site) {
        Arenas data = arenas(level);
        Arena a = data.arenas.get(site.start().pack());
        Vec3 at = plunge(site);
        if (a != null) {
            // 终结演到一半被重置：打中的人恢复战斗骨架，镜头回来
            if (a.state == State.FINISHER) releaseFinisher(level, a);
            JianSui boss = boss(level, a);
            if (boss != null) boss.discard();
            for (ServerPlayer p : players(level, a, true)) sendBack(level, a, p);
            // 命令不在每刻的处理里：剑格、疤所在的区块没加载的，先加载上再拆、再还原（记录马上就删了，不能留着以后再做）
            for (long g : a.guards.keySet()) level.getChunkAt(BlockPos.of(g));
            for (long q : a.scar.keySet()) level.getChunkAt(BlockPos.of(q));
            clearStage(level, a);
            hideCracks(level, a, at);
            long key = a.key;
            TASKS.removeIf(t -> t.level == level && t.arena == key);
            for (var e : a.scar.entrySet()) {
                BlockPos q = BlockPos.of(e.getKey());
                if (level.isLoaded(q) && level.getBlockState(q).is(Blocks.CRACKED_DEEPSLATE_TILES)) level.setBlock(q, e.getValue(), Block.UPDATE_ALL);
            }
            data.arenas.remove(a.key);
        }
        for (JianSui b : level.getEntitiesOfClass(JianSui.class, new AABB(at, at).inflate(64))) b.discard();
        BlockPos relic = relicPos(site);
        if (level.getBlockState(relic).is(JianSuiBlocks.AXIAL_SWORD.get())) level.setBlock(relic, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        data.setDirty();
    }

    /** 命令、测试用：这处地标的剑祟此刻在哪个状态（没有记录时 null）。 */
    public static @Nullable State state(ServerLevel level, BlockPos near) {
        TaixuanSite site = TaixuanSite.at(level, near);
        return site == null ? null : state(level, site);
    }

    public static @Nullable State state(ServerLevel level, TaixuanSite site) {
        Arena a = arena(level, site);
        return a == null ? null : a.state;
    }

    /** 测试用：这处地标的剑祟（没有、没加载时 null）。 */
    public static @Nullable JianSui boss(ServerLevel level, TaixuanSite site) {
        Arena a = arena(level, site);
        return a == null ? null : boss(level, a);
    }

    /** 测试用：这个人到过的最高落脚台。 */
    public static int reached(ServerLevel level, TaixuanSite site, Player p) {
        Arena a = arena(level, site);
        return a == null ? 0 : a.reached.getOrDefault(p.getUUID(), 0);
    }

    /** 命令：直接跳到某个状态（试过场用）。 */
    public static boolean skip(ServerLevel level, BlockPos near, State state) {
        TaixuanSite site = TaixuanSite.at(level, near);
        if (site == null) return false;
        skip(level, site, state);
        return true;
    }

    public static void skip(ServerLevel level, TaixuanSite site, State state) {
        if (state == State.DORMANT) {
            // 回到还没开打：等于重置后重新放下残骸
            reset(level, site);
            open(level, site);
            return;
        }
        open(level, site);
        Arena a = arena(level, site);
        // 从终结跳到前面的状态：打中的人恢复战斗骨架，镜头回来（跳到打败由 finishNow 收）
        if (a.state == State.FINISHER && state != State.FINISHER && state != State.DEFEATED) releaseFinisher(level, a);
        JianSui boss = boss(level, a);
        if (boss == null) {
            // 命令不在每刻的处理里：插剑那一格的区块没加载时先加载上（不然放不下残骸，跳过去的状态里没有剑祟）
            level.getChunkAt(BlockPos.containing(plunge(site)));
            spawnRemains(level, a);
            boss = boss(level, a);
        }
        boolean down = a.state == State.FALL || a.state == State.FINISHER || a.state == State.RIFT && level.getGameTime() - a.since >= TELEPORT_AT;
        if (down && state != State.FALL && state != State.FINISHER && state != State.DEFEATED) {
            // 从空洞里那一段跳出去：空洞里的人送回上面，场景收掉（不然人困在封住的空洞里），剑祟回到上面插剑的地方
            for (ServerPlayer p : players(level, a, true)) sendBack(level, a, p);
            clearStage(level, a);
            hideCracks(level, a, plunge(site));
            if (boss != null && inCavity(site, boss.position())) {
                Vec3 at = plunge(site);
                boss.teleportTo(at.x, at.y, at.z);
                boss.setHidden(0);
            }
        }
        if (state == State.ENTRANCE) {
            // 从头演登场（运镜、标题都有）
            if (boss != null) {
                a.state = State.DORMANT;
                boss.setPhase(0);
                wake(level, boss);
            }
            return;
        }
        if (boss != null && boss.phase() == 0) {
            // 直接跳到后面的状态：不演登场，只把血按人数加好
            scaleHealth(level, a, boss);
            boss.setPhase(1);
        }
        switch (state) {
            case FALL -> {
                if (a.state != State.FALL) {
                    // 和裂地走完时一样：护到第三阶段开始后 1 秒
                    dropIn(level, a, boss, 20);
                    startFall(level, a, boss);
                }
            }
            case RIFT_WAIT -> waitInRift(level, a);
            case DEFEATED, FINISHER -> finishNow(level, a);
            default -> enter(level, a, state);
        }
    }
}
