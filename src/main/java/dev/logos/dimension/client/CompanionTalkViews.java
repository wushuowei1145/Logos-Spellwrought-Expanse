package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.client.companion.CompanionPanelScreen;
import dev.logos.dimension.client.companion.CompanionTalkScreen;
import dev.logos.dimension.companion.CompanionBlade;
import dev.logos.dimension.companion.CompanionLines;
import dev.logos.dimension.companion.CompanionMind;
import dev.logos.dimension.companion.CompanionTactic;
import dev.logos.dimension.companion.CompanionTalk;
import dev.logos.dimension.companion.Companions;
import dev.logos.dimension.companion.KatashiroItem;
import dev.logos.dimension.companion.ShiraumeCompanion;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 实际客户端检查和白梅说话、角色面板、行动策略（docs/SHIRAUME_COMPANION.md 第 8.13 节），同一组 {@code shiraume_companion}：
 * <ul>
 * <li>{@code companion_talk}：潜行右键她打开对话；聊天、作战策略（改成优先远程、留在原地再跟上）、详细数据（三页，盖了契印以后尾尖亮、
 *     形代替身解锁、往事多了）、看包袱再回来、形代丢了拿纸再折一张、一会再聊；再说话时来了敌人，话被打断；
 * <li>{@code companion_tactics}：不参与攻击时不打主人在打的尸壳、躲开；优先远程时尸壳贴着也不拔刀，放狐火；优先近战时七格外就凝刀走过去砍。
 * </ul>
 */
public abstract class CompanionTalkViews extends CompanionViews {
    static @Nullable CompanionTalkScreen talk() {
        return Minecraft.getInstance().screen instanceof CompanionTalkScreen t ? t : null;
    }

    static @Nullable CompanionPanelScreen panel() {
        return Minecraft.getInstance().screen instanceof CompanionPanelScreen p ? p : null;
    }

    /** 主人潜行右键她（空手）。 */
    static void sneakClick(ServerPlayer p, ShiraumeCompanion c) {
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        p.setShiftKeyDown(true);
        c.interact(p, InteractionHand.MAIN_HAND, Vec3.ZERO);
        p.setShiftKeyDown(false);
    }

    static boolean said(String id) {
        CompanionTalkScreen t = talk();
        return t != null && t.lineKey() != null && t.lineKey().endsWith(".talk." + id);
    }

