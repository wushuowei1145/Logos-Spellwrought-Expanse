package dev.logos.dimension.magic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import dev.logos.lang.Compiler;
import dev.logos.lang.LogosError;
import dev.logos.lang.Spell;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.attachment.AttachmentType;
import org.jspecify.annotations.Nullable;

/**
 * 术式里藏着的元素词（狐火的 {@code foxfire}、龙炎的 {@code dragonfire}，docs/FOXFIRE.md 第 4 节、docs/DRAGONFIRE.md）。
 * 编译器把它当奥秘读，并按源码记下这条术式用过它（Logos 也按源码缓存编译结果）。
 * <ul>
 *   <li>玩家自己写术式的地方（编纂台、书与笔、发射器、书灵）走 {@link #authoring}：没学会的词和写错的词一样报错。</li>
 *   <li>学会了（{@link #teach}，存在玩家身上，死了也记得）就和别的元素一样认得，编纂台的卡片库里也列出来。</li>
 *   <li>调试命令和附属自己的生物不走 {@link #authoring}，一直认得。</li>
 * </ul>
 * 注入 Logos 的地方（{@code mixin/HiddenWord*Mixin}、{@code Foxfire*Mixin}）对所有藏着的词一视同仁，新词只要在这里登记。
 */
public final class HiddenWord {
    private static final List<HiddenWord> ALL = new CopyOnWriteArrayList<>();

    /** 术式里写的词。 */
    public final String word;
    /** 书灵的「搭档学会了什么」里，这个词的键（同 Logos 的 {@code element:…}）。 */
    public final String unlock;
    private final Supplier<AttachmentType<Boolean>> known;
    /** 客户端：本机的玩家学会了没有（客户端启动时指过去；服务端一直是假）。 */
    public BooleanSupplier viewerKnows = () -> false;
    /** 大于 0 时正在编译玩家自己写的、不认得这个词的术式。 */
    private final ThreadLocal<int[]> authoring = ThreadLocal.withInitial(() -> new int[1]);
    /** 编译时用过这个词的术式，按源码记。 */
    private final Set<String> sources = Collections.synchronizedSet(Collections.newSetFromMap(new LinkedHashMap<>(16, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 512;
        }
    }));

    private HiddenWord(String word, Supplier<AttachmentType<Boolean>> known) {
        this.word = word;
        this.unlock = "element:" + word;
        this.known = known;
    }

    /** 登记一个藏着的词；{@code known} 是「学会了」的附着数据（布尔，死了也记得，只同步给本人）。 */
    public static HiddenWord register(String word, Supplier<AttachmentType<Boolean>> known) {
        HiddenWord w = new HiddenWord(word, known);
        ALL.add(w);
        return w;
    }

    public static List<HiddenWord> all() {
        return ALL;
    }

    // ---------------------------------------------------------------- 编译

    /** 编译器碰到一个不认识的元素词时问：它是不是这个词（玩家写的、没学会时不是）。 */
    public boolean accepts(String w) {
        return word.equals(w) && authoring.get()[0] == 0;
    }

    /** 编译完一条用过这个词的术式。 */
    public void compiled(@Nullable Spell spell) {
        if (spell != null) sources.add(spell.source());
    }

    /** 这条术式用过这个词。 */
    public boolean is(@Nullable Spell spell) {
        return spell != null && sources.contains(spell.source());
    }

    // ---------------------------------------------------------------- 学会

    public boolean knows(@Nullable Entity p) {
        return p instanceof Player player && player.getData(known);
    }

    /** 教 {@code p} 这个词；已经会了返回假。 */
    public boolean teach(ServerPlayer p) {
        if (knows(p)) return false;
        p.setData(known, true);
        p.syncData(known);
        return true;
    }

    /** 忘掉这个词（测试和命令用）。 */
    public void forget(ServerPlayer p) {
        p.setData(known, false);
        p.syncData(known);
    }

    // ---------------------------------------------------------------- 玩家写术式

    /** 编译玩家自己写的术式：这期间这个词是不认识的词（学会了的照常认得）。 */
    public <T> T authoring(boolean knows, Supplier<T> compile) {
        if (knows) return compile.get();
        int[] depth = authoring.get();
        depth[0]++;
        try {
            return compile.get();
        } finally {
            depth[0]--;
        }
    }

    /** 编译玩家自己写的术式：{@code knows} 回答他学会了哪些词，其余的都是不认识的词。 */
    public static <T> T authoring(Predicate<HiddenWord> knows, Supplier<T> compile) {
        Supplier<T> body = compile;
        for (HiddenWord w : ALL) {
            Supplier<T> inner = body;
            boolean k = knows.test(w);
            body = () -> w.authoring(k, inner);
        }
        return body.get();
    }

    /**
     * 同上，用于走 Logos 编译缓存的地方：缓存按源码记，里面可能是调试时编过、用了没学会的词的术式。
     * 那就按玩家写的重编一次，报出和写错一个词一样的错。
     */
    public static Spell authoring(Predicate<HiddenWord> knows, String src, Supplier<Spell> compile) {
        Spell spell = authoring(knows, compile);
        for (HiddenWord w : ALL) {
            if (knows.test(w) || !w.is(spell)) continue;
            authoring(knows, () -> Compiler.compile(src));
            throw new LogosError("未知的词 " + w.word, 0, 0);
        }
        return spell;
    }

    /** {@code p} 学会了哪些词（玩家写术式的服务端入口用）。 */
    public static Predicate<HiddenWord> knownBy(@Nullable Entity p) {
        return w -> w.knows(p);
    }

    /** 本机玩家学会了哪些词（编纂台在客户端编图用）。 */
    public static Predicate<HiddenWord> knownByViewer() {
        return w -> w.viewerKnows.getAsBoolean();
    }
}
