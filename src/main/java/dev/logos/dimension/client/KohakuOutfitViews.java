package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.companion.Companions;
import dev.logos.dimension.companion.KohakuCompanion;
import dev.logos.dimension.companion.KohakuOutfit;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * 实际客户端检查琥珀换衣服（docs/KOHAKU_COMPANION.md 第 19 节），也在 {@code kohaku_companion} 一组。{@code kohaku_outfit}：对话里「换身衣服吧」，
 * 四套挨个换（穿着的那一套打勾；再选同一套她说已经穿着了），换完收回再叫出来还是那一套，角色面板里也是那一套。
 */
public abstract class KohakuOutfitViews extends KohakuViews {
    static final Vec3 TALK_EYE = new Vec3(.5, FLOOR + 2.62, -1.9), AT = new Vec3(.5, FLOOR + 1, 1.2);

    @LDLRegisterClient(name = "kohaku_outfit", group = "kohaku_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Outfit extends KohakuOutfitViews {
        private KohakuCompanion c;

        private void talk(ServerPlayer p) {
            p.setShiftKeyDown(true);
            p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            c.interact(p, InteractionHand.MAIN_HAND, Vec3.ZERO);
            p.setShiftKeyDown(false);
        }

        private static boolean wearing(ServerPlayer p, KohakuCompanion c, KohakuOutfit o) {
            return c.outfit() == o && p.getData(Companions.KOHAKU_DAYS).outfit().equals(o.id);
        }

        @Override
        public void define(ScenarioBuilder s) {
            ScenarioBuilder b = kohakuStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("站好、召唤", ctx -> {
                        aim(ctx.player(), TALK_EYE, AT.add(0, .9, 0));
                        c = summonKohaku(ctx.player(), AT);
                    })
                    .step("对准", ctx -> aimClient(AT.add(0, .9, 0)))
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .checkServer("平时穿开衫制服", ctx -> c.outfit() == KohakuOutfit.CARDIGAN)
                    .server("潜行右键她", ctx -> talk(ctx.player()))
                    .waitUntil("对话打开", ctx -> CompanionTalkViews.talk() != null)
                    .step("聊天", ctx -> {
                        CompanionTalkViews.talk().skip();
                        CompanionTalkViews.talk().choose("chat");
                    }).ticks(4)
                    .step("换身衣服吧", ctx -> CompanionTalkViews.talk().choose("chat.outfit")).ticks(6)
                    .screenshot("kohaku_outfit_menu");
            for (KohakuOutfit o : new KohakuOutfit[] {KohakuOutfit.DAISY, KohakuOutfit.VELVET, KohakuOutfit.SWIMSUIT}) {
                b = b.step("换上 " + o.id, ctx -> {
                            CompanionTalkViews.talk().skip();
                            CompanionTalkViews.talk().choose("outfit." + o.id);
                        })
                        .waitUntil("她说了换上 " + o.id + " 的那句", ctx -> CompanionTalkViews.said("outfit_" + o.id))
                        .checkServer("穿上了，记在你身上", ctx -> wearing(ctx.player(), c, o))
                        .step("字出完", ctx -> CompanionTalkViews.talk().skip()).ticks(12)
                        .screenshot("kohaku_outfit_" + o.id);
            }
            b.step("再选泳装", ctx -> {
                        CompanionTalkViews.talk().skip();
                        CompanionTalkViews.talk().choose("outfit.swimsuit");
                    })
                    .waitUntil("她说已经穿着了", ctx -> CompanionTalkViews.said("outfit_same"))
                    .step("关掉对话", ctx -> Minecraft.getInstance().setScreen(null)).ticks(4)
                    .server("收回", ctx -> Companions.dismiss(ctx.player(), Companions.KOHAKU_DEF))
                    .waitUntilServer("她走了", ctx -> c.isRemoved() && Companions.kohaku(ctx.player()) == null)
                    .server("再叫出来", ctx -> c = summonKohaku(ctx.player(), AT))
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .checkServer("还是泳装", ctx -> c.outfit() == KohakuOutfit.SWIMSUIT)
                    .server("潜行右键她", ctx -> talk(ctx.player()))
                    .waitUntil("对话打开", ctx -> CompanionTalkViews.talk() != null)
                    .step("详细数据", ctx -> {
                        CompanionTalkViews.talk().skip();
                        CompanionTalkViews.talk().choose("stats");
                    })
                    .waitUntil("面板打开", ctx -> CompanionTalkViews.panel() != null).ticks(10)
                    .screenshot("kohaku_outfit_panel")
                    .step("关掉", ctx -> Minecraft.getInstance().setScreen(null)).ticks(4);
        }
    }
}
