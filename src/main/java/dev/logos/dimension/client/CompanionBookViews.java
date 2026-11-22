package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.client.companion.CompanionBookClient;
import dev.logos.dimension.client.companion.CompanionBookScreen;
import dev.logos.dimension.companion.CompanionBook;
import dev.logos.dimension.companion.CompanionDef;
import dev.logos.dimension.companion.Companions;
import dev.logos.dimension.companion.KohakuCompanion;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;

/**
 * 实际客户端检查友人帐（{@link CompanionBook}，docs/COMPANIONS.md 第 7 节），一组 {@code companion_book}。{@code companion_book}：
 * 只和琥珀立了契时另外两页是黑窗；在友人帐里叫她出来、再打开是「收回」、收回；三位都立了契；
 * 琥珀撤退以后那一页写着还要歇几秒、按了不算。
 */
public abstract class CompanionBookViews extends KohakuViews {
    static @Nullable CompanionBookScreen book() {
        return CompanionBookScreen.current();
    }

    static CompanionBook.@Nullable Page page(String id) {
        CompanionBookScreen b = book();
        if (b == null) return null;
        for (CompanionBook.Page p : b.pages()) if (p.id().equals(id)) return p;
        return null;
    }

    /** 三位都清掉：契、在外面的。 */
    static void clearAll(ServerPlayer p) {
        for (CompanionDef d : Companions.ALL) {
            Companions.dismiss(p, d);
            p.setData(d.bond().get(), Companions.Bond.NONE);
        }
    }

    @LDLRegisterClient(name = "companion_book", group = "companion_book", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Book extends CompanionBookViews {
        private KohakuCompanion c;

        @Override
        public void define(ScenarioBuilder s) {
            kohakuStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("三位都没立契；只和琥珀立契", ctx -> {
                        ServerPlayer p = ctx.player();
                        clearAll(p);
                        Companions.grantPact(p, Companions.KOHAKU_DEF);
                    })
                    .step("按 K（翻开友人帐）", ctx -> CompanionBookClient.open())
                    .waitUntil("友人帐翻开了", ctx -> book() != null).ticks(10)
                    .check("琥珀那一页立过契；白梅、精灵公主是黑窗", ctx -> {
                        var k = page("kohaku");
                        return k != null && k.pact() && !k.out() && !page("shiraume").pact()
                                && !page("elf_princess").pact();
                    })
                    .screenshot("companion_book_one")
                    .step("按琥珀那一页的「召唤」", ctx -> book().press("kohaku"))
                    .waitUntil("友人帐合上", ctx -> book() == null)
                    .waitUntilServer("她出来了", ctx -> (c = Companions.kohaku(ctx.player())) != null)
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .step("再翻开", ctx -> CompanionBookClient.open())
                    .waitUntil("友人帐翻开了", ctx -> book() != null).ticks(6)
                    .check("这一页写着她在你身边、按钮是收回", ctx -> page("kohaku").out())
                    .screenshot("companion_book_out")
                    .step("按「收回」", ctx -> book().press("kohaku"))
                    .waitUntil("友人帐合上", ctx -> book() == null)
                    .waitUntilServer("她回去了", ctx -> Companions.kohaku(ctx.player()) == null)
                    .server("三位都立了契", ctx -> {
                        Companions.grantPact(ctx.player(), Companions.SHIRAUME_DEF);
                        Companions.grantPact(ctx.player(), Companions.PRINCESS_DEF);
                    })
                    .step("再翻开", ctx -> CompanionBookClient.open())
                    .waitUntil("友人帐翻开了", ctx -> book() != null).ticks(10)
                    .check("三页都画着本人", ctx -> page("shiraume").pact() && page("elf_princess").pact() && page("kohaku").pact())
                    .screenshot("companion_book_all")
                    .step("合上", ctx -> book().onClose())
                    .server("叫琥珀出来再让她撤退", ctx -> c = summonKohaku(ctx.player(), ctx.player().position().add(1.5, 0, 1.5)))
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("撤退", ctx -> c.withdrawForTest())
                    .waitUntilServer("被法阵卷走了，歇着", ctx -> Companions.kohaku(ctx.player()) == null && Companions.restLeft(ctx.player(),
                            Companions.KOHAKU_DEF) > 0)
                    .step("再翻开", ctx -> CompanionBookClient.open())
                    .waitUntil("友人帐翻开了", ctx -> book() != null).ticks(6)
                    .check("这一页写着还要歇几秒", ctx -> page("kohaku").rest() > 0)
                    .screenshot("companion_book_resting")
                    .step("按了也不算", ctx -> book().press("kohaku")).ticks(20)
                    .checkServer("她没出来", ctx -> Companions.kohaku(ctx.player()) == null)
                    .step("合上", ctx -> book().onClose())
                    .teardown("清掉", ctx -> ctx.server().execute(() -> {
                        for (ServerPlayer p : ctx.server().getPlayerList().getPlayers()) {
                            clearAll(p);
                            Companions.wake(p, Companions.KOHAKU_DEF);
                        }
                    }));
        }
    }
}