    @LDLRegisterClient(name = "companion_talk", group = "shiraume_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Talk extends CompanionTalkViews {
        // 站在地上：teleport 的浮点误差会让脚底陷进地面 1e-9（以前狐火灯因此当成头顶有方块，露天也点灯）
        private static final Vec3 EYE = new Vec3(.5, FLOOR + 2.62, -1.9), AT = new Vec3(.5, FLOOR + 1, 1.2);
        private ShiraumeCompanion c;
        private Mob husk;
        private int guiScale;
        private java.util.concurrent.CompletableFuture<Void> reload = java.util.concurrent.CompletableFuture.completedFuture(null);

        /** 维护者玩的是中文：对话和面板用中文拍（测试客户端默认英文）。 */
        private void language(String code) {
            var mc = Minecraft.getInstance();
            mc.getLanguageManager().setSelected(code);
            mc.options.languageCode = code;
            reload = mc.reloadResourcePacks();
        }

        @Override
        public void define(ScenarioBuilder s) {
            s.step("切成中文", ctx -> language("zh_cn")).waitUntil("资源重载完", ctx -> reload.isDone());
            stage(s, GameType.SURVIVAL, 60).runCommand("difficulty peaceful")
                    .server("站好、召唤", ctx -> {
                        ServerPlayer p = ctx.player();
                        p.getInventory().clearContent();
                        aim(p, EYE, AT.add(0, .9, 0));
                        c = summonAt(p, AT);
                        var level = ctx.server().overworld();
                        System.out.println("[companion] talk: heightmap over player " + level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                                p.getBlockX(), p.getBlockZ()) + " player y " + p.getBlockY());
                    })
                    .step("对准", ctx -> aimClient(AT.add(0, .9, 0)))
                    .waitUntilServer("入场播完", ctx -> !"summon".equals(c.npcMiscAnimation()))
                    .server("潜行右键她", ctx -> sneakClick(ctx.player(), c))
                    .waitUntil("对话打开", ctx -> talk() != null)
                    .check("开口第一句是招呼", ctx -> talk().lineKey() != null && talk().lineKey().contains(".talk.greet_"))
                    .check("最上面一层：聊天、包袱、作战策略、详细数据、一会再聊",
                            ctx -> talk().options().equals(List.of("chat", "pouch", "tactic", "stats", "bye")))
                    .ticks(30)
                    .checkServer("她站住、看着主人", ctx -> c.talking() && c.mind().current() == CompanionMind.Kind.TALK)
                    .checkServer("露天不点狐火灯（主人脚底差一点点陷进地面也算露天）", ctx -> !c.lanternOn())
                    .step("字出完", ctx -> talk().skip()).ticks(6)
                    .check("说完了才出选项", ctx -> talk().ready())
                    .screenshot("companion_talk_open")
                    // 聊天
                    .step("聊天", ctx -> talk().choose("chat")).ticks(6)
                    .check("聊天这一层", ctx -> talk().options().equals(List.of("chat.katashiro", "chat.food", "chat.past", "chat.tails", "chat.us",
                            "chat.outfit", "chat.rest", "back")))
                    .screenshot("companion_talk_chat")
                    .step("你喜欢吃什么？", ctx -> talk().choose("chat.food"))
                    .waitUntil("她回话", ctx -> said("food_1"))
                    .ticks(20)
                    .screenshot("companion_talk_food_typing")
                    .step("跳到最后一句", ctx -> talk().skip()).ticks(6)
                    .check("翻到第二句", ctx -> said("food_2"))
                    .screenshot("companion_talk_food")
                    // 作战策略
                    .step("返回 → 作战策略", ctx -> {
                        talk().choose("back");
                        talk().choose("tactic");
                    }).ticks(6)
                    .check("作战策略这一层", ctx -> talk().options().equals(List.of("move.follow", "move.stay", "plan", "back")))
                    .screenshot("companion_talk_tactic")
                    .step("修改行动策略", ctx -> talk().choose("plan")).ticks(6)
                    .screenshot("companion_talk_plan")
                    .step("优先远程", ctx -> talk().choose("tactic.ranged"))
                    .waitUntilServer("契上记下了优先远程", ctx -> ctx.player().getData(Companions.BOND).tacticOf() == CompanionTactic.RANGED)
                    .waitUntil("她回话，客户端的档案也打上勾", ctx -> said("tactic_ranged") && talk().sheet().tactic().equals("ranged"))
                    .step("字出完", ctx -> talk().skip()).ticks(6)
                    .screenshot("companion_talk_ranged")
                    .step("返回 → 留在原地", ctx -> {
                        talk().choose("back");
                        talk().choose("move.stay");
                    })
                    .waitUntilServer("她留在原地", ctx -> c.staying())
                    .waitUntil("档案也是留在原地", ctx -> talk().sheet().staying())
                    .step("跟上", ctx -> talk().choose("move.follow"))
                    .waitUntilServer("又跟上了", ctx -> !c.staying())
                    // 详细数据
                    .step("返回 → 详细数据", ctx -> {
                        talk().skip();
                        talk().choose("back");
                        talk().choose("stats");
                    })
                    .waitUntil("角色面板打开", ctx -> panel() != null)
                    .ticks(20)
                    .check("面板上的数：生命 46、攻击 5.8（主人没穿装备，继承三成）", ctx -> {
                        var sheet = panel().parent().sheet();
                        return Math.abs(sheet.stat("health").value() - 46) < .01 && Math.abs(sheet.stat("attack").value() - 5.8) < .01;
                    })
                    .screenshot("companion_panel_stats")
                    .step("契印 · 能力", ctx -> panel().tab(CompanionPanelScreen.Tab.SKILLS)).ticks(4)
                    .screenshot("companion_panel_skills")
                    .server("盖三枚契印", ctx -> {
                        for (int i = 0; i < 3; i++) Companions.addSeal(ctx.player());
                    })
                    .waitUntil("面板上三条尾尖亮了，形代替身解锁", ctx -> panel() != null && panel().parent().sheet().seals() == 3
                            && panel().parent().sheet().skills().stream().anyMatch(k -> k.id().equals("katashiro") && k.unlocked()))
                    .step("选形代替身", ctx -> {
                        var skills = panel().parent().sheet().skills();
                        for (int i = 0; i < skills.size(); i++) if (skills.get(i).id().equals("katashiro")) panel().selectSkill(i);
                    }).ticks(4)
                    .screenshot("companion_panel_skills_sealed")
                    .step("选下一枚契印的 ？？？", ctx -> {
                        var skills = panel().parent().sheet().skills();
                        for (int i = 0; i < skills.size(); i++) if (skills.get(i).id().equals("sealed")) panel().selectSkill(i);
                    }).ticks(4)
                    .screenshot("companion_panel_skills_locked")
                    .step("往事", ctx -> panel().tab(CompanionPanelScreen.Tab.MEMORIES)).ticks(4)
                    .check("想起了四段", ctx -> panel().parent().sheet().memories() == 4)
                    .screenshot("companion_panel_memories")
                    .step("选第六段（还没想起来）", ctx -> panel().selectMemory(6)).ticks(4)
                    .screenshot("companion_panel_memories_locked")
                    // 界面放大到 4 倍（1600×900 时只有 400×225，最挤的时候）也要排得下
                    .step("界面放大到 4 倍", ctx -> {
                        var mc = Minecraft.getInstance();
                        guiScale = mc.options.guiScale().get();
                        mc.options.guiScale().set(4);
                        mc.resizeGui();
                        panel().tab(CompanionPanelScreen.Tab.STATS);
                    }).ticks(4)
                    .screenshot("companion_panel_stats_gui4")
                    .step("契印 · 能力", ctx -> panel().tab(CompanionPanelScreen.Tab.SKILLS)).ticks(4)
                    .screenshot("companion_panel_skills_gui4")
                    .step("往事", ctx -> panel().tab(CompanionPanelScreen.Tab.MEMORIES)).ticks(4)
                    .screenshot("companion_panel_memories_gui4")
                    .step("回到对话", ctx -> panel().onClose()).ticks(6)
                    .step("字出完", ctx -> talk().skip()).ticks(4)
                    .screenshot("companion_talk_gui4")
                    .step("界面放回", ctx -> {
                        var mc = Minecraft.getInstance();
                        mc.options.guiScale().set(guiScale);
                        mc.resizeGui();
                        talk().choose("stats");
                    })
                    .waitUntil("面板又打开", ctx -> panel() != null)
                    .step("Esc 回到对话", ctx -> panel().onClose())
                    .waitUntil("回到对话", ctx -> talk() != null)
                    .checkServer("还在说话", ctx -> CompanionTalk.talking(ctx.player()) && c.talking())
                    // 包袱
                    .step("让我看看你的包袱", ctx -> talk().choose("pouch"))
                    .waitUntilServer("打开了包袱", ctx -> ctx.player().containerMenu instanceof ChestMenu)
                    .checkServer("看包袱时对话没断", ctx -> CompanionTalk.talking(ctx.player()))
                    .ticks(10)
                    .server("关上包袱", ctx -> ctx.player().closeContainer())
                    .waitUntil("回到对话，她问你看完了没有", ctx -> said("pouch_back"))
                    // 形代丢了
                    .server("形代丢了，身上有一张纸", ctx -> {
                        ctx.player().getInventory().clearContent();
                        ctx.player().getInventory().add(new ItemStack(Items.PAPER));
                    })
                    .step("聊天 → 我弄丢了一开始的形代", ctx -> {
                        talk().skip();
                        talk().choose("chat");
                        talk().choose("chat.katashiro");
                    })
                    .waitUntilServer("她用那张纸折了一张写着名字的形代", ctx -> KatashiroItem.carries(ctx.player()))
                    .checkServer("纸用掉了", ctx -> !ctx.player().getInventory().contains(new ItemStack(Items.PAPER)))
                    .waitUntil("她说这次收好", ctx -> {
                        if (talk() != null) talk().skip();
                        return said("katashiro_given");
                    })
                    .step("再问一次", ctx -> talk().choose("chat.katashiro"))
                    .waitUntil("形代就在身上", ctx -> said("katashiro_have"))
                    // 一会再聊
                    .step("返回 → 一会再聊", ctx -> {
                        talk().skip();
                        talk().choose("back");
                        talk().choose("bye");
                    })
                    .waitUntil("对话关了", ctx -> Minecraft.getInstance().screen == null)
                    .waitUntilServer("她不说了，挥手道别", ctx -> !c.talking() && !CompanionTalk.talking(ctx.player())
                            && CompanionLines.last().endsWith(".talk.bye"))
                    // 来了敌人
                    .server("再和她说话", ctx -> sneakClick(ctx.player(), c))
                    .waitUntil("对话打开", ctx -> talk() != null)
                    .runCommand("difficulty normal")
                    .server("一只尸壳冲主人来", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        husk = CompanionLifeViews.spawn(level, EntityType.HUSK, new Vec3(5.5, FLOOR + 1, 4.5), 135);
                        husk.setTarget(ctx.player());
                    })
                    .waitUntil("话被打断，对话关了", ctx -> Minecraft.getInstance().screen == null)
                    .checkServer("她说有东西过来了", ctx -> CompanionLines.last().endsWith(".talk.danger") && !c.talking())
                    .server("收拾掉尸壳", ctx -> husk.discard())
                    .teardown("恢复界面、切回英文", ctx -> {
                        if (Minecraft.getInstance().screen != null) Minecraft.getInstance().setScreen(null);
                        Minecraft.getInstance().options.hideGui = false;
                        language("en_us");
                    });
        }
    }

    /**
     * 行动策略：一只不动的尸壳（冲着主人，算她要对付的）放在她身边。不参与攻击时她不打、往主人那边躲；优先远程时贴着两格半也不拔刀，
     * 退开放狐火；优先近战时七格外就凝刀走过去，一发狐火都不放。
     */
    @LDLRegisterClient(name = "companion_tactics", group = "shiraume_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Tactics extends CompanionTalkViews {
        private static final Vec3 EYE = new Vec3(-2, FLOOR + 4.6, -1.5), LOOK = new Vec3(.5, FLOOR + 1, 2.5);
        private ShiraumeCompanion c;
        private Mob husk;
        private int casts, landed;
        private float health;
        private double start;
        private boolean drawn, cast;

        private void tactic(ServerPlayer p, CompanionTactic t) {
            p.setData(Companions.BOND, p.getData(Companions.BOND).withTactic(t));
        }

        /** 她身边 {@code dz} 格一只不动的尸壳，冲着主人。 */
        private void husk(ServerLevel level, ServerPlayer p, double dz) {
            c.getNavigation().stop();
            husk = CompanionLifeViews.spawn(level, EntityType.HUSK, new Vec3(c.getX(), FLOOR + 1, c.getZ() + dz), 180);
            husk.setNoAi(true);
            husk.setTarget(p);
            health = husk.getHealth();
            casts = ShiraumeCompanion.casts();
            landed = CompanionBlade.landed();
            start = c.distanceTo(husk);
            drawn = false;
            cast = false;
        }

        private boolean trace(String what) {
            if (c.tickCount % 20 == 0 && c.mind() != null) {
                System.out.println("[companion] " + what + " t=" + c.tickCount + " d=" + String.format("%.2f", c.distanceTo(husk)) + " husk=" + husk.getHealth()
                        + " casts=" + (ShiraumeCompanion.casts() - casts) + " " + c.mind().describe() + " | " + CompanionBlade.tally());
            }
            drawn |= c.blade().drawn();
            cast |= ShiraumeCompanion.casts() > casts;
            return true;
        }

        @Override
        public void define(ScenarioBuilder s) {
            stage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("主人站上石台", ctx -> CompanionLifeViews.onPillar(ctx.server().overworld(), ctx.player(), EYE, LOOK)).ticks(10)
                    .step("对准", ctx -> aimClient(LOOK))
                    .server("召唤", ctx -> c = summonAt(ctx.player(), new Vec3(.5, FLOOR + 1, .5)))
                    .waitUntilServer("入场播完", ctx -> !"summon".equals(c.npcMiscAnimation()))
                    // 不参与攻击
                    .server("不参与攻击；她身边两格半一只尸壳", ctx -> {
                        tactic(ctx.player(), CompanionTactic.PASSIVE);
                        husk(ctx.server().overworld(), ctx.player(), 2.6);
                    })
                    .waitUntilServer("过四秒", ctx -> trace("passive") && c.tickCount % 80 == 0 && c.distanceTo(husk) > start + 1)
                    .ticks(40)
                    .screenshot("companion_tactic_passive")
                    .checkServer("没打它，往后躲开了", ctx -> {
                        System.out.println("[companion] passive: husk " + husk.getHealth() + "/" + health + " d " + c.distanceTo(husk) + " from " + start
                                + " casts " + (ShiraumeCompanion.casts() - casts) + " landed " + (CompanionBlade.landed() - landed));
                        return husk.getHealth() >= health && ShiraumeCompanion.casts() == casts && CompanionBlade.landed() == landed
                                && c.distanceTo(husk) > start + 1;
                    })
                    .server("清掉，她回到台子中间", ctx -> {
                        husk.discard();
                        c.teleportTo(.5, FLOOR + 1, .5);
                    }).ticks(20)
                    // 优先远程
                    .server("优先远程；尸壳在她身边两格半", ctx -> {
                        tactic(ctx.player(), CompanionTactic.RANGED);
                        husk(ctx.server().overworld(), ctx.player(), 2.5);
                    })
                    .waitUntilServer("放了两发狐火", ctx -> trace("ranged") && ShiraumeCompanion.casts() - casts >= 2)
                    .screenshot("companion_tactic_ranged")
                    .checkServer("没拔刀，尸壳挨了狐火", ctx -> {
                        System.out.println("[companion] ranged: husk " + husk.getHealth() + "/" + health + " drawn " + drawn);
                        return !drawn && husk.getHealth() < health;
                    })
                    .server("清掉，她回到台子中间", ctx -> {
                        husk.discard();
                        c.teleportTo(.5, FLOOR + 1, .5);
                    }).ticks(20)
                    // 优先近战
                    .server("优先近战；尸壳在她七格外", ctx -> {
                        tactic(ctx.player(), CompanionTactic.MELEE);
                        husk(ctx.server().overworld(), ctx.player(), 7);
                    })
                    .waitUntilServer("凝刀走过去砍中", ctx -> trace("melee") && CompanionBlade.landed() - landed >= 1)
                    .screenshot("companion_tactic_melee")
                    .checkServer("一发狐火都没放", ctx -> {
                        System.out.println("[companion] melee tactic: husk " + husk.getHealth() + "/" + health + " cast " + cast);
                        return !cast;
                    })
                    .server("清掉", ctx -> husk.discard())
                    .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
        }
    }
}
