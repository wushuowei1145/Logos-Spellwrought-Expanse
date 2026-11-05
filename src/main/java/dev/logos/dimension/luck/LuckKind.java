package dev.logos.dimension.luck;

import com.mojang.serialization.Codec;
import net.minecraft.util.StringRepresentable;

/**
 * 运势的种类，对应御神签上的各个签项。现在都是空壳：能抽到、能看到图标，还没有实际效果；
 * 玩法要用时读 {@link Luck#level}。{@code hasBad} 为假的没有凶的一面（失物运不罚人）。
 */
public enum LuckKind implements StringRepresentable {
    WEALTH("wealth", true, 0xE8B53A),
    MARKET("market", true, 0x5FB6C9),
    WAR("war", true, 0xD8513E),
    SPELL("spell", true, 0x9A6BE0),
    HARVEST("harvest", true, 0x8DBB45),
    ALCHEMY("alchemy", true, 0x46B59A),
    TRAVEL("travel", true, 0xC79A5B),
    LOST("lost", false, 0xD9C7A0),
    MEETING("meeting", true, 0xE58FB0);

    public static final Codec<LuckKind> CODEC = StringRepresentable.fromEnum(LuckKind::values);

    public final String id;
    public final boolean hasBad;
    public final int color;

    LuckKind(String id, boolean hasBad, int color) {
        this.id = id;
        this.hasBad = hasBad;
        this.color = color;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    /** 签纸上这一项的名字（商卖、胜负……）。 */
    public String labelKey() {
        return "luck.logos_dimension." + id;
    }

    /** 这一项在这个等级（正吉负凶）下的第 i 句说明。 */
    public String lineKey(int level, int i) {
        return labelKey() + "." + (level < 0 ? "minus" : "plus") + Math.abs(level) + "." + i;
    }
}
