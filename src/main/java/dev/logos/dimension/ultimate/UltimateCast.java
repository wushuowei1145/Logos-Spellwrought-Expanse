package dev.logos.dimension.ultimate;

import dev.logos.dimension.companion.CompanionDef;
import dev.logos.dimension.companion.CompanionEntity;
import dev.logos.dimension.companion.CompanionLines;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import dev.logos.dimension.LogosDimension;

/**
 * 正在演的一段奥义（docs/ULTIMATES.md「脚本」）：谁在演、为谁演、连携里第几段、打谁，以及打中时怎么算伤害。
 * 脚本（{@link UltimateScript}）每刻拿到它。
 */
public final class UltimateCast {
    /** 怎么把伤害打出去：默认是演的人自己的近战；龙炎、奥秘、生命这类走各自的伤害入口。返回打没打中。 */
    @FunctionalInterface
    public interface Dealer {
        boolean deal(LivingEntity target, float amount);
    }

    private static final Identifier PIERCE = LogosDimension.id("ultimate_pierce");

    private final ServerPlayer owner;
    private final LivingEntity performer;
    private final @Nullable CompanionDef def;
    private final String key;
    private final UltimateChain chain;
    private final UltimateChain.Stage stage;
    private final UltimateScript script;
    private final long seed;
    private final Vec3 aim;
    private @Nullable LivingEntity target;
    int age;

    UltimateCast(ServerPlayer owner, LivingEntity performer, @Nullable CompanionDef def, String key, UltimateChain chain, UltimateChain.Stage stage,
                 UltimateScript script, long seed, @Nullable LivingEntity target, Vec3 aim) {
        this.owner = owner;
        this.performer = performer;
        this.def = def;
        this.key = key;
        this.chain = chain;
        this.stage = stage;
        this.script = script;
        this.seed = seed;
        this.target = target;
        this.aim = aim;
    }

    /** 为谁演（下令的玩家）。 */
    public ServerPlayer owner() {
        return owner;
    }

    /** 谁在演：同伴，或者放最后一击的玩家自己。 */
    public LivingEntity performer() {
        return performer;
    }

    /** 在演的同伴；玩家的最后一击时是 null。 */
    public @Nullable CompanionEntity companion() {
        return performer instanceof CompanionEntity c ? c : null;
    }

    /** 她的档案；玩家的最后一击时是 null。 */
    public @Nullable CompanionDef def() {
        return def;
    }

    /** 画面按这个找演出：同伴 ID，或者 {@code hero/<武器大类>}。 */
    public String key() {
        return key;
    }

    public UltimateChain.Stage stage() {
        return stage;
    }

    public UltimateScript script() {
        return script;
    }

    /** 开演以后第几刻。 */
    public int age() {
        return age;
    }

    /** 这一次的随机种子（客户端的演出拿到同一个）。 */
    public long seed() {
        return seed;
    }

    public ServerLevel level() {
        return (ServerLevel) performer.level();
    }

    /** 打谁：开演时选好的；死了、跑到别的世界了，就重锁离原来那一点最近的（{@link UltimateTargets#NEAR} 格内），一个都没有时是 null。 */
    public @Nullable LivingEntity target() {
        if (target != null && target.isAlive() && target.level() == performer.level()) return target;
        Vec3 around = target != null ? target.position() : aim;
        target = UltimateTargets.near(owner, performer, around, UltimateTargets.NEAR);
        return target;
    }

    /** 往哪放：目标在时是目标脚下，没有目标时是开演时准星前方的那一点。 */
    public Vec3 aim() {
        LivingEntity t = target();
        return t != null ? t.position() : aim;
    }

    /** 这一段的威力：连携里第几段的倍率（最后一击按几位同伴），再乘前面交过来的馈赠。 */
    public double power() {
        double p = stage.finisher() ? UltimateChain.finisherPower(stage.members()) : UltimateChain.power(stage.index());
        for (RelayGift g : chain.gifts(stage)) p *= g.power(stage);
        return p;
    }

    /** 打中一下：用演的人自己的近战伤害。 */
    public float hit(LivingEntity target, float base) {
        return hit(target, base, this::melee);
    }

    /**
     * 打中一下：{@code base} 乘上 {@link #power}，再让前面交过来的馈赠改一遍（加伤、穿甲、二次引爆），然后由 {@code dealer} 打出去。
     * 连斩也每一下都算（清掉目标的无敌帧）。返回打出去的伤害（没打中是 0）。
     */
    public float hit(LivingEntity target, float base, Dealer dealer) {
        if (!target.isAlive()) return 0;
        UltimateHit hit = new UltimateHit(target, (float) (base * power()));
        for (RelayGift g : chain.gifts(stage)) g.hit(this, hit);
        target.invulnerableTime = 0;
        boolean landed;
        double pierce = hit.pierce();
        if (pierce > 0) {
            pierce(target, Attributes.ARMOR, -pierce);
            pierce(target, Attributes.ARMOR_TOUGHNESS, -pierce);
        }
        try {
            landed = dealer.deal(target, hit.amount());
        } finally {
            if (pierce > 0) {
                unpierce(target, Attributes.ARMOR);
                unpierce(target, Attributes.ARMOR_TOUGHNESS);
            }
        }
        return landed ? hit.amount() : 0;
    }

    private boolean melee(LivingEntity target, float amount) {
        var sources = performer.damageSources();
        var source = performer instanceof Player p ? sources.playerAttack(p) : sources.mobAttack(performer);
        return target.hurtServer(level(), source, amount);
    }

    private static void pierce(LivingEntity target, Holder<Attribute> attribute, double amount) {
        var inst = target.getAttribute(attribute);
        if (inst != null && !inst.hasModifier(PIERCE)) inst.addTransientModifier(new AttributeModifier(PIERCE, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    private static void unpierce(LivingEntity target, Holder<Attribute> attribute) {
        var inst = target.getAttribute(attribute);
        if (inst != null) inst.removeModifier(PIERCE);
    }

    /** 她对主人说一句：{@code line.logos_dimension.<同伴>.<line>}。玩家的最后一击时什么都不说。 */
    public void say(String line, int ticks) {
        CompanionEntity c = companion();
        if (c != null) CompanionLines.say(c, owner, line, ticks);
    }

    /** 管得着这一段的馈赠（只读）。 */
    public List<RelayGift> gifts() {
        return chain.gifts(stage);
    }
}
