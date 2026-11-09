package dev.logos.dimension.shiraume;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.camera.CameraPoint;
import dev.logos.dimension.camera.CameraShot;
import dev.logos.dimension.camera.CameraShots;
import dev.logos.dimension.dissolve.DissolveShape;
import dev.logos.dimension.dissolve.Dissolving;
import dev.logos.dimension.fox.FoxSanctuaryStructure;
import dev.logos.dimension.fox.SanctuaryVaultPiece;
import dev.logos.dimension.item.RyukaItem;
import dev.logos.dimension.screenfx.ScreenEffects;
import dev.logos.dimension.screenfx.ScreenFlash;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * 一处封印之间里的白梅战（docs/SHIRAUME_BOSS.md 第 2、5、7 节）：封印杭断开后的觉醒、开场过场、变身、击败与奖励，
 * 以及整场都在的红光、玩家放的方块、没人时休息。按杀生石脚下的位置（{@code BlockPos.asLong}）存在维度存档里。
 *
 * <p>命令召出来的白梅（不在封印之间里）也有一份记录，以召出的位置为中心、半径 {@link #FREE_RADIUS} 当场地。
 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class ShiraumeFight {
    /** SEALED：还没解封，只记玩家放的方块；其余是觉醒、第一阶段、变身、第二阶段、击败。 */
    public enum State { SEALED, AWAKENING, PHASE_ONE, TRANSFORMING, PHASE_TWO, DEFEATED }

    // ---- 觉醒（刻，从最后一道封印断开算起）
    static final int SHAKE_1 = 70, SHAKE_2 = 130, BLAST = 170;
    /** 过场的几个机位（从爆炸算起）。 */
    static final int SHOT_MEDIUM = 40, SHOT_PUSH = 65, SHOT_LOW = 90, SHOT_TILT = 120, SHOT_FACE = 150, SHOT_PULL = 180, TITLE_AT = 190;
    /** 起身开始、Boss 战开始（从爆炸算起）。 */
    static final int RISE_AT = 90, FIGHT_AT = 260;
    // ---- 变身（刻，从第一条血清空算起）
    static final int SLAM_AT = 30, SWAP_AT = 60, REVEAL_AT = 100, ROAR_AT = 124, FILL_AT = 150, PHASE_TWO_AT = 160;
    // ---- 击败
    static final int MOOD_OFF_AT = 40, DISSOLVE_AT = 50, REWARD_AT = 130;
    /** 场地里没有活着的玩家多久就休息（刻）。 */
    static final int REST_AFTER = 600;
    /** 命令召出来的白梅：以召出的位置为心的场地半径。 */
    static final double FREE_RADIUS = 40;
    /** 离杀生石太近的玩家（封印杭那一圈以内）挪到这个半径上。 */
    static final double SEAL_RING = 15, SAFE_RING = 18.5;
    /** 红光边缘的暗红（觉醒时杀生石炸开也用它）。她的狐火是淡粉的（{@link FoxfireFlame#SHIRAUME}）。 */
    public static final int RED_FOXFIRE = 0xE8303A;

    // ---------------------------------------------------------------- 存档

    /** 一处场地。 */
    static final class Arena {
        final long key;
        State state;
        long since;
        @Nullable UUID boss;
        final Set<UUID> players = new HashSet<>();
        final Set<Long> placed = new HashSet<>();
        /** 爆炸后悬在她周围的几团狐火（看不见的光源方块，红光给它们加红晕），击败后拿走。 */
        final Set<Long> lights = new HashSet<>();
        /** 没有封印之间时（命令召出来的）以这里为心。 */
        @Nullable Vec3 free;
        /** 运行时：多久没人了、上一次发红光的刻、过场里要保护的玩家。 */
        int empty;
        long moodSent;
        final Set<UUID> cutscene = new HashSet<>();

        Arena(long key, State state, long since) {
            this.key = key;
            this.state = state;
            this.since = since;
        }
    }

    record Saved(String state, long since, String boss, List<String> players, List<Long> placed, Optional<List<Double>> free, List<Long> lights) {
        static final Codec<Saved> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("state").forGetter(Saved::state),
                Codec.LONG.fieldOf("since").forGetter(Saved::since),
                Codec.STRING.optionalFieldOf("boss", "").forGetter(Saved::boss),
                Codec.STRING.listOf().optionalFieldOf("players", List.of()).forGetter(Saved::players),
                Codec.LONG.listOf().optionalFieldOf("placed", List.of()).forGetter(Saved::placed),
                Codec.DOUBLE.listOf().optionalFieldOf("free").forGetter(Saved::free),
                Codec.LONG.listOf().optionalFieldOf("lights", List.of()).forGetter(Saved::lights)
        ).apply(i, Saved::new));
    }

    public static final class Arenas extends SavedData {
        static final Codec<Arenas> CODEC = Codec.unboundedMap(Codec.STRING, Saved.CODEC).xmap(Arenas::new, Arenas::save);
        static final SavedDataType<Arenas> TYPE = new SavedDataType<>(LogosDimension.id("shiraume_arenas"), Arenas::new, CODEC);
        final Map<Long, Arena> arenas = new HashMap<>();

        public Arenas() {}

        private Arenas(Map<String, Saved> saved) {
            for (var e : saved.entrySet()) {
                long key = Long.parseLong(e.getKey());
                Saved s = e.getValue();
                Arena a = new Arena(key, State.valueOf(s.state), s.since);
                a.boss = s.boss.isEmpty() ? null : UUID.fromString(s.boss);
                for (String p : s.players) a.players.add(UUID.fromString(p));
                a.placed.addAll(s.placed);
                a.lights.addAll(s.lights);
                s.free.ifPresent(f -> a.free = new Vec3(f.get(0), f.get(1), f.get(2)));
                arenas.put(key, a);
            }
        }

        private Map<String, Saved> save() {
            Map<String, Saved> out = new HashMap<>();
            for (Arena a : arenas.values()) {
                out.put(Long.toString(a.key), new Saved(a.state.name(), a.since, a.boss == null ? "" : a.boss.toString(),
                        a.players.stream().map(UUID::toString).toList(), List.copyOf(a.placed),
                        a.free == null ? Optional.empty() : Optional.of(List.of(a.free.x, a.free.y, a.free.z)), List.copyOf(a.lights)));
            }
            return out;
        }

        @Nullable Arena get(long key) {
            return arenas.get(key);
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

    private static final Map<Long, SanctuaryVaultPiece> VAULTS = new HashMap<>();
    private record Task(ServerLevel level, long at, Runnable run) {}
    private static final List<Task> TASKS = new ArrayList<>();
    /** 过场里不受伤的玩家，到哪一刻为止。 */
    private static final Map<UUID, Long> PROTECTED = new HashMap<>();

    private ShiraumeFight() {}

    /** 在 {@code at} 那一刻（游戏刻）做一件事。 */
    static void later(ServerLevel level, long at, Runnable run) {
        TASKS.add(new Task(level, at, run));
    }

    // ---------------------------------------------------------------- 场地几何

    static @Nullable SanctuaryVaultPiece vault(ServerLevel level, long key) {
        SanctuaryVaultPiece v = VAULTS.get(key);
        if (v == null) {
            v = FoxSanctuaryStructure.vaultAt(level, BlockPos.of(key));
            if (v != null) VAULTS.put(key, v);
        }
        return v;
    }

    /** 场地中心（地面上一格，人站的高度）。 */
    static Vec3 center(ServerLevel level, Arena a) {
        if (a.free != null) return a.free;
        return Vec3.atBottomCenterOf(BlockPos.of(a.key).below(2));
    }

    static AABB box(ServerLevel level, Arena a) {
        SanctuaryVaultPiece v = a.free == null ? vault(level, a.key) : null;
        if (v != null) return v.arenaBox();
        Vec3 c = center(level, a);
        return new AABB(c.x - FREE_RADIUS, c.y - 4, c.z - FREE_RADIUS, c.x + FREE_RADIUS, c.y + 30, c.z + FREE_RADIUS);
    }

    public static boolean inArena(ServerLevel level, long key, BlockPos pos) {
        Arena a = arenas(level).get(key);
        if (a == null) return false;
        SanctuaryVaultPiece v = a.free == null ? vault(level, key) : null;
        if (v != null) return v.inArena(pos);
        return center(level, a).distanceToSqr(Vec3.atCenterOf(pos)) < FREE_RADIUS * FREE_RADIUS;
    }

    static List<ServerPlayer> playersIn(ServerLevel level, Arena a) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : level.players()) if (p.isAlive() && !p.isSpectator() && inArena(level, a.key, p.blockPosition())) out.add(p);
        return out;
    }

    /** 在场的玩家：封印之间里或 64 格内（断封印的色散、红光都给他们）。 */
    static List<ServerPlayer> present(ServerLevel level, Arena a) {
        Vec3 c = center(level, a);
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : level.players()) if (inArena(level, a.key, p.blockPosition()) || p.position().distanceToSqr(c) < 64 * 64) out.add(p);
        return out;
    }

    // ---------------------------------------------------------------- 封印杭

    /** 断了一根封印杭：在场的玩家色散一下；最后一根断掉时开始觉醒。由 {@code SealStakeBlock} 调用。 */
    public static void sealBroken(ServerLevel level, SanctuaryVaultPiece vault, int left) {
        long key = vault.sealStone().asLong();
        VAULTS.put(key, vault);
        Arena existing = arenas(level).get(key);
        if (existing != null && existing.state != State.SEALED) return;
        Arena probe = existing != null ? existing : new Arena(key, State.SEALED, level.getGameTime());
        ShiraumeCue.send(present(level, probe), ShiraumeCue.aberration(left == 0 ? 1f : .55f));
        if (left == 0) awaken(level, vault);
    }

    /** 最后一道封印断开：红光、两次震动、爆炸、过场，然后开打（第 2 节的时间表）。 */
    public static boolean awaken(ServerLevel level, SanctuaryVaultPiece vault) {
        long key = vault.sealStone().asLong();
        Arenas data = arenas(level);
        Arena a = data.get(key);
        if (a != null && a.state != State.SEALED) return false;
        VAULTS.put(key, vault);
        if (a == null) a = new Arena(key, State.AWAKENING, level.getGameTime());
        a.state = State.AWAKENING;
        a.since = level.getGameTime();
        data.put(a);
        sendMood(level, a, true);
        return true;
    }

    // ---------------------------------------------------------------- 每刻

    @SubscribeEvent
    static void tick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        for (Iterator<Task> it = TASKS.iterator(); it.hasNext(); ) {
            Task t = it.next();
            if (t.level != level || t.at > now) continue;
            it.remove();
            t.run.run();
        }
        PROTECTED.values().removeIf(until -> until < now);
        Arenas data = level.getDataStorage().get(Arenas.TYPE);
        if (data == null || data.arenas.isEmpty()) return;
        for (Arena a : List.copyOf(data.arenas.values())) {
            if (a.state == State.SEALED) continue;
            switch (a.state) {
                case AWAKENING -> awakening(level, a, now - a.since);
                case PHASE_ONE, PHASE_TWO -> fighting(level, a, now);
                case TRANSFORMING -> transforming(level, a, now - a.since);
                case DEFEATED -> defeated(level, a, now - a.since);
                default -> { }
            }
            if (a.free == null && a.state != State.DEFEATED && now - a.moodSent >= 100) sendMood(level, a, true);
        }
    }

    private static void sendMood(ServerLevel level, Arena a, boolean on) {
        a.moodSent = level.getGameTime();
        ShiraumeCue cue = ShiraumeCue.mood(on, box(level, a), center(level, a).add(0, 2, 0), a.since);
        Vec3 c = center(level, a);
        for (ServerPlayer p : level.players()) if (p.position().distanceToSqr(c) < 112 * 112) ShiraumeCue.send(p, cue);
    }

    // ---------------------------------------------------------------- 觉醒

    private static void awakening(ServerLevel level, Arena a, long age) {
        Vec3 c = center(level, a);
        Vec3 stone = c.add(0, 4, 0);
        if (age == SHAKE_1 || age == SHAKE_2) {
            boolean second = age == SHAKE_2;
            ShiraumeCue.send(present(level, a), ShiraumeCue.shake(stone, second ? .85f : .55f));
            level.playSound(null, stone.x, stone.y, stone.z, SoundEvents.WARDEN_EMERGE, SoundSource.HOSTILE, second ? 3f : 2f, second ? .55f : .5f);
            ceilingDust(level, a, second ? 90 : 50);
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, stone.x, stone.y, stone.z, second ? 80 : 40, 1.2, 2.5, 1.2, .04);
        }
        if (age > SHAKE_2 && age < BLAST && age % 4 == 0) {
            level.sendParticles(new net.minecraft.core.particles.DustParticleOptions(RED_FOXFIRE, 1.6f), stone.x, stone.y, stone.z, 12, 1.4, 2.6, 1.4, 0);
        }
        if (age == BLAST) blast(level, a);
        if (age < BLAST) return;
        Mob boss = boss(level, a);
        long since = age - BLAST;
        if (boss != null) {
            if (since == RISE_AT && boss instanceof ShiraumeBoss b) b.play("rise");
            if (since >= SHOT_FACE) {
                ServerPlayer focus = nearest(level, a, boss.position());
                if (focus != null) {
                    boss.getLookControl().setLookAt(focus, 20, 20);
                    if (boss instanceof Shiraume s) s.face(focus, 4);
                }
            }
        }
        if (since == SHOT_MEDIUM || since == SHOT_PUSH || since == SHOT_LOW || since == SHOT_TILT || since == SHOT_FACE || since == SHOT_PULL) {
            for (ServerPlayer p : level.players()) if (a.cutscene.contains(p.getUUID())) cutscene(level, a, p, boss, (int) since);
        }
        if (since == TITLE_AT) {
            for (ServerPlayer p : level.players()) if (a.cutscene.contains(p.getUUID())) ShiraumeCue.send(p, ShiraumeCue.title(level.getGameTime()));
        }
        if (since >= FIGHT_AT) {
            a.cutscene.clear();
            if (boss instanceof ShiraumeBoss b) {
                b.setScripted(false);
                b.showBar(true, 1);
            }
            enter(level, a, State.PHASE_ONE);
        }
    }

    /** 杀生石炸开：黑白闪、爆炸声与碎石，闪光那一刻拆掉杀生石与底座、挪开太近的玩家、白梅跪坐在原处，硬切进过场。 */
    private static void blast(ServerLevel level, Arena a) {
        Vec3 c = center(level, a);
        Vec3 stone = c.add(0, 4, 0);
        List<ServerPlayer> watching = present(level, a);
        for (ServerPlayer p : watching) ScreenEffects.flash(p, ScreenFlash.at(CameraPoint.world(stone)));
        level.playSound(null, stone.x, stone.y, stone.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 4f, .6f);
        level.playSound(null, stone.x, stone.y, stone.z, SoundEvents.FOX_SCREECH, SoundSource.HOSTILE, 3f, .55f);
        level.playSound(null, stone.x, stone.y, stone.z, SoundEvents.WITHER_BREAK_BLOCK, SoundSource.HOSTILE, 2f, .6f);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, stone.x, stone.y, stone.z, 2, 1, 1, 1, 0);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, stone.x, stone.y, stone.z, 120, 4, 3, 4, .08);
        level.sendParticles(ParticleTypes.CHERRY_LEAVES, stone.x, stone.y + 3, stone.z, 160, 7, 4, 7, .02);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.POLISHED_BLACKSTONE.defaultBlockState()), stone.x, stone.y, stone.z, 200, 3, 3, 3, .3);
        SanctuaryVaultPiece vault = a.free == null ? vault(level, a.key) : null;
        if (vault != null) {
            vault.clear(level, SanctuaryVaultPiece.Removable.STONE);
            vault.clear(level, SanctuaryVaultPiece.Removable.ALTAR);
            vault.clear(level, SanctuaryVaultPiece.Removable.PEDESTALS);
        }
        purge(level, a, c, 26, null);
        wisps(level, a, c);
        // 离杀生石太近的玩家：挪到底座外面的圆台上，同一方位，脸朝圆心
        for (ServerPlayer p : playersIn(level, a)) {
            Vec3 off = p.position().subtract(c);
            double flat = Math.sqrt(off.x * off.x + off.z * off.z);
            if (flat > SEAL_RING || Math.abs(off.y) > 6) continue;
            double angle = flat < .5 ? p.getRandom().nextDouble() * Math.PI * 2 : Math.atan2(off.z, off.x);
            Vec3 to = safeSpot(level, c, angle, SAFE_RING);
            float yaw = (float) (Mth.atan2(c.z - to.z, c.x - to.x) * Mth.RAD_TO_DEG) - 90;
            p.teleportTo(level, to.x, to.y, to.z, Set.of(), yaw, 0, false);
        }
        // 白梅出现在杀生石原处，日式跪坐，朝着最近的玩家
        Shiraume boss = ShiraumeEntities.SHIRAUME.get().create(level, EntitySpawnReason.EVENT);
        if (boss != null) {
            ServerPlayer focus = nearest(level, a, c);
            float yaw = focus == null ? 180 : (float) (Mth.atan2(focus.getZ() - c.z, focus.getX() - c.x) * Mth.RAD_TO_DEG) - 90;
            boss.snapTo(c.x, c.y, c.z, yaw, 0);
            boss.setYHeadRot(yaw);
            boss.setYBodyRot(yaw);
            scale(boss, level, a);
            boss.setArena(a.key);
            boss.setScripted(true);
            level.addFreshEntity(boss);
            boss.play("seiza");
            boss.showBar(false, 1);
            a.boss = boss.getUUID();
        }
        long until = level.getGameTime() + FIGHT_AT + 20;
        for (ServerPlayer p : watching) {
            a.cutscene.add(p.getUUID());
            PROTECTED.put(p.getUUID(), until);
            cutscene(level, a, p, boss, 0);
        }
        arenas(level).setDirty();
    }

    /** 杀生石炸开后悬在半空的几团狐火：看不见的光源方块（中央的灯都拆掉了，不然她只剩剪影），红光给它们加红晕，战斗中冒火苗。 */
    private static void wisps(ServerLevel level, Arena a, Vec3 c) {
        var light = Blocks.LIGHT.defaultBlockState().setValue(net.minecraft.world.level.block.LightBlock.LEVEL, 12);
        for (int i = 0; i < 6; i++) {
            double angle = Math.PI * 2 * i / 6 + .3;
            BlockPos pos = BlockPos.containing(c.x + Math.cos(angle) * 5.5, c.y + 2.6 + (i % 2) * .8, c.z + Math.sin(angle) * 5.5);
            if (!level.getBlockState(pos).isAir()) continue;
            level.setBlock(pos, light, 3);
            a.lights.add(pos.asLong());
        }
    }

    /** 击败后狐火熄掉。 */
    private static void douse(ServerLevel level, Arena a) {
        for (long l : a.lights) {
            BlockPos pos = BlockPos.of(l);
            if (level.getBlockState(pos).is(Blocks.LIGHT)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            level.sendParticles(ParticleTypes.SMOKE, pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, 6, .15, .15, .15, .01);
        }
        a.lights.clear();
        arenas(level).setDirty();
    }

    /** 开场过场的机位（从爆炸算起的刻 {@code at}），每个玩家各一份；新的一段从当时的画面接过去。 */
    private static void cutscene(ServerLevel level, Arena a, ServerPlayer p, @Nullable Mob boss, int at) {
        Vec3 b = boss != null ? boss.position() : center(level, a);
        float yaw = (boss != null ? boss.getYRot() : 0) * Mth.DEG_TO_RAD;
        Vec3 f = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw)), r = new Vec3(-f.z, 0, f.x), up = new Vec3(0, 1, 0);
        CameraShot shot = switch (at) {
            case 0 -> CameraShot.of(world(level, b, b.add(f.scale(16)).add(0, 10, 0)), CameraPoint.world(b.add(0, 2, 0))).timing(0, SHOT_MEDIUM + 10, 20);
            case SHOT_MEDIUM -> CameraShot.of(world(level, b, b.add(f.scale(7.5)).add(r.scale(1.2)).add(0, 2.2, 0)), CameraPoint.world(b.add(0, 1.3, 0)))
                    .timing(20, 40, 20);
            case SHOT_PUSH -> CameraShot.of(world(level, b, b.add(f.scale(5.4)).add(r.scale(.9)).add(0, 1.9, 0)), CameraPoint.world(b.add(0, 1.2, 0)))
                    .timing(25, 40, 20);
            case SHOT_LOW -> CameraShot.of(world(level, b, b.add(f.scale(4.3)).add(r.scale(1.8)).add(0, .5, 0)), CameraPoint.world(b.add(0, 2.2, 0)))
                    .timing(18, 40, 20);
            case SHOT_TILT -> CameraShot.of(world(level, b, b.add(f.scale(4.6)).add(r.scale(1.6)).add(0, .6, 0)), CameraPoint.world(b.add(0, 3.0, 0)))
                    .timing(30, 40, 20);
            case SHOT_FACE -> CameraShot.of(world(level, b, b.add(f.scale(2.5)).add(0, 2.7, 0)), CameraPoint.world(b.add(0, 2.62, 0)))
                    .timing(22, 40, 20);
            default -> {
                // 镜头从她面前拉回到玩家的位置，停在玩家肩后看着她；标题在这时出现，然后回到玩家
                Vec3 toBoss = b.subtract(p.position());
                Vec3 d = new Vec3(toBoss.x, 0, toBoss.z).normalize();
                Vec3 side = new Vec3(-d.z, 0, d.x);
                Vec3 from = p.position().subtract(d.scale(4.2)).add(side.scale(-1.1)).add(0, 2.6, 0);
                yield CameraShot.of(world(level, p.position().add(0, 1.6, 0), from), CameraPoint.world(b.add(0, 2.3, 0)))
                        .timing(40, FIGHT_AT - SHOT_PULL - 60, 20);
            }
        };
        CameraShots.play(p, shot.letterbox().cinema());
    }

    /** 机位被方块挡住就停在前面。 */
    private static CameraPoint world(ServerLevel level, Vec3 from, Vec3 to) {
        return CameraPoint.world(CameraShots.reach(level, from, to));
    }

    // ---------------------------------------------------------------- 战斗中

    private static void fighting(ServerLevel level, Arena a, long now) {
        if (now % 10 == 0) for (long l : a.lights) {
            BlockPos pos = BlockPos.of(l);
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5, 2, .12, .12, .12, .005);
        }
        if (now % 20 != 0) return;
        Mob boss = boss(level, a);
        List<ServerPlayer> inside = playersIn(level, a);
        for (ServerPlayer p : inside) if (!p.isCreative()) a.players.add(p.getUUID());
        if (boss == null) return;
        ShiraumeBoss b = (ShiraumeBoss) boss;
        boolean anyone = inside.stream().anyMatch(p -> !p.isCreative());
        a.empty = anyone ? 0 : a.empty + 20;
        if (!b.resting() && a.empty >= REST_AFTER) b.setResting(true);
        if (b.resting() && anyone) b.setResting(false);
        if (now % 40 == 0) unstick(level, a, boss);
    }

    /**
     * 防卡位：白梅够不着目标（目标站在玩家垒的方块上、或她被方块挡住），就把身边与目标脚下玩家放的方块烧掉；
     * 还是走不过去时化作花瓣，在目标附近的空地重新出现。
     */
    private static void unstick(ServerLevel level, Arena a, Mob boss) {
        LivingEntity target = boss.getTarget();
        purge(level, a, boss.position(), 3.5, null);
        if (target == null) return;
        boolean high = target.getY() - center(level, a).y > 3.5;
        boolean blocked = boss.getNavigation().isDone() && boss.distanceTo(target) > 6 || boss.horizontalCollision;
        if (!high && !blocked) return;
        int burned = purge(level, a, target.position(), 6, boss);
        burned += purge(level, a, boss.position(), 6, boss);
        if (burned == 0 && (high || boss.horizontalCollision) && boss.getRandom().nextFloat() < .4f) blink(level, boss, target);
    }

    /** 化作花瓣，在目标附近的空地重新出现。 */
    static void blink(ServerLevel level, Mob boss, LivingEntity target) {
        for (int i = 0; i < 12; i++) {
            double angle = boss.getRandom().nextDouble() * Math.PI * 2;
            Vec3 at = target.position().add(Math.cos(angle) * 4, 0, Math.sin(angle) * 4);
            BlockPos pos = BlockPos.containing(at);
            for (int dy = 2; dy >= -3; dy--) {
                BlockPos feet = pos.above(dy);
                if (level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP)
                        && level.noCollision(boss, boss.getDimensions(boss.getPose()).makeBoundingBox(Vec3.atBottomCenterOf(feet)))) {
                    level.sendParticles(ParticleTypes.CHERRY_LEAVES, boss.getX(), boss.getY() + 1.5, boss.getZ(), 60, .6, 1.2, .6, .05);
                    boss.teleportTo(feet.getX() + .5, feet.getY(), feet.getZ() + .5);
                    level.sendParticles(ParticleTypes.CHERRY_LEAVES, boss.getX(), boss.getY() + 1.5, boss.getZ(), 60, .6, 1.2, .6, .05);
                    level.playSound(null, boss.getX(), boss.getY(), boss.getZ(), SoundEvents.FOX_TELEPORT, SoundSource.HOSTILE, 1.5f, .7f);
                    return;
                }
            }
        }
    }

    // ---------------------------------------------------------------- 玩家放的方块

    /** 封印之间里玩家放下的方块都记下来，白梅被它们挡住时烧掉。 */
    @SubscribeEvent
    static void placed(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Player)) return;
        BlockPos pos = event.getPos();
        SanctuaryVaultPiece vault = FoxSanctuaryStructure.vaultAt(level, pos);
        Arenas data = arenas(level);
        if (vault != null && vault.inArena(pos)) {
            long key = vault.sealStone().asLong();
            VAULTS.put(key, vault);
            Arena a = data.get(key);
            if (a == null) a = data.put(new Arena(key, State.SEALED, level.getGameTime())); // 还没解封：只记方块
            a.placed.add(pos.asLong());
            data.setDirty();
            return;
        }
        for (Arena a : data.arenas.values()) {
            if (a.free != null && a.state != State.DEFEATED && inArena(level, a.key, pos)) {
                a.placed.add(pos.asLong());
                data.setDirty();
            }
        }
    }

    /**
     * 用她淡粉的狐火烧掉 {@code at} 附近 {@code radius} 格内玩家放的方块（方块消散，不掉落），
     * 以及圆台面上玩家倒的水和岩浆。返回烧掉了几块。
     */
    static int purge(ServerLevel level, Arena a, Vec3 at, double radius, @Nullable Mob by) {
        List<BlockPos> burn = new ArrayList<>();
        for (Iterator<Long> it = a.placed.iterator(); it.hasNext(); ) {
            BlockPos pos = BlockPos.of(it.next());
            if (level.getBlockState(pos).isAir()) {
                it.remove();
                continue;
            }
            if (Vec3.atCenterOf(pos).distanceToSqr(at) <= radius * radius) {
                burn.add(pos);
                it.remove();
            }
        }
        double floor = center(level, a).y;
        BlockPos.betweenClosedStream(BlockPos.containing(at).offset((int) -radius, -1, (int) -radius), BlockPos.containing(at).offset((int) radius, 4, (int) radius))
                .filter(p -> p.getY() >= floor && level.getFluidState(p).isSource() && !level.getFluidState(p).is(Fluids.EMPTY)
                        && level.getFluidState(p.below()).isEmpty())
                .map(BlockPos::immutable).limit(64).forEach(burn::add);
        if (burn.isEmpty()) return 0;
        Dissolving.blocks(level, burn, DissolveShape.point(at.x, at.y, at.z), 24, FoxfireFlame.SHIRAUME.core);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.FIRECHARGE_USE, SoundSource.HOSTILE, 1.4f, .6f);
        if (by != null) level.sendParticles(new net.minecraft.core.particles.DustParticleOptions(FoxfireFlame.SHIRAUME.core, 1.6f), at.x, at.y + 1, at.z, 30, radius * .4, 1, radius * .4, .02);
        arenas(level).setDirty();
        return burn.size();
    }

    // ---------------------------------------------------------------- 变身

    /** 第一条血清空：单膝跪地 → 拍地 → 光柱 → 九尾狐（第 5 节）。由人形的血量下限触发。 */
    public static void transform(ServerLevel level, Shiraume boss) {
        Arena a = arenaOf(level, boss);
        if (a.state == State.TRANSFORMING || a.state == State.PHASE_TWO || a.state == State.DEFEATED) return;
        boss.setScripted(true);
        boss.play("kneel");
        enter(level, a, State.TRANSFORMING);
        long until = level.getGameTime() + PHASE_TWO_AT + 20;
        Vec3 b = boss.position();
        float yaw = boss.getYRot() * Mth.DEG_TO_RAD;
        Vec3 f = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw)), r = new Vec3(-f.z, 0, f.x);
        for (ServerPlayer p : present(level, a)) {
            a.cutscene.add(p.getUUID());
            PROTECTED.put(p.getUUID(), until);
            CameraShots.play(p, CameraShot.of(world(level, b.add(0, 1.5, 0), b.add(f.scale(6.5)).add(r.scale(1.2)).add(0, 2, 0)),
                    CameraPoint.world(b.add(0, 1.0, 0))).timing(12, SLAM_AT + 20, 20).letterbox().cinema());
        }
    }

    private static void transforming(ServerLevel level, Arena a, long age) {
        Mob boss = boss(level, a);
        if (age == SLAM_AT && boss instanceof Shiraume s) s.play("slam");
        if (age == SWAP_AT && boss instanceof Shiraume s) swap(level, a, s);
        if (age == REVEAL_AT && boss instanceof ShiraumeBeast beast) {
            beast.play("reveal");
            Vec3 b = beast.position();
            float yaw = beast.getYRot() * Mth.DEG_TO_RAD;
            Vec3 f = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw)), r = new Vec3(-f.z, 0, f.x);
            for (ServerPlayer p : level.players()) {
                if (!a.cutscene.contains(p.getUUID())) continue;
                CameraShots.play(p, CameraShot.of(world(level, b.add(0, 2, 0), b.add(f.scale(8)).add(r.scale(2)).add(0, 1.2, 0)),
                        CameraPoint.world(b.add(0, 2.8, 0))).timing(18, PHASE_TWO_AT - REVEAL_AT - 38, 20).letterbox().cinema());
            }
        }
        if (age == ROAR_AT && boss != null) {
            level.playSound(null, boss.getX(), boss.getY() + 2, boss.getZ(), SoundEvents.FOX_SCREECH, SoundSource.HOSTILE, 4f, .45f);
            level.playSound(null, boss.getX(), boss.getY() + 2, boss.getZ(), SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 3f, .8f);
            ShiraumeCue.send(present(level, a), ShiraumeCue.shake(boss.position(), .6f));
        }
        if (age == FILL_AT && boss instanceof ShiraumeBoss b) b.showBar(true, 0);
        if (age >= PHASE_TWO_AT) {
            a.cutscene.clear();
            if (boss instanceof ShiraumeBoss b) b.setScripted(false);
            enter(level, a, State.PHASE_TWO);
        }
    }

    /** 拍地那一下（人形招式 {@code slam} 的接触）：冲击波推开周围的人，光柱冲起，黑白闪。 */
    static void slammed(ServerLevel level, Shiraume boss) {
        Arena a = arenaOf(level, boss);
        Vec3 b = boss.position();
        level.playSound(null, b.x, b.y, b.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 3f, .5f);
        level.playSound(null, b.x, b.y, b.z, SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 4f, .5f);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DEEPSLATE_TILES.defaultBlockState()), b.x, b.y + .2, b.z, 160, 4, .2, 4, .4);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, boss.getBoundingBox().inflate(10), e -> e != boss && e.isAlive())) {
            Vec3 away = e.position().subtract(b);
            away = new Vec3(away.x, 0, away.z).normalize();
            e.push(away.x * 1.6, .55, away.z * 1.6);
            e.hurtMarked = true;
        }
        List<ServerPlayer> watching = present(level, a);
        ShiraumeCue.send(watching, ShiraumeCue.shake(b, .9f));
        ShiraumeCue.send(watching, ShiraumeCue.aberration(.7f));
        float[] seals = new float[0];
        SanctuaryVaultPiece vault = a.free == null ? vault(level, a.key) : null;
        if (vault != null) {
            List<BlockPos> list = vault.seals();
            seals = new float[list.size() * 2];
            for (int i = 0; i < list.size(); i++) {
                seals[i * 2] = list.get(i).getX() + .5f;
                seals[i * 2 + 1] = list.get(i).getZ() + .5f;
            }
        } else {
            seals = new float[18];
            for (int i = 0; i < 9; i++) {
                double angle = Math.toRadians(40 * i);
                seals[i * 2] = (float) (b.x + Math.sin(angle) * 13);
                seals[i * 2 + 1] = (float) (b.z - Math.cos(angle) * 13);
            }
        }
        ShiraumeCue pillar = ShiraumeCue.pillar(b, level.getGameTime() + 2, seals);
        for (ServerPlayer p : level.players()) if (p.position().distanceToSqr(b) < 128 * 128) ShiraumeCue.send(p, pillar);
        for (ServerPlayer p : watching) {
            ScreenEffects.flash(p, ScreenFlash.at(CameraPoint.world(b.add(0, 3, 0))));
            if (!a.cutscene.contains(p.getUUID())) continue;
            float yaw = boss.getYRot() * Mth.DEG_TO_RAD;
            Vec3 f = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
            CameraShots.play(p, CameraShot.of(world(level, b.add(0, 2, 0), b.add(f.scale(13)).add(0, 2.5, 0)), CameraPoint.world(b.add(0, 7, 0)))
                    .timing(6, REVEAL_AT - SLAM_AT - 6, 20).letterbox().cinema());
        }
        purge(level, a, center(level, a), 64, boss);
    }

    /** 光柱里人形换成狐形：同一位置、同一朝向，叼着流火。 */
    private static void swap(ServerLevel level, Arena a, Shiraume human) {
        ShiraumeBeast beast = ShiraumeEntities.BEAST.get().create(level, EntitySpawnReason.EVENT);
        if (beast == null) return;
        beast.snapTo(human.getX(), human.getY(), human.getZ(), human.getYRot(), 0);
        beast.setYHeadRot(human.getYRot());
        beast.setYBodyRot(human.getYRot());
        scale(beast, level, a);
        beast.setArena(a.key);
        beast.setScripted(true);
        human.discard();
        level.addFreshEntity(beast);
        beast.play("reveal_hold");
        beast.showBar(false, 0);
        a.boss = beast.getUUID();
        arenas(level).setDirty();
    }

    // ---------------------------------------------------------------- 击败

    /** 第二条血清空（狐形倒下）：趴下、化作花瓣、红光退去、奖励（第 7 节）。 */
    public static void defeated(ServerLevel level, ShiraumeBeast beast) {
        Arena a = arenaOf(level, beast);
        if (a.state == State.DEFEATED) return;
        for (ServerPlayer p : playersIn(level, a)) if (!p.isCreative()) a.players.add(p.getUUID());
        enter(level, a, State.DEFEATED);
    }

    private static void defeated(ServerLevel level, Arena a, long age) {
        Mob boss = boss(level, a);
        if (age == MOOD_OFF_AT && a.free == null) sendMood(level, a, false);
        if (age == MOOD_OFF_AT) douse(level, a);
        if (age == DISSOLVE_AT && boss != null) Dissolving.entity(boss, DissolveShape.parse("down"), 80, ShiraumeEntities.CHERRY | Dissolving.PETALS);
        if (age == REWARD_AT) reward(level, a);
    }

    /** 每个参战的玩家各得一把流火，落在场地中央，只有本人能捡。白梅怎么加入还没定（docs/SHIRAUME_BOSS.md 第 8 节）。 */
    private static void reward(ServerLevel level, Arena a) {
        Vec3 c = center(level, a).add(0, 1.2, 0);
        int i = 0;
        for (UUID id : a.players) {
            double angle = i++ * 2.4;
            ItemEntity item = new ItemEntity(level, c.x + Math.cos(angle) * .8, c.y, c.z + Math.sin(angle) * .8, new ItemStack(RyukaItem.ITEM.get()));
            item.setDeltaMovement(0, .15, 0);
            item.setTarget(id);
            item.setUnlimitedLifetime();
            item.setPickUpDelay(30);
            level.addFreshEntity(item);
        }
        level.sendParticles(ParticleTypes.CHERRY_LEAVES, c.x, c.y + 2, c.z, 200, 3, 3, 3, .02);
        level.playSound(null, c.x, c.y, c.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.HOSTILE, 3f, .8f);
        arenas(level).setDirty();
    }

    // ---------------------------------------------------------------- 工具

    private static void enter(ServerLevel level, Arena a, State state) {
        a.state = state;
        a.since = level.getGameTime();
        arenas(level).setDirty();
    }

    /** 命令或测试召出来的白梅：以她现在的位置建一处场地（已经有场地的不变）。 */
    public static void adopt(ServerLevel level, Mob boss) {
        arenaOf(level, boss);
    }

    /** 这只白梅所在的场地；命令召出来、不在任何场地里的，以她现在的位置建一个。 */
    static Arena arenaOf(ServerLevel level, Mob boss) {
        ShiraumeBoss b = (ShiraumeBoss) boss;
        Arenas data = arenas(level);
        Long key = b.arena();
        Arena a = key == null ? null : data.get(key);
        if (a == null) {
            long k = boss.blockPosition().asLong();
            a = new Arena(k, boss instanceof ShiraumeBeast ? State.PHASE_TWO : State.PHASE_ONE, level.getGameTime());
            a.free = boss.position();
            a.boss = boss.getUUID();
            data.put(a);
            b.setArena(k);
        }
        return a;
    }

    static @Nullable Mob boss(ServerLevel level, Arena a) {
        if (a.boss == null) return null;
        Entity e = level.getEntity(a.boss);
        return e instanceof Mob m && e instanceof ShiraumeBoss && e.isAlive() ? m : e instanceof ShiraumeBeast beast ? beast : null;
    }

    static @Nullable ServerPlayer nearest(ServerLevel level, Arena a, Vec3 to) {
        ServerPlayer best = null;
        for (ServerPlayer p : present(level, a)) if (!p.isSpectator() && (best == null || p.distanceToSqr(to) < best.distanceToSqr(to))) best = p;
        return best;
    }

    /** 场地中心（没人时白梅回到这里）。 */
    static @Nullable Vec3 home(ServerLevel level, Mob boss) {
        Long key = ((ShiraumeBoss) boss).arena();
        Arena a = key == null ? null : arenas(level).get(key);
        return a == null ? null : center(level, a);
    }

    /** 记下参战的人（打过她的）。 */
    static void joined(ServerLevel level, Mob boss, LivingEntity attacker) {
        if (!(attacker instanceof ServerPlayer p) || p.isCreative()) return;
        Long key = ((ShiraumeBoss) boss).arena();
        Arena a = key == null ? null : arenas(level).get(key);
        if (a != null && a.players.add(p.getUUID())) arenas(level).setDirty();
    }

    /**
     * 锁定的目标：场地里活着的玩家（创造、旁观不算）。现在的目标还在场就继续锁它，否则换最近的。
     * 没有封印之间时，以场地中心 {@link #FREE_RADIUS} 格为界。
     */
    static @Nullable LivingEntity pickTarget(ServerLevel level, Mob boss) {
        Long key = ((ShiraumeBoss) boss).arena();
        LivingEntity current = boss.getTarget();
        if (key == null) {
            if (current != null && current.isAlive() && current.distanceToSqr(boss) < FREE_RADIUS * FREE_RADIUS) return current;
            Player p = level.getNearestPlayer(boss, 24);
            return p != null && !p.isCreative() && !p.isSpectator() ? p : null;
        }
        if (current instanceof Player p && p.isAlive() && !p.isCreative() && !p.isSpectator() && inArena(level, key, p.blockPosition())) return current;
        LivingEntity best = null;
        for (ServerPlayer p : level.players()) {
            if (!p.isAlive() || p.isCreative() || p.isSpectator() || !inArena(level, key, p.blockPosition())) continue;
            if (best == null || p.distanceToSqr(boss) < best.distanceToSqr(boss)) best = p;
        }
        return best;
    }

    /** 两条血都按觉醒时在场的玩家数加：每多一人 +50%，最多三倍。 */
    private static void scale(Mob boss, ServerLevel level, Arena a) {
        int n = Math.max(1, (int) playersIn(level, a).stream().filter(p -> !p.isCreative()).count());
        double factor = Math.min(3, 1 + .5 * (n - 1));
        var attribute = boss.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        if (attribute != null && factor > 1) {
            attribute.addOrReplacePermanentModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(LogosDimension.id("shiraume_players"),
                    factor - 1, net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
        boss.setHealth(boss.getMaxHealth());
    }

    /** 圆台上 {@code angle} 方向、离中心 {@code radius} 的落脚点（往里找到能站的地方）。 */
    private static Vec3 safeSpot(ServerLevel level, Vec3 c, double angle, double radius) {
        for (double rr = radius; rr > SEAL_RING; rr -= 1) {
            BlockPos feet = BlockPos.containing(c.x + Math.cos(angle) * rr, c.y, c.z + Math.sin(angle) * rr);
            if (level.getBlockState(feet).getCollisionShape(level, feet).isEmpty() && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                    && !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty())
                return Vec3.atBottomCenterOf(feet);
        }
        return new Vec3(c.x + Math.cos(angle) * radius, c.y, c.z + Math.sin(angle) * radius);
    }

    /** 岩顶落灰：封印之间里随机的几处往下落深板岩的灰。 */
    private static void ceilingDust(ServerLevel level, Arena a, int count) {
        Vec3 c = center(level, a);
        var dust = new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.DEEPSLATE.defaultBlockState());
        for (int i = 0; i < count; i++) {
            double angle = level.getRandom().nextDouble() * Math.PI * 2, r = Math.sqrt(level.getRandom().nextDouble()) * 24;
            level.sendParticles(dust, c.x + Math.cos(angle) * r, c.y + 29, c.z + Math.sin(angle) * r, 4, .5, 0, .5, 0);
        }
    }

    // ---------------------------------------------------------------- 过场保护

    /** 过场里的玩家不受伤（世界不暂停，锁着操作挨打不公平）。 */
    @SubscribeEvent
    static void protect(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        Long until = PROTECTED.get(p.getUUID());
        if (until != null && p.level().getGameTime() <= until) event.setCanceled(true);
    }

    // ---------------------------------------------------------------- 命令与测试

    /** 命令：在最近的封印之间里直接从「最后一道封印断开」开始。 */
    public static boolean awakenNearest(ServerLevel level, BlockPos near) {
        SanctuaryVaultPiece vault = FoxSanctuaryStructure.vaultAt(level, near);
        if (vault == null) return false;
        Arena old = arenas(level).get(vault.sealStone().asLong());
        if (old != null && old.state != State.SEALED) return false;
        for (BlockPos seal : vault.seals()) level.setBlock(seal, Blocks.AIR.defaultBlockState(), 3);
        return awaken(level, vault);
    }

    /** 这个维度里所有场地的键（测试用）。 */
    public static Set<Long> keys(ServerLevel level) {
        return Set.copyOf(arenas(level).arenas.keySet());
    }

    /** 命令：这处场地的状态（测试用）。 */
    public static @Nullable State state(ServerLevel level, long key) {
        Arena a = arenas(level).get(key);
        return a == null ? null : a.state;
    }

    /** 命令：清掉场地记录与白梅（不还原杀生石）。 */
    public static int reset(ServerLevel level) {
        Arenas data = arenas(level);
        int n = data.arenas.size();
        for (Arena a : List.copyOf(data.arenas.values())) {
            Mob boss = boss(level, a);
            if (boss != null) boss.discard();
            sendMood(level, a, false);
            douse(level, a);
        }
        data.arenas.clear();
        data.setDirty();
        TASKS.clear();
        PROTECTED.clear();
        return n;
    }

    /** 命令：直接进入第二阶段（当前的人形开始变身）。 */
    public static boolean skipToPhaseTwo(ServerLevel level) {
        for (Arena a : arenas(level).arenas.values()) {
            if (boss(level, a) instanceof Shiraume s && a.state == State.PHASE_ONE) {
                s.setHealth(0);
                return true;
            }
        }
        return false;
    }
}
