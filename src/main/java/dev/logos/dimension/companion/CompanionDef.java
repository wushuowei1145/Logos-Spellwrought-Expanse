package dev.logos.dimension.companion;

import dev.logos.dimension.ultimate.UltimateDef;
import java.util.function.Supplier;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.attachment.AttachmentType;

/**
 * 一位同伴的定义（docs/COMPANIONS.md 第 4 节）：同伴的 ID、实体、存在主人身上的契和包袱、数值成长、休息多久、契印一共几枚。
 * 公共的系统（召唤收回、数值、心智、表情、台词……）都按它读，不再把哪一位写死在类里。
 *
 * <p>每位同伴的契和包袱各是一个玩家附件，ID 是 {@code <同伴>_bond}、{@code <同伴>_pouch}：白梅的就是原来的
 * {@code shiraume_bond}、{@code shiraume_pouch}，旧存档原样读得出来，一个玩家可以和几位分别立契。
 *
 * @param id         同伴 ID（语言键、对话、界面样式、台词前缀都按它找），例如 {@code shiraume}
 * @param type       她的实体
 * @param bond       主人身上和她的契（{@link Companions.Bond}）
 * @param pouch      主人身上她背的包袱
 * @param growth     数值：她自己的、契印成长、羁绊加成、继承比例
 * @param restTicks  倒下（或撤退）以后要歇多少刻才能再叫
 * @param maxSeals   契印一共几枚
 * @param ultimate   她的奥义（docs/ULTIMATES.md）：冷却、耗魔、演出脚本
 */
public record CompanionDef(String id, Supplier<? extends EntityType<? extends CompanionEntity>> type, Supplier<AttachmentType<Companions.Bond>> bond,
                           Supplier<AttachmentType<CompanionPouch>> pouch, Growth growth, int restTicks, int maxSeals,
                           UltimateDef ultimate) {
    /**
     * 数值的规矩（docs/COMPANIONS.md 第 3.5 节），每位同伴自己定数。
     *
     * @param health         她自己的生命（没盖契印、生疏时）；{@code armor}、{@code toughness} 同理
     * @param attack         她自己的攻击（白梅：一发狐火弹的基础伤害）
     * @param spellPower     她自己的法术强度（倍数，1 是普通；只有用法术打的同伴有意义）
     * @param sealHealth     每枚契印她自己多长的生命；{@code sealArmor}、{@code sealToughness}、{@code sealSpellPower} 同理（到 {@code fullSeals} 枚为止）
     * @param bondHealth     羁绊每升一段多的生命；{@code bondAttack}、{@code bondSpellPower} 同理
     * @param ratio          继承比例的起点，每枚契印加 {@code ratioStep}，最多 {@code maxRatio}
     * @param fullSeals      她自己的成长到第几枚契印为止
     * @param healthRatio    生命单独的继承比例：起点、每枚契印加 {@code healthRatioStep}、最多 {@code maxHealthRatio}
     * @param inheritsArmor  护甲、韧性和保护类魔咒继承不继承（琥珀不继承，一直是 0）
     */
    public record Growth(double health, double armor, double toughness, double attack, double spellPower,
                         double sealHealth, double sealArmor, double sealToughness, double sealSpellPower,
                         double bondHealth, double bondAttack, double bondSpellPower,
                         double ratio, double ratioStep, double maxRatio, int fullSeals,
                         double healthRatio, double healthRatioStep, double maxHealthRatio, boolean inheritsArmor) {
        /** 生命和别的数按同一个比例继承，护甲也继承（白梅、精灵公主）。 */
        public Growth(double health, double armor, double toughness, double attack, double spellPower,
                      double sealHealth, double sealArmor, double sealToughness, double sealSpellPower,
                      double bondHealth, double bondAttack, double bondSpellPower,
                      double ratio, double ratioStep, double maxRatio, int fullSeals) {
            this(health, armor, toughness, attack, spellPower, sealHealth, sealArmor, sealToughness, sealSpellPower, bondHealth, bondAttack,
                    bondSpellPower, ratio, ratioStep, maxRatio, fullSeals, ratio, ratioStep, maxRatio, true);
        }

        /** 盖了 {@code seals} 枚契印时的继承比例。 */
        public double ratio(int seals) {
            return Math.min(maxRatio, ratio + ratioStep * Math.max(0, seals));
        }

        /** 盖了 {@code seals} 枚契印时生命的继承比例。 */
        public double healthRatio(int seals) {
            return Math.min(maxHealthRatio, healthRatio + healthRatioStep * Math.max(0, seals));
        }

        /** 护甲、韧性、保护类魔咒的继承比例（不继承时是 0）。 */
        public double armorRatio(int seals) {
            return inheritsArmor ? ratio(seals) : 0;
        }

        /** 契印算到第几枚为止（她自己的成长）。 */
        public int grown(int seals) {
            return Math.clamp(seals, 0, fullSeals);
        }
    }

    @Override
    public String toString() {
        return id;
    }
}
