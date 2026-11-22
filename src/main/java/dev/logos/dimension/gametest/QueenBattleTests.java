package dev.logos.dimension.gametest;

import dev.logos.dimension.elven.blight.*;
import dev.logos.dimension.item.NaturesForceItem;
import dev.logos.lang.Element;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.*;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.Vec3;

/**
 * 用真实实体走完重做后的女王战：公主到场、阶段一打空、公主落到祭坛、五次降下（砍中、没砍到）、倒地、狂乱、拥心共殒，
 * 以及重置和门口相遇。公主该说的提醒都要说出来（屏幕上不写状态）。
 */
public final class QueenBattleTests {
    private QueenBattleTests() {}

    private static List<BlockPos> marks(ServerLevel world, BlockPos heart) {
        var arena = QueenArena.get(world.getServer());
        List<Vec3> marks = new ArrayList<>(List.of(arena.gate(), arena.entrance(), arena.altar()));
        marks.addAll(arena.tunnels());
        return marks.stream().map(m -> BlockPos.containing(QueenArena.at(heart, Direction.SOUTH, m)).below()).toList();
    }

    /**
     * 战场标记下垫一块石台，并强制加载标记所在的区块：测试场地在空中，公主、衍生物落在标记点上不往下掉；
     * 门口离树心 39 格，在夹具只加载的 3×3 区块外面（正常游戏里玩家站在那附近，那一片一定加载着）。
     */
    static void pads(ServerLevel world, BlockPos heart) {
        for (BlockPos at : marks(world, heart)) {
            world.setChunkForced(at.getX() >> 4, at.getZ() >> 4, true);
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) world.setBlockAndUpdate(at.offset(x, 0, z), Blocks.STONE.defaultBlockState());
        }
    }

    /** 放掉 {@link #pads} 强制加载的区块。 */
    static void unpads(ServerLevel world, BlockPos heart) {
        for (BlockPos at : marks(world, heart)) world.setChunkForced(at.getX() >> 4, at.getZ() >> 4, false);
    }

    /** 砍断所有还在的血管接头（客户端场景也用）。 */
    public static void cut(CorruptedElfQueen q, ServerPlayer player) {
        var world = player.level();
        for (var root : List.copyOf(q.tethers(world))) if (!root.heart()) {
            root.invulnerableTime = 0; root.hurtServer(world, world.damageSources().playerAttack(player), 1000);
        }
    }

    /** 降下来的腐败之心砍一刀（普通近战，客户端场景也用）。返回斩掉了一段没有。 */
    public static boolean cutHeart(CorruptedElfQueen q, ServerPlayer player) {
        var world = player.level();
        for (var t : q.tethers(world)) if (t.heart()) {
            int before = q.purification().cleared();
            t.invulnerableTime = 0;
            t.hurtServer(world, world.damageSources().playerAttack(player), 4);
            return q.purification().cleared() > before;
        }
        return false;
    }

    private static boolean said(ElfPrincess p, String line) {
        return dev.logos.dimension.companion.CompanionLines.said(p, "line.logos_dimension.elf_princess." + line);
    }

    private static void snapshot(GameTestHelper h, CorruptedElfQueen q, String what) {
        var world = h.getLevel();
        int phase = q.phase(), cleared = q.purification().cleared(), window = q.purification().window();
        float progress = q.purification().progress();
        var out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, world.registryAccess());
        q.saveWithoutId(out);
        q.load(TagValueInput.create(ProblemReporter.DISCARDING, world.registryAccess(), out.buildResult()));
        h.assertTrue(q.phase() == phase && q.purification().cleared() == cleared && q.purification().window() == window
                && Math.abs(q.purification().progress() - progress) < 1e-6, "存档重载丢了" + what);
    }

    static void battle(GameTestHelper h) {
        var f = ElvenBlightGameTests.fixture(h, new BlockPos(100000, 200, 100000)); var world = h.getLevel();
        pads(world, f.pos());
        f.player().getAbilities().invulnerable = true;
        // 0 阶段一 → 1 等公主落到祭坛 → 2 第一刀 → 3 第二次降下没砍到 → 4 倒地与扶起 → 5 剩下四刀 → 6 狂乱 → 7 结局
        int[] stage = {0};
        long[] mark = {0};
        int[] maxFilth = {0};
        boolean[] seen = new boolean[4]; // 衍生物出现、优先打公主、降下来时腐败之心被压低、光柱轰下来过
        CorruptedElfQueen[] queen = {null};
        ElfPrincess[] princess = {null};
        h.onEachTick(() -> {
            // 强制加载的区块要过几刻才可见（公主生成在门口那一片），再开战。
            if (queen[0] == null) {
                if (h.getTick() < 20) return;
                queen[0] = Objects.requireNonNull(f.heart().awaken(f.player()));
                princess[0] = Objects.requireNonNull(f.heart().princess(world), "开战时公主不在");
                h.assertTrue(princess[0].role() == ElfPrincess.Role.WAITING, "开战时公主没在大厅入口等着：" + princess[0].role());
                h.assertTrue(queen[0].getMaxHealth() == QueenBattleRules.HEALTH && Math.abs(dev.logos.runtime.SpellModifiers.taken(queen[0], Element.LIFE) - 1.3) < 1e-9, "阶段一生命或生命易伤不对");
                h.assertTrue(said(princess[0], "ward") || said(princess[0], "ward_none"), "开战时公主没说外面的核心");
                return;
            }
            var q = queen[0];
            var p = princess[0];
            if (q.entering() || !q.isAlive()) return;
            if (q.phase() < 3) f.player().setPos(q.arena().add(0, 0, 6));
            // 公主保持满血（这里测流程，不测她扛不扛得住）；倒地测试那一段自己控制。
            if (stage[0] != 4) { p.setHealth(p.getMaxHealth()); if (p.downed()) p.forceRevive(); }
            if (!q.waveMobs().isEmpty()) seen[0] = true;
            if (!seen[1]) seen[1] = q.waveMobs().stream().map(world::getEntity).anyMatch(e -> e instanceof net.minecraft.world.entity.Mob m && m.getTarget() == p);
            if (q.purification().open() && q.corePosition(0).y < f.pos().getY() - 4.5) seen[2] = true;
            if (q.pillarsFired() > 0) seen[3] = true;
            maxFilth[0] = Math.max(maxFilth[0], q.filthCount());
            switch (stage[0]) {
                case 0 -> {
                    q.invulnerableTime = 0;
                    q.hurtServer(world, world.damageSources().playerAttack(f.player()), 5000);
                    h.assertTrue(q.isAlive() && q.getHealth() == 1 && q.phase() == 2, "阶段一打空没有进入阶段二：" + q.phase() + " / " + q.getHealth());
                    h.assertTrue(p.role() == ElfPrincess.Role.LEAP && p.getY() > q.altar().y + 3, "公主没有从祭坛上方落下：" + p.role() + " " + p.position());
                    stage[0] = 1;
                }
                case 1 -> {
                    if (!p.channeling() || q.transitioning()) return;
                    h.assertTrue(p.position().distanceTo(q.altar()) < 1, "公主没落在祭坛上");
                    h.assertTrue(q.position().subtract(q.arena()).horizontalDistance() < 1.5, "阶段二女王没被拽回场地中央");
                    h.assertTrue(q.tethers(world).stream().filter(t -> !t.heart()).count() == QueenBattleRules.reachableVeins(), "高台上的血管接头数不对");
                    // 阶段二打不动女王。
                    q.invulnerableTime = 0;
                    h.assertTrue(!q.hurtServer(world, world.damageSources().playerAttack(f.player()), 50) && q.getHealth() == 1, "阶段二女王还能被打伤");
                    h.assertTrue(said(p, "queen_shielded"), "打女王时公主没提醒打不动");
                    snapshot(h, q, "阶段二");
                    var vein = q.tethers(world).stream().filter(t -> !t.heart()).findFirst().orElseThrow();
                    int slot = vein.slot();
                    vein.invulnerableTime = 0;
                    vein.hurtServer(world, world.damageSources().playerAttack(f.player()), 1000);
                    h.assertTrue(QueenBattleRules.severed(q.brokenRoots(), slot) && Integer.bitCount(q.brokenRoots()) == 1, "砍断血管没有记下");
                    h.assertTrue(said(p, "vein_cut"), "砍断血管后公主没说还剩几根");
                    q.sever(slot, world.damageSources().playerAttack(f.player()));
                    h.assertTrue(Integer.bitCount(q.brokenRoots()) == 1, "同一条血管被重复结算");
                    q.purification().debugProgress(.199f);
                    stage[0] = 2;
                }
                case 2 -> {
                    if (!q.purification().open()) return;
                    h.assertTrue(said(p, "window"), "腐败之心降下来时公主没喊");
                    h.assertTrue(cutHeart(q, f.player()), "降下来的腐败之心砍了一刀没有斩掉一段");
                    h.assertTrue(q.purification().cleared() == 1 && !q.purification().open() && q.cutting() && q.phase() == 2, "一刀之后没有升回去或没有处决的停顿");
                    h.assertTrue(Math.abs(q.purification().health() - .8f) < 1e-6, "一刀不是固定 20%");
                    q.purification().debugProgress(.399f);
                    stage[0] = 3; mark[0] = 0;
                }
                case 3 -> {
                    if (q.purification().open()) { if (mark[0] == 0) { mark[0] = 1; snapshot(h, q, "降下来的时候"); } return; }
                    if (mark[0] == 0) return;
                    // 没人砍到：它升回去，进度退 15%。
                    h.assertTrue(Math.abs(q.purification().progress() - (.4f - HeartPurification.FAIL_SETBACK)) < .01, "没砍到时没有退进度：" + q.purification().progress());
                    h.assertTrue(said(p, "window_failed"), "没砍到时公主没说");
                    p.forceDown();
                    h.assertTrue(said(p, "downed_first"), "公主第一次倒下没说要人拉她");
                    mark[0] = world.getGameTime();
                    stage[0] = 4;
                }
                case 4 -> {
                    h.assertTrue(p.downed(), "公主没有倒地");
                    if (world.getGameTime() - mark[0] < 40) return;
                    h.assertTrue(q.purification().progress() < .4f - HeartPurification.FAIL_SETBACK - .015f, "公主倒地时进度没有回退");
                    p.forceRevive();
                    h.assertTrue(p.channeling() && p.revives() == 1, "扶起后公主没有接着净化");
                    q.purification().debugProgress(.399f);
                    stage[0] = 5; mark[0] = 0;
                }
                case 5 -> {
                    if (q.phase() == QueenBattleRules.FRENZY) { stage[0] = 6; mark[0] = 0; return; }
                    if (q.purification().open()) { cutHeart(q, f.player()); return; }
                    // 每段只调一次进度：每刻都调的话会被按回去，永远涨不到刻度。
                    int c = q.purification().cleared();
                    if (mark[0] != c + 1 && !q.cutting() && c < HeartPurification.SEGMENTS) { q.purification().debugProgress(HeartPurification.THRESHOLDS[c] - .001f); mark[0] = c + 1; }
                }
                case 6 -> {
                    if (mark[0] == 0) {
                        h.assertTrue(q.purification().done() && q.waveMobs().isEmpty() && q.tethers(world).isEmpty(), "第五刀后衍生物或血管还在");
                        h.assertTrue(q.action().equals("queen_kneel") && said(p, "severed"), "第五刀后女王没跪倒或公主没说线断了");
                        mark[0] = 1;
                    }
                    if (q.phase() == QueenBattleRules.ENDING) {
                        h.assertTrue(said(p, "frenzy") && said(p, "embrace"), "狂乱时公主没喊出来");
                        h.assertTrue(maxFilth[0] > 80, "狂乱时脉络没有漫开：" + maxFilth[0]);
                        stage[0] = 7;
                    }
                }
                case 7 -> {
                    h.assertTrue(p.role() == ElfPrincess.Role.AFTER, "结局时公主没有收起净化");
                    h.assertTrue(q.waveMobs().isEmpty(), "结局时没有清掉衍生物");
                    if (q.endingTick() >= 100) {
                        h.assertTrue(q.filthCount() == 0, "自爆后漫开的脉络没有散掉");
                        h.assertTrue(WorldTreePurification.get(world).started(f.pos()), "结局没有启动世界树净化");
                        stage[0] = 8;
                    }
                }
                default -> { }
            }
        });
        h.succeedWhen(RealTimeWait.paced(() -> {
            h.assertTrue(stage[0] == 8, "等待战斗走完，现在在第 " + stage[0] + " 步" + (queen[0] == null ? "" : "（阶段 " + queen[0].phase() + "，进度 "
                    + queen[0].purification().progress() + "，已斩 " + queen[0].purification().cleared() + "，降下 " + queen[0].purification().window() + "，停顿 " + queen[0].cutting()
                    + "，心 " + queen[0].tethers(world).stream().filter(QueenTether::heart).count() + "，玩家活着 " + f.player().isAlive() + "，公主 " + princess[0].role() + "）"));
            try {
                h.assertTrue(seen[0] && seen[1], "衍生物没有出现或没有先找公主");
                h.assertTrue(seen[2], "降下来时腐败之心没有被压低");
                h.assertTrue(seen[3], "腐败之心的光柱没有轰下来过");
                h.assertTrue(f.heart().getBlockState().getValue(QueenHeartBlock.DEFEATED), "自爆后核心没有永久击败标记");
                h.succeed();
            } finally { f.close(h); unpads(world, f.pos()); }
        }));
    }

    /** 所有人离开后重置：女王回到坐姿，公主回到入口，衍生物、污秽、血管接头清掉，进度清零。 */
    static void reset(GameTestHelper h) {
        var f = ElvenBlightGameTests.fixture(h, new BlockPos(-100000, 200, 100000)); var world = h.getLevel();
        pads(world, f.pos());
        f.player().getAbilities().invulnerable = true;
        boolean[] done = {false};
        CorruptedElfQueen[] queen = {null};
        ElfPrincess[] princess = {null};
        h.onEachTick(() -> {
            // 强制加载的区块要过几刻才可见，再开战。
            if (queen[0] == null) {
                if (h.getTick() < 20) return;
                queen[0] = Objects.requireNonNull(f.heart().awaken(f.player()));
                princess[0] = Objects.requireNonNull(f.heart().princess(world), "开战时公主不在");
                return;
            }
            var q = queen[0];
            var p = princess[0];
            if (q.entering() || done[0]) return;
            f.player().setPos(q.arena().add(0, 0, 6));
            p.setHealth(p.getMaxHealth());
            if (q.phase() == 1) { q.invulnerableTime = 0; q.hurtServer(world, world.damageSources().playerAttack(f.player()), 5000); return; }
            if (q.waveMobs().isEmpty() || q.tethers(world).isEmpty()) return;
            var mobs = q.waveMobs();
            q.purification().debugProgress(.15f);
            q.resetBattle(world);
            h.assertTrue(q.phase() == 1 && q.action().equals("queen_guard") && q.getHealth() == q.getMaxHealth(), "女王没有回到坐姿和满血");
            h.assertTrue(q.purification().progress() == 0 && q.brokenRoots() == 0, "净化进度或血管没有清零");
            h.assertTrue(q.waveMobs().isEmpty() && mobs.stream().noneMatch(id -> world.getEntity(id) != null), "衍生物没有消散");
            h.assertTrue(q.filthCount() == 0, "地上的污秽没有消散");
            h.assertTrue(p.role() == ElfPrincess.Role.WAITING, "公主没有回到大厅入口：" + p.role());
            done[0] = true;
        });
        h.succeedWhen(RealTimeWait.paced(() -> {
            h.assertTrue(done[0], "等待衍生物出现后重置");
            try {
                h.assertTrue(queen[0].tethers(world).isEmpty(), "重置后血管接头还在");
                h.succeed();
            } finally { f.close(h); unpads(world, f.pos()); }
        }));
    }

    /** 门口相遇：公主由腐败之心在门口生成，交出自然之力，说「走吧」后才进大厅；她不到场女王不起身。 */
    static void gate(GameTestHelper h) {
        var f = ElvenBlightGameTests.fixture(h, new BlockPos(100000, 200, -100000)); var world = h.getLevel();
        pads(world, f.pos());
        int[] step = {0};
        h.onEachTick(() -> {
            switch (step[0]) {
                case 0 -> {
                    var p = f.heart().princess(world);
                    if (p == null) return;
                    Vec3 gate = QueenArena.at(f.pos(), Direction.SOUTH, QueenArena.get(world.getServer()).gate());
                    h.assertTrue(p.role() == ElfPrincess.Role.GATE && p.position().distanceTo(gate) < 2, "公主没有在门口生成：" + p.role() + " " + p.position());
                    h.assertTrue(!p.hurtServer(world, world.damageSources().playerAttack(f.player()), 50) && p.getHealth() == p.getMaxHealth(), "战斗外能打伤公主");
                    var greet = p.talkGreeting(f.player());
                    h.assertTrue(f.player().getInventory().contains(s -> s.getItem() instanceof NaturesForceItem), "门口没有交出自然之力");
                    h.assertTrue(greet.stream().anyMatch(l -> l.key().endsWith("greet_cores")), "开场没有说外面供养女王的核心");
                    int bows = f.player().getInventory().countItem(NaturesForceItem.ITEM.get());
                    p.talkGreeting(f.player());
                    h.assertTrue(f.player().getInventory().countItem(NaturesForceItem.ITEM.get()) == bows, "第二次说话又给了一把弓");
                    step[0] = 1;
                }
                case 1 -> {
                    // 女王坐在核心下：公主还在门口，玩家走到跟前她也不起身。
                    var queen = f.heart().queen() == null ? null : world.getEntity(f.heart().queen());
                    if (!(queen instanceof CorruptedElfQueen q)) return;
                    f.player().setPos(q.position().add(0, 0, 4));
                    if (q.tickCount < 60) return;
                    h.assertTrue(q.action().equals("queen_guard"), "公主没到场女王就起身了");
                    var p = f.heart().princess(world);
                    var reply = p.talkPick(f.player(), "ready");
                    h.assertTrue(p.role() == ElfPrincess.Role.AHEAD && !reply.lines().isEmpty(), "说「走吧」后公主没有动身");
                    p.arrive(world);
                    step[0] = 2;
                }
                case 2 -> {
                    var q = (CorruptedElfQueen) world.getEntity(f.heart().queen());
                    if (q.action().equals("queen_guard")) return;
                    step[0] = 3;
                }
                default -> { }
            }
        });
        h.succeedWhen(RealTimeWait.paced(() -> {
            h.assertTrue(step[0] == 3, "等待公主到场后女王起身，现在在第 " + step[0] + " 步");
            try {
                // 公主的存档：门口相遇记录和给过弓的人。
                var p = f.heart().princess(world);
                var out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, world.registryAccess());
                p.saveWithoutId(out);
                p.load(TagValueInput.create(ProblemReporter.DISCARDING, world.registryAccess(), out.buildResult()));
                h.assertTrue(p.ready() && p.talkGreeting(f.player()).stream().noneMatch(l -> l.key().endsWith("greet_first")), "重载丢了公主的状态或相遇记录");
                var tag = f.heart().saveWithFullMetadata(world.registryAccess());
                var loaded = (QueenHeart) net.minecraft.world.level.block.entity.BlockEntity.loadStatic(f.pos(), f.heart().getBlockState(), tag, world.registryAccess());
                h.assertTrue(loaded != null && p.getUUID().equals(loaded.princessId()), "腐败之心重载丢了公主");
                h.succeed();
            } finally { f.close(h); unpads(world, f.pos()); }
        }));
    }

    static void spells(GameTestHelper h) {
        var f = ElvenBlightGameTests.fixture(h); var world = h.getLevel();
        var q = Objects.requireNonNull(f.heart().awaken(f.player()));
        for (String id : List.of("fan", "ray", "rain", "thorn_vein", "thorn_pierce")) h.assertTrue(QueenSpells.spell(world.getServer(), id) != null, "载页不能编译：" + id);
        var moves = QueenMoves.of(world.getServer(), CorruptedElfQueen.MOVESET);
        h.assertTrue(moves.shape("queen_life_ray").mana() == 40 && moves.plan().rotation().size() == 7 && moves.plan().spells().size() == 5, "招式文件里的耗魔或出招顺序没读到");
        h.assertTrue(moves.shape("queen_thorn_pierce").mana() == 100 && "thorn_pierce".equals(moves.shape("queen_thorn_pierce").spell()), "荆棘穿刺没写进招式文件");
        var to = f.player().getEyePosition().subtract(q.getEyePosition()).normalize();
        q.setYRot((float) Math.toDegrees(Math.atan2(-to.x, to.z))); q.setXRot((float) -Math.toDegrees(Math.atan2(to.y, Math.hypot(to.x, to.z))));
        float before = f.player().getHealth(); double mana = q.mana();
        h.assertTrue(QueenSpells.cast(q, "ray", q.getEyePosition(), f.player(), 40), "无法通过 Logos 运行时施法");
        h.assertTrue(f.player().getHealth() < before && q.mana() == mana - 40, "真实载页未伤到玩家或没有付费");
        h.assertTrue(f.player().getLastDamageSource() != null && f.player().getLastDamageSource().is(dev.logos.dimension.corruption.Corruption.DAMAGE), "女王的生命法术不是腐败伤害");
        // 腐化荆棘：游脉画成腐败荆棘（先给客户端打个招呼）。
        long cues = QueenSpellEffects.replaced(), thorns = QueenSpellEffects.thorns();
        q.setXRot(0);
        h.assertTrue(QueenSpells.cast(q, "thorn_vein", q.getEyePosition(), f.player(), 40), "腐化荆棘放不出来");
        // 荆棘穿刺的目标是她锁定的敌人（foe）：锁定玩家时圈在玩家脚下。
        q.setTarget(f.player());
        h.assertTrue(QueenSpells.cast(q, "thorn_pierce", q.getEyePosition(), f.player(), 100), "荆棘穿刺放不出来");
        long started = world.getGameTime();
        h.succeedWhen(RealTimeWait.paced(() -> {
            h.assertTrue(QueenSpellEffects.replaced() > cues + 1, "腐化荆棘或荆棘穿刺没有换成腐败的样子");
            h.assertTrue(world.getGameTime() - started > 30, "等荆棘穿刺的三轮刺完");
            h.assertTrue(QueenSpellEffects.thorns() >= thorns + 9, "荆棘穿刺的射线没有全画成向上刺出的荆棘：" + (QueenSpellEffects.thorns() - thorns));
            h.assertTrue(QueenSpellEffects.lastField().distanceTo(f.player().position()) < 2.5, "荆棘穿刺没圈在她锁定的玩家脚下：" + QueenSpellEffects.lastField() + " 玩家 " + f.player().position() + " 女王 " + q.position());
            try {
                q.payMana(q.mana());
                h.assertTrue(!QueenSpells.cast(q, "fan", q.getEyePosition(), f.player(), 60) && q.mana() == 0, "魔力不足仍然施法");
                h.succeed();
            } finally { f.close(h); }
        }));
    }
    static void restoration(GameTestHelper h) {
        var world = h.getLevel();
        // 开发测试会复用存档；每轮选择新区域，不能把上一轮已完成的净化区当成新任务。
        var center = new BlockPos(2_000_000 + (int) (world.getGameTime() % 100000) * 32, 250, -2_000_000);
        var remembered = ElvenBlight.MEMORY.get().defaultBlockState();
        var original = BlightMemoryBlock.original(remembered);
        BlockPos p = center.east(3), built = center.east(4), outside = center.east(30);
        world.setBlockAndUpdate(p, remembered); world.setBlockAndUpdate(built, Blocks.DIAMOND_BLOCK.defaultBlockState()); world.setBlockAndUpdate(outside, remembered);
        // 模拟从结构区块读取、尚未实例化的 DUMMY 核心，复现净化后延迟 NBT 残留。
        BlockPos pending = center.west(3);
        world.setBlockAndUpdate(pending, ElvenBlight.NODE.get().defaultBlockState());
        var chunk = world.getChunkAt(pending); chunk.removeBlockEntity(pending);
        var delayed = new net.minecraft.nbt.CompoundTag(); delayed.putString("id", "DUMMY");
        delayed.putInt("x", pending.getX()); delayed.putInt("y", pending.getY()); delayed.putInt("z", pending.getZ());
        chunk.setBlockEntityNbt(delayed);
        h.assertTrue(chunk.getBlockEntityNbt(pending) != null, "没有准备延迟加载核心");
        var job = WorldTreePurification.get(world); job.start(world, center, 16); job.start(world, center, 16);
        h.succeedWhen(RealTimeWait.paced(() -> {
            h.assertTrue(world.getBlockState(p) == original, "等待区块批量还原");
            h.assertTrue(world.getBlockState(built).is(Blocks.DIAMOND_BLOCK), "覆盖了玩家建筑");
            h.assertTrue(world.getBlockState(pending).isAir(), "等待核心方块被净化：" + world.getBlockState(pending));
            h.assertTrue(chunk.getBlockEntityNbt(pending) == null && chunk.getBlockEntity(pending) == null, "已移除核心仍有延迟方块实体数据");
            h.assertTrue(world.getBlockState(outside) == remembered, "清理超出球形范围");
            var saved = WorldTreePurification.CODEC.encodeStart(NbtOps.INSTANCE, job).getOrThrow();
            var loaded = WorldTreePurification.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
            h.assertTrue(loaded.started(center), "净化地区没有持久化");
            h.succeed();
        }));
    }
}
