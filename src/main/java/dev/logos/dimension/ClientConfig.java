package dev.logos.dimension;

import net.neoforged.neoforge.common.ModConfigSpec;

/** 只影响本机画面的设置，文件是 config/logos_dimension-client.toml。 */
public final class ClientConfig {
    private ClientConfig() {}

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue BLOOM = BUILDER
            .comment("How strongly glowing effects bloom on the screen. 0 turns it off.")
            .defineInRange("bloom", 1.0, 0.0, 4.0);

    public static final ModConfigSpec.DoubleValue BIOME_STYLE = BUILDER
            .comment("How strongly whole-biome painting styles show, like the ink-wash look in Cloudveil Peaks. 0 turns them off.")
            .defineInRange("biomeStyle", 1.0, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue ULTIMATE_HOLD = BUILDER
            .comment("How long (seconds) to hold the ultimate key before the command wheel opens. A shorter press orders the next companion.")
            .defineInRange("ultimateHold", 0.2, 0.15, 0.6);

    public static final ModConfigSpec SPEC = BUILDER.build();
}
