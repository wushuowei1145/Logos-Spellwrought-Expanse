package dev.logos.dimension.companion;

import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 同伴的「档案」：打开对话、角色面板时服务端发给客户端的一份快照（docs/SHIRAUME_COMPANION.md 第 8.13 节）。
 * 结构是通用的，别的同伴填自己的数值、能力和往事，界面照着画；文字都在语言文件里，这里只放键和数。
 *
 * @param entity       她的实体 ID（对话按它认人）
 * @param companion    哪一位（{@code shiraume}）：界面的配色、展示姿势和语言键都按它找
 * @param outfit       当前装束（面板里展示她穿的那一套）
 * @param stats        数值：生命、攻击、护甲……
 * @param skills       能力：普通攻击、特殊、被动，含没解锁的
 * @param seals        契印枚数，{@code maxSeals} 一共几枚（白梅九尾就是九枚）
 * @param stage        羁绊到了哪一段（{@link CompanionBond.Stage} 的名字），{@code stageProgress} 离下一段走了几成（0–1）
 * @param satiety      饱腹，{@code satietyMax} 上限
 * @param staying      留在原地（假：跟着主人）；{@code tactic} 行动策略（{@link CompanionTactic}）
 * @param memories     想起了几段往事（第 0 段本来就记得），{@code memoryTotal} 一共几段
 * @param ratio        继承主人长期属性的比例（0–1）
 */
public record CompanionSheet(int entity, String companion, String outfit, List<Stat> stats, List<Skill> skills, int seals, int maxSeals,
                             String stage, float stageProgress, float satiety, float satietyMax, boolean staying, String tactic,
                             int memories, int memoryTotal, float ratio) {
    /** 能力的三类：普通攻击、特殊、被动。 */
    public static final int NORMAL = 0, SPECIAL = 1, PASSIVE = 2;

    /**
     * 一项数值。{@code value} 现在的值，{@code base} 她自己本来的，多出来的是从主人那里继承的；{@code current} 不小于 0 时
     * 显示成「current / value」（生命）。语言键 {@code gui.logos_dimension.companion.stat.<id>}。
     */
    public record Stat(String id, float value, float base, float current) {
        static final StreamCodec<ByteBuf, Stat> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Stat::id, ByteBufCodecs.FLOAT, Stat::value,
                ByteBufCodecs.FLOAT, Stat::base, ByteBufCodecs.FLOAT, Stat::current, Stat::new);

        public float bonus() {
            return value - base;
        }
    }

    /**
     * 一项能力。语言键 {@code talk.logos_dimension.<companion>.skill.<id>.name}、{@code .desc}（{@code args} 填进说明；以 {@code #} 开头的
     * 参数本身也是语言键）、{@code .icon}（图标上的一个字）。{@code seal}：第几枚契印解锁，0 是本来就会。
     */
    public record Skill(String id, int kind, int seal, boolean unlocked, List<String> args) {
        static final StreamCodec<ByteBuf, Skill> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Skill::id, ByteBufCodecs.VAR_INT, Skill::kind,
                ByteBufCodecs.VAR_INT, Skill::seal, ByteBufCodecs.BOOL, Skill::unlocked, ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), Skill::args,
                Skill::new);
    }

    public static final StreamCodec<ByteBuf, CompanionSheet> CODEC = StreamCodec.of((buf, s) -> {
        ByteBufCodecs.VAR_INT.encode(buf, s.entity);
        ByteBufCodecs.STRING_UTF8.encode(buf, s.companion);
        ByteBufCodecs.STRING_UTF8.encode(buf, s.outfit);
        Stat.CODEC.apply(ByteBufCodecs.list()).encode(buf, s.stats);
        Skill.CODEC.apply(ByteBufCodecs.list()).encode(buf, s.skills);
        ByteBufCodecs.VAR_INT.encode(buf, s.seals);
        ByteBufCodecs.VAR_INT.encode(buf, s.maxSeals);
        ByteBufCodecs.STRING_UTF8.encode(buf, s.stage);
        ByteBufCodecs.FLOAT.encode(buf, s.stageProgress);
        ByteBufCodecs.FLOAT.encode(buf, s.satiety);
        ByteBufCodecs.FLOAT.encode(buf, s.satietyMax);
        ByteBufCodecs.BOOL.encode(buf, s.staying);
        ByteBufCodecs.STRING_UTF8.encode(buf, s.tactic);
        ByteBufCodecs.VAR_INT.encode(buf, s.memories);
        ByteBufCodecs.VAR_INT.encode(buf, s.memoryTotal);
        ByteBufCodecs.FLOAT.encode(buf, s.ratio);
    }, buf -> new CompanionSheet(ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf),
            Stat.CODEC.apply(ByteBufCodecs.list()).decode(buf), Skill.CODEC.apply(ByteBufCodecs.list()).decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.FLOAT.decode(buf), ByteBufCodecs.FLOAT.decode(buf),
            ByteBufCodecs.FLOAT.decode(buf), ByteBufCodecs.BOOL.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.FLOAT.decode(buf)));

    public Stat stat(String id) {
        for (Stat s : stats) if (s.id.equals(id)) return s;
        return new Stat(id, 0, 0, -1);
    }

    public CompanionTactic tacticOf() {
        return CompanionTactic.byId(tactic);
    }
}
