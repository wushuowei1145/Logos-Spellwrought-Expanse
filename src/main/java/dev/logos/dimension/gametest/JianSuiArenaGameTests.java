package dev.logos.dimension.gametest;

import com.mojang.authlib.GameProfile;
import dev.logos.dimension.jiansui.FallStage;
import dev.logos.dimension.jiansui.JianSui;
import dev.logos.dimension.jiansui.JianSuiBlocks;
import dev.logos.dimension.jiansui.JianSuiFight;
import dev.logos.dimension.jiansui.JianSuiFight.State;
import dev.logos.dimension.longsword.LongswordCombat;
import dev.logos.dimension.taixuan.TaixuanSite;
import dev.logos.runtime.PlayerMagic;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * 剑祟整场（docs/JIAN_SUI_BOSS.md 第 18 节）：在云岫峰林一处真的太玄灵境上，一个嵌入连接的假玩家从走近雾底开始
 * （和平时一样由 scout 放下残骸），醒来、登场、两条血打空、离体、裂地、被送进空洞；躲开一柄巨剑看它留下剑格台阶，
 * 再站着挨一柄；放的方块被斩碎；掉进虚空（黑屏这几刻落到洞底不摔伤）被送回落脚台；蹲在起点的裂缝上回到上面；
 * 踩石台的裂缝下来、再从裂缝上去（回去的地方避开裂缝）、再下来；最后近战打中剑祟进终结，黑白定格里换到上面，不按也按时
 * 自己撬完，看遗剑插好、人回到上面、场景收走、剑祟真的没了，重置后遗剑和留下的疤都还原。假玩家不自己动：位置都由测试和场地摆。
 * 撬三下按攻击键另见 {@link #finisherPry}。
 */
final class JianSuiArenaGameTests {
    private JianSuiArenaGameTests() {}

    /** 按顺序等条件、做动作；每一步等太久就失败。 */
    private static final class Script {
        record Step(String name, int max, BooleanSupplier until, Runnable then) {}

        final GameTestHelper h;
        final List<Step> steps = new ArrayList<>();
        final Runnable close;
        int index, waited;
        boolean done;

        Script(GameTestHelper h, Runnable close) {
            this.h = h;
            this.close = close;
        }

        Script await(String name, int max, BooleanSupplier until, Runnable then) {
            steps.add(new Step(name, max, until, then));
            return this;
        }

        Script run(String name, Runnable action) {
            return await(name, 1, () -> true, action);
        }

        Script pause(int ticks) {
            int[] n = {0};
            return await("停 " + ticks + " 刻", ticks + 2, () -> ++n[0] > ticks, () -> {});
        }

        void tick() {
            if (done) return;
            try {
                while (index < steps.size()) {
                    Step s = steps.get(index);
                    if (!s.until.getAsBoolean()) {
                        if (++waited > s.max) h.assertTrue(false, "等太久：" + s.name);
                        return;
                    }
                    s.then.run();
                    index++;
                    waited = 0;
                    if (s.max > 1) return;
                }
                done = true;
                close.run();
                h.succeed();
            } catch (RuntimeException | Error e) {
                done = true;
                close.run();
                throw e;
            }
        }
    }

    private static ServerPlayer player(GameTestHelper h) {
        var server = h.getLevel().getServer();
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "jian-sui-arena"), false);
        var p = new ServerPlayer(server, h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, p, cookie);
        p.connection.markClientLoaded();
        p.setGameMode(GameType.SURVIVAL);
        return p;
    }

    /**
     * 让测试里拼装的地标在世界里「登记」上（同世界生成）：起点区块记着结构起点，它覆盖的区块都记一笔引用。
     * 这样 {@link TaixuanSite#at}（scout、命令都靠它找地标）在测试世界里也找得到。
     */
    static void register(ServerLevel level, StructureStart start) {
        var origin = start.getChunkPos();
        level.getChunk(origin.x(), origin.z()).setStartForStructure(start.getStructure(), start);
        BoundingBox box = start.getBoundingBox();
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++)
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++)
                level.getChunk(cx, cz).addReferenceForStructure(start.getStructure(), origin.pack());
    }

    /** {@code xz} 那一列、{@code near} 高度附近能站的地方（实心方块上面两格是空的）。 */
    private static Vec3 ground(ServerLevel level, Vec3 xz, double near) {
        BlockPos top = BlockPos.containing(xz.x, near + 6, xz.z);
        for (int dy = 0; dy < 20; dy++) {
            BlockPos q = top.below(dy);
            if (!level.getBlockState(q).getCollisionShape(level, q).isEmpty() && level.getBlockState(q.above()).getCollisionShape(level, q.above()).isEmpty()
                    && level.getBlockState(q.above(2)).getCollisionShape(level, q.above(2)).isEmpty())
                return new Vec3(xz.x, q.getY() + level.getBlockState(q).getCollisionShape(level, q).max(net.minecraft.core.Direction.Axis.Y), xz.z);
        }
        throw new IllegalStateException("站不住：" + xz);
    }

    private static void put(ServerPlayer p, ServerLevel level, Vec3 at) {
        p.teleportTo(level, at.x, at.y, at.z, Set.of(), p.getYRot(), 0, true);
        // 假玩家没有客户端回执：换维度后自己收尾，不然一直算「正在换维度」，什么伤害都不吃
        p.hasChangedDimension();
        p.connection.markClientLoaded();
        p.setDeltaMovement(Vec3.ZERO);
        p.setOnGround(true);
        p.resetFallDistance();
    }

    private static boolean inCavity(Vec3 p) {
        return p.y < -19 && p.y > -60;
    }

    private static double flat(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    /** {@code at} 周围 {@code r} 格内有没有剑格方块。 */
    private static boolean guardNear(ServerLevel level, Vec3 at, int r) {
        BlockPos c = BlockPos.containing(at);
        return BlockPos.betweenClosedStream(c.offset(-r, -r, -r), c.offset(r, r, r)).anyMatch(q -> level.getBlockState(q).is(JianSuiBlocks.SWORD_GUARD.get()));
    }

    /** 遗剑下面那一层 5×5 的地砖（留疤前后比对）。 */
    private static List<BlockState> floor(ServerLevel level, BlockPos relic) {
        List<BlockState> out = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) out.add(level.getBlockState(relic.below().offset(dx, 0, dz)));
        return out;
    }

    /**
     * 弹反巨剑（第 10.3 节）：直接跳到第三阶段，人站在起点碎块面上拿着打刀、面朝剑祟，剑到之前两刻举刀：
     * 不掉血、没被击退，剑祟晃一下（parried）。顺带看清魔力不算修行：带着 100 点魔力进来，被清零后 Logos 不该把它记成「用掉的」。
     */
    static void giantParry(GameTestHelper h) {
        ServerLevel level = TaixuanGameTests.level(h);
        TaixuanSite site = TaixuanSite.of(TaixuanGameTests.placeStudy(level).start());
        h.assertTrue(site != null, "找不到太玄灵境的地标");
        Vec3 plunge = JianSuiFight.plunge(site);
        Vec3 out = plunge.subtract(site.axis(plunge.y)).multiply(1, 0, 1).normalize();
        Vec3 near = ground(level, plunge.add(out.scale(5)), plunge.y);
        ServerPlayer p = player(h);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(dev.logos.dimension.item.UchigatanaItem.INK.get()));
        Vec3[] pin = new Vec3[1];
        long[] t0 = new long[1];
        double[] spent = new double[1], push = new double[1];
        boolean[] parried = new boolean[1];
        List<String> moves = new ArrayList<>();
        var script = new Script(h, () -> {
            p.stopUsingItem();
            JianSuiFight.reset(level, site);
            level.getServer().getPlayerList().remove(p);
        });
        script
            .run("站到石台边", () -> put(p, level, near))
            .pause(10)
            .run("带着 100 点魔力跳到第三阶段", () -> {
                PlayerMagic magic = PlayerMagic.of(p);
                magic.mana = 100;
                magic.manaSeen = 100;
                spent[0] = magic.manaSpent;
                JianSuiFight.reset(level, site);
                JianSuiFight.skip(level, site, State.FALL);
                t0[0] = level.getGameTime();
            })
            .await("送到起点碎块", 20, () -> inCavity(p.position()), () -> {
                p.hasChangedDimension();
                p.connection.markClientLoaded();
                // 站到碎块面上（送下来时在上方两格）
                pin[0] = ground(level, p.position(), p.getY());
            })
            .await("剑到之前两刻举刀", 80, () -> level.getGameTime() >= t0[0] + JianSuiFight.FIRST_SWORD + JianSuiFight.SWORD_AIM + JianSuiFight.SWORD_FLIGHT - 2, () -> {
                h.assertTrue(p.getHealth() == p.getMaxHealth(), "举刀前就掉血了");
                p.getMainHandItem().use(level, p, InteractionHand.MAIN_HAND);
            })
            .await("剑弹回去打在剑祟身上", 30, () -> moves.contains("parried"), () -> {
                h.assertTrue(p.getHealth() == p.getMaxHealth(), "弹反了还掉了血：" + p.getHealth());
                h.assertTrue(push[0] < .3, "弹反了还被击退：" + push[0]);
                h.assertTrue(PlayerMagic.of(p).mana == 0, "第三阶段魔力没有清零：" + PlayerMagic.of(p).mana);
                h.assertTrue(PlayerMagic.of(p).manaSpent - spent[0] < 1, "清零的魔力被记成了修行：" + (PlayerMagic.of(p).manaSpent - spent[0]));
            });
        h.onEachTick(() -> {
            if (pin[0] != null) {
                // 剑落下的那一刻（场地的每刻处理在测试之前跑）设的速度，在钉住之前量
                if (level.getGameTime() >= t0[0] + JianSuiFight.FIRST_SWORD + JianSuiFight.SWORD_AIM + JianSuiFight.SWORD_FLIGHT)
                    push[0] = Math.max(push[0], p.getDeltaMovement().horizontalDistance());
                // Logos 回魔力、按「魔力比上一刻看到的少了多少」记修行：逐刻结算一次（不靠假玩家的刻数），
                // 排在玩家这一刻之前，这样场地排在最后的清零照真实的顺序跑在它后面
                dev.logos.runtime.ManaGrowth.tick(p);
                // 打刀要逐刻更新（拔刀、举刀），人钉在原地、面朝剑祟。
                // 同 JianSuiGameTests 的 Fixture：刻数钉在 1，按「每 20 刻」发包的处理（没有通道的假玩家收到会报错）不会轮到它
                p.tickCount = 1;
                p.doTick();
                p.setPos(pin[0]);
                p.setDeltaMovement(Vec3.ZERO);
                p.setOnGround(true);
                JianSui boss = JianSuiFight.boss(level, site);
                if (boss != null) {
                    Vec3 to = boss.position().subtract(p.position());
                    float yaw = (float) Math.toDegrees(Math.atan2(-to.x, to.z));
                    p.setYRot(yaw);
                    p.setYHeadRot(yaw);
                    String m = boss.move();
                    if (moves.isEmpty() || !moves.getLast().equals(m)) moves.add(m);
                }
            }
            script.tick();
        });
    }

    /**
     * 撬三下（第 11 节）：直接跳到第三阶段，拿着打刀近战打中剑祟。打中的那一下还在出招的结算里，下一刻（发黑白「飞身刺」时）
     * 才暂停战斗骨架；黑白里早按的不算；撬剑的窗口一开按一下，之后每隔 {@link JianSuiFight#PRY_GAP} 刻再按，三下都比不按时
     * 自己撬的早，多按的第四下不算；第三下之后 {@link JianSuiFight#BURST_AFTER} 刻贯穿古剑崩出，再
     * {@link JianSuiFight#GONE_AFTER} 刻收尾，战斗骨架恢复。假玩家不自己动、没有客户端，按键直接调服务端收按键的那一处。
     */
    static void finisherPry(GameTestHelper h) {
        ServerLevel level = TaixuanGameTests.level(h);
        TaixuanSite site = TaixuanSite.of(TaixuanGameTests.placeStudy(level).start());
        h.assertTrue(site != null, "找不到太玄灵境的地标");
        Vec3 plunge = JianSuiFight.plunge(site);
        Vec3 out = plunge.subtract(site.axis(plunge.y)).multiply(1, 0, 1).normalize();
        Vec3 near = ground(level, plunge.add(out.scale(5)), plunge.y);
        ServerPlayer p = player(h);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(dev.logos.dimension.item.UchigatanaItem.INK.get()));
        long[] hit = new long[1], beat = new long[3], burst = new long[1];
        LongSupplier t = () -> level.getGameTime() - hit[0];
        IntSupplier beats = () -> JianSuiFight.pryBeats(level, site);
        var script = new Script(h, () -> {
            JianSuiFight.reset(level, site);
            level.getServer().getPlayerList().remove(p);
        });
        script
            .run("站到石台边", () -> put(p, level, near))
            .pause(10)
            .run("跳到第三阶段", () -> {
                JianSuiFight.reset(level, site);
                JianSuiFight.skip(level, site, State.FALL);
            })
            .await("送到起点碎块", 20, () -> inCavity(p.position()), () -> {
                p.hasChangedDimension();
                p.connection.markClientLoaded();
            })
            .run("拿着打刀近战打中剑祟", () -> {
                JianSui boss = JianSuiFight.boss(level, site);
                h.assertTrue(boss != null, "第三阶段没有剑祟");
                put(p, level, boss.position().add(1.2, 0, 0));
                boss.hurtServer(level, level.damageSources().playerAttack(p), 1);
                h.assertTrue(JianSuiFight.state(level, site) == State.FINISHER, "近战打中没有进终结");
                h.assertTrue(!LongswordCombat.paused(p), "打中的那一下里就暂停了战斗骨架（还在出招的结算里）");
                hit[0] = level.getGameTime();
            })
            .await("发黑白「飞身刺」时暂停战斗骨架", 3, () -> LongswordCombat.paused(p), () ->
                h.assertTrue(t.getAsLong() == JianSuiFight.FIN_SHOT, "暂停不在发黑白的那一刻：打中后第 " + t.getAsLong() + " 刻"))
            .await("黑白里早按一下", JianSuiFight.FIN_COLOUR, () -> t.getAsLong() >= JianSuiFight.FIN_COLOUR - 3, () -> JianSuiFight.pryPress(p))
            .await("撬剑的窗口一开按第一下", 20, () -> t.getAsLong() >= JianSuiFight.PRY_OPEN, () -> {
                h.assertTrue(beats.getAsInt() == 0, "黑白里早按的那一下也算了");
                h.assertTrue(LongswordCombat.paused(p), "过场里战斗骨架没有一直暂停着");
                h.assertTrue(!inCavity(p.position()), "回到彩色时人还在空洞里：" + p.position());
                JianSuiFight.pryPress(p);
            })
            .await("第一下", 4, () -> beats.getAsInt() == 1, () -> beat[0] = t.getAsLong())
            .await("隔开再按第二下", JianSuiFight.PRY_GAP + 2, () -> t.getAsLong() >= beat[0] + JianSuiFight.PRY_GAP, () -> JianSuiFight.pryPress(p))
            .await("第二下", 4, () -> beats.getAsInt() == 2, () -> beat[1] = t.getAsLong())
            .await("隔开再按第三下", JianSuiFight.PRY_GAP + 2, () -> t.getAsLong() >= beat[1] + JianSuiFight.PRY_GAP, () -> JianSuiFight.pryPress(p))
            .await("第三下", 4, () -> beats.getAsInt() == 3, () -> {
                beat[2] = t.getAsLong();
                // 多按一下：已经撬满三下，不算
                JianSuiFight.pryPress(p);
            })
            .await("第三下之后贯穿古剑崩出", JianSuiFight.BURST_AFTER + 3, () -> {
                JianSui boss = JianSuiFight.boss(level, site);
                return boss != null && (boss.hidden() & JianSui.HIDE_AXIAL) != 0;
            }, () -> {
                burst[0] = t.getAsLong();
                for (int i = 0; i < beat.length; i++)
                    h.assertTrue(beat[i] < JianSuiFight.PRY_AUTO[i], "按了的第 " + (i + 1) + " 下没有比自己撬的早：第 " + beat[i] + " 刻，自己撬在第 " + JianSuiFight.PRY_AUTO[i] + " 刻");
                h.assertTrue(beat[1] - beat[0] >= JianSuiFight.PRY_GAP && beat[2] - beat[1] >= JianSuiFight.PRY_GAP, "两下隔得太近：" + Arrays.toString(beat));
                h.assertTrue(burst[0] - beat[2] == JianSuiFight.BURST_AFTER, "崩出没有紧跟第三下：第三下第 " + beat[2] + " 刻，崩出第 " + burst[0] + " 刻");
                h.assertTrue(beats.getAsInt() == 3, "多按的第四下也算了：" + beats.getAsInt());
                h.assertTrue(LongswordCombat.paused(p), "崩出时战斗骨架已经恢复了（过场还没完）");
            })
            .await("收尾：打败，战斗骨架恢复", JianSuiFight.GONE_AFTER + 3, () -> JianSuiFight.state(level, site) == State.DEFEATED, () -> {
                h.assertTrue(t.getAsLong() - burst[0] == JianSuiFight.GONE_AFTER, "收尾没有按崩出算：崩出第 " + burst[0] + " 刻，收尾第 " + t.getAsLong() + " 刻");
                h.assertTrue(!LongswordCombat.paused(p), "收尾后战斗骨架还暂停着");
                h.assertTrue(!inCavity(p.position()) && flat(p.position(), plunge) < 2.0, "收尾后打中的人不在剑祟面前：" + p.position());
            });
        h.onEachTick(script::tick);
    }

    static void flow(GameTestHelper h) {
        ServerLevel level = TaixuanGameTests.level(h);
        var study = TaixuanGameTests.placeStudy(level);
        TaixuanSite site = TaixuanSite.of(study.start());
        h.assertTrue(site != null, "找不到太玄灵境的地标");
        Vec3 plunge = JianSuiFight.plunge(site);
        Vec3 axis = site.axis(plunge.y);
        Vec3 out = plunge.subtract(axis).multiply(1, 0, 1).normalize();
        Vec3 far = ground(level, plunge.add(out.scale(12)), plunge.y), near = ground(level, plunge.add(out.scale(5)), plunge.y);
        Vec3 start = JianSuiFight.stone(site, 0);
        int feet = TaixuanSite.cavity().getAsJsonObject("start").get("feet").getAsInt();
        // 起点碎块上另一处（离正中两格）：躲巨剑时挪到这里
        Vec3 dodge = site.point(135.5, feet, 124.5);
        Vec3 checkpoint = JianSuiFight.checkpoint(site, 1);
        BlockPos relic = JianSuiFight.relicPos(site);
        ServerPlayer p = player(h);
        long[] mark = new long[2], hitAt = new long[1];
        float[] health = new float[1];
        BlockPos[] placed = new BlockPos[1];
        UUID[] bossId = new UUID[1];
        List<List<BlockState>> floorBefore = new ArrayList<>();
        var script = new Script(h, () -> {
            if (placed[0] != null && level.getBlockState(placed[0]).is(Blocks.COBBLESTONE)) level.setBlock(placed[0], Blocks.AIR.defaultBlockState(), 3);
            JianSuiFight.reset(level, site);
            level.getServer().getPlayerList().remove(p);
        });
        script
            .run("放到残骸 12 格外", () -> put(p, level, far))
            .pause(20)
            .run("登记地标、清掉旧记录、记下插剑处的地砖", () -> {
                register(level, study.start());
                JianSuiFight.reset(level, site);
                floorBefore.add(floor(level, relic));
            })
            .run("走近太玄的雾底（scout）：开一份记录", () -> {
                h.assertTrue(TaixuanSite.at(level, p.blockPosition()) != null, "登记后还是找不到脚下的地标");
                JianSuiFight.scoutFor(p);
                h.assertTrue(JianSuiFight.state(level, site) == State.DORMANT, "scout 没有开记录：" + JianSuiFight.state(level, site));
            })
            .await("残骸放下", 220, () -> JianSuiFight.boss(level, site) != null, () -> {
                JianSui boss = JianSuiFight.boss(level, site);
                h.assertTrue(boss.phase() == 0 && boss.scripted(), "残骸不是第 0 阶段、过场");
                h.assertTrue(boss.position().distanceTo(plunge) < .2, "残骸不在插剑的地方：" + boss.position() + " / " + plunge);
                mark[0] = boss.flashTick();
            })
            .await("走近时残骸眼睛亮一下", 90, () -> JianSuiFight.boss(level, site).flashTick() != mark[0], () ->
                h.assertTrue(JianSuiFight.state(level, site) == State.DORMANT, "12 格外就醒了"))
            .run("走到 5 格内", () -> put(p, level, near))
            .await("醒过来登场", 30, () -> JianSuiFight.state(level, site) == State.ENTRANCE, () -> {})
            .await("登场完进第一阶段", 220, () -> JianSuiFight.state(level, site) == State.PHASE1, () -> {
                JianSui boss = JianSuiFight.boss(level, site);
                bossId[0] = boss.getUUID();
                h.assertTrue(boss.phase() == 1 && !boss.scripted() && boss.barShown(), "第一阶段没开打：" + boss.phase() + " " + boss.scripted());
                h.assertTrue(Math.abs(boss.getMaxHealth() - JianSui.HEALTH_ONE) < 1, "一个人时第一条血不是 450：" + boss.getMaxHealth());
                boss.setHealth(0);
                h.assertTrue(JianSuiFight.state(level, site) == State.SEPARATION && boss.isAlive() && boss.getHealth() >= 1, "第一条血打空没有转离体");
            })
            .await("离体完进第二阶段", 120, () -> JianSuiFight.state(level, site) == State.PHASE2, () -> {})
            .pause(1)
            .run("第二条血 550、补满，打空进裂地", () -> {
                JianSui boss = JianSuiFight.boss(level, site);
                h.assertTrue(boss.phase() == 2 && !boss.scripted(), "第二阶段没开打");
                h.assertTrue(Math.abs(boss.getMaxHealth() - JianSui.HEALTH_TWO) < 1 && boss.getHealth() > JianSui.HEALTH_TWO - 1, "第二条血不对：" + boss.getHealth() + "/" + boss.getMaxHealth());
                boss.setHealth(0);
                h.assertTrue(JianSuiFight.state(level, site) == State.RIFT, "第二条血打空没有转裂地");
            })
            .await("裂地完进第三阶段", 260, () -> JianSuiFight.state(level, site) == State.FALL, () -> {
                h.assertTrue(inCavity(p.position()) && p.position().distanceTo(start) < 3, "没有被送到起点碎块：" + p.position() + " / " + start);
                JianSui boss = JianSuiFight.boss(level, site);
                h.assertTrue(boss.phase() == 3 && boss.position().distanceTo(JianSuiFight.top(site)) < 2, "剑祟不在顶块上：" + boss.position());
                h.assertTrue(!level.getEntitiesOfClass(FallStage.class, new AABB(site.axis(FallStage.CENTER_Y), site.axis(FallStage.CENTER_Y)).inflate(4)).isEmpty(), "没有第三阶段的场景");
                // 站到起点碎块面上离正中两格的地方（送下来时在上方两格，假玩家不会自己落下去；正中是回去的裂缝，那里不留剑格）
                put(p, level, dodge);
                mark[1] = level.getGameTime();
            })
            .pause(2)
            .run("魔力设成 0", () -> h.assertTrue(PlayerMagic.of(p).mana == 0, "第三阶段魔力没有清零：" + PlayerMagic.of(p).mana))
            .await("第一柄巨剑起手（剑祟抬手指）后挪到正中", 60, () -> "point".equals(JianSuiFight.boss(level, site).move()), () -> {
                health[0] = p.getHealth();
                put(p, level, start);
            })
            .await("躲开的那柄扎在刚才站的地方，剑格那一格能踩", 40, () -> guardNear(level, dodge, 2), () -> {
                h.assertTrue(p.getHealth() == health[0], "躲开了还掉了血：" + p.getHealth() + " / " + health[0]);
                h.assertTrue(!level.getBlockState(BlockPos.containing(JianSuiFight.exitCrack(site))).is(JianSuiBlocks.SWORD_GUARD.get()), "剑格压在回去的裂缝上");
            })
            .await("站着不动挨下一柄巨剑", 160, () -> p.getHealth() < health[0], () -> p.setHealth(p.getMaxHealth()))
            .run("在起点碎块上放一块石头", () -> {
                BlockPos cell = BlockPos.containing(start).offset(1, 0, 0);
                if (!level.getBlockState(cell).isAir()) cell = BlockPos.containing(start).offset(-1, 0, 0);
                h.assertTrue(level.getBlockState(cell).isAir(), "起点碎块上没有空位放方块");
                level.setBlock(cell, Blocks.COBBLESTONE.defaultBlockState(), 3);
                NeoForge.EVENT_BUS.post(new BlockEvent.EntityPlaceEvent(BlockSnapshot.create(level.dimension(), level, cell), Blocks.STONE.defaultBlockState(), p));
                placed[0] = cell;
            })
            .await("放的方块被斩碎", 60, () -> level.getBlockState(placed[0]).isAir(), () -> {})
            .run("站到第一个落脚台上", () -> put(p, level, checkpoint))
            .await("记下到过第一个落脚台", 20, () -> JianSuiFight.reached(level, site, p) == 1, () -> {})
            .run("掉进虚空", () -> {
                health[0] = p.getHealth();
                put(p, level, new Vec3(checkpoint.x, -56, checkpoint.z));
            })
            .pause(2)
            .run("黑下去的这几刻落到洞底不摔伤", () -> {
                float before = p.getHealth();
                p.causeFallDamage(25, 1, level.damageSources().fall());
                h.assertTrue(p.getHealth() == before, "掉进虚空后落到洞底摔伤了：" + before + " → " + p.getHealth());
            })
            .await("黑一下送回第一个落脚台", 30, () -> p.position().distanceTo(checkpoint) < 1.5, () -> {
                float lost = health[0] - p.getHealth();
                h.assertTrue(lost > 0 && lost <= 4.01f && p.isAlive(), "掉进虚空的伤害不对：" + lost);
                p.setHealth(p.getMaxHealth());
            })
            .run("蹲在起点的裂缝上", () -> {
                h.assertTrue(!level.getBlockState(BlockPos.containing(JianSuiFight.exitCrack(site))).is(JianSuiBlocks.SWORD_GUARD.get()), "剑格压在回去的裂缝上");
                put(p, level, JianSuiFight.exitCrack(site));
                p.setShiftKeyDown(true);
            })
            .await("白一下回到上面", 80, () -> !inCavity(p.position()), () -> {
                p.setShiftKeyDown(false);
                h.assertTrue(p.position().distanceTo(near) < 1.5, "没有回到下去前的位置：" + p.position() + " / " + near);
            })
            .run("踩石台上的裂缝", () -> put(p, level, ground(level, plunge, plunge.y)))
            .await("被吸下去", 40, () -> inCavity(p.position()), () ->
                h.assertTrue(p.position().distanceTo(start) < 3, "后来的人没有被送到起点碎块：" + p.position()))
            .run("再蹲在起点的裂缝上（下来前站在裂缝上）", () -> {
                put(p, level, JianSuiFight.exitCrack(site));
                p.setShiftKeyDown(true);
            })
            .await("回到上面时避开裂缝", 80, () -> !inCavity(p.position()), () -> {
                p.setShiftKeyDown(false);
                h.assertTrue(flat(p.position(), plunge) >= 3, "回去的位置落在裂缝上：" + p.position() + " / " + plunge);
            })
            .pause(30)
            .run("没再被吸下去；再踩一次裂缝", () -> {
                h.assertTrue(!inCavity(p.position()), "避开裂缝后又被吸下去了");
                put(p, level, ground(level, plunge, plunge.y));
            })
            .await("又被吸下去", 40, () -> inCavity(p.position()), () -> put(p, level, dodge))
            // 终结时要确实有一格剑格立着，才看得出收尾时把它拆了
            .await("又一柄巨剑起手，挪开", 160, () -> "point".equals(JianSuiFight.boss(level, site).move()), () -> put(p, level, start))
            .await("剑格立着", 60, () -> guardNear(level, dodge, 2), () -> {})
            .run("近战打中剑祟", () -> {
                JianSui boss = JianSuiFight.boss(level, site);
                put(p, level, boss.position().add(1.2, 0, 0));
                boss.hurtServer(level, level.damageSources().playerAttack(p), 1);
                h.assertTrue(JianSuiFight.state(level, site) == State.FINISHER, "近战打中没有进终结");
                h.assertTrue(boss.isAlive(), "终结一开始剑祟就没了");
                hitAt[0] = level.getGameTime();
            })
            .await("黑白「飞身刺」定格里换到上面", JianSuiFight.FIN_SWAP + 4, () -> !inCavity(p.position()), () -> {
                long t = level.getGameTime() - hitAt[0];
                h.assertTrue(t == JianSuiFight.FIN_SWAP, "换场景不在黑白定格里：打中后第 " + t + " 刻");
                JianSui boss = JianSuiFight.boss(level, site);
                h.assertTrue(boss != null && boss.position().distanceTo(plunge) < .2, "换场景后剑祟不在插剑的地方");
                h.assertTrue(JianSuiFight.state(level, site) == State.FINISHER, "换场景时已经不在终结里了");
            })
            .await("不按也自己撬完、遗剑落下、打败", 220, () -> JianSuiFight.state(level, site) == State.DEFEATED, () -> {
                long t = level.getGameTime() - hitAt[0];
                int auto = JianSuiFight.PRY_AUTO[2] + JianSuiFight.BURST_AFTER + JianSuiFight.GONE_AFTER;
                h.assertTrue(t == auto, "不按的时候没有按时自己撬完：打中后第 " + t + " 刻收尾，应在第 " + auto + " 刻");
                h.assertTrue(level.getBlockState(relic).is(JianSuiBlocks.AXIAL_SWORD.get()), "遗剑没有插在插剑的地方");
                h.assertTrue(level.getEntity(bossId[0]) == null, "打败后剑祟的实体还在");
                h.assertTrue(!inCavity(p.position()), "终结后人还在空洞里：" + p.position());
                double front = flat(p.position(), plunge);
                h.assertTrue(front > 1.2 && front < 2.0, "打中它的人没有站在它面前：离插剑处 " + front);
                Vec3 toBoss = plunge.subtract(p.position()).multiply(1, 0, 1);
                h.assertTrue(p.getLookAngle().multiply(1, 0, 1).dot(toBoss) > 0, "打中它的人背对着它");
                h.assertTrue(level.getEntitiesOfClass(FallStage.class, new AABB(site.axis(FallStage.CENTER_Y), site.axis(FallStage.CENTER_Y)).inflate(40)).isEmpty(), "第三阶段的场景没收");
                h.assertTrue(!guardNear(level, start, 3), "第三阶段结束后剑格还留着");
                h.assertTrue(!floor(level, relic).equals(floorBefore.getFirst()), "落剑的地方没有留疤");
            })
            .run("重置收走遗剑、还原留下的疤", () -> {
                JianSuiFight.reset(level, site);
                h.assertTrue(!level.getBlockState(relic).is(JianSuiBlocks.AXIAL_SWORD.get()), "重置后遗剑还在");
                h.assertTrue(JianSuiFight.state(level, site) == null, "重置后记录还在");
                h.assertTrue(floor(level, relic).equals(floorBefore.getFirst()), "重置后疤没有还原");
            });
        h.onEachTick(script::tick);
    }
}
