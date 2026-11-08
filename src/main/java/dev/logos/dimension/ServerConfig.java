package dev.logos.dimension;

import net.neoforged.neoforge.common.ModConfigSpec;

/** 影响玩法的设置，每个存档一份：serverconfig/logos_dimension-server.toml。 */
public final class ServerConfig {
    private ServerConfig() {}

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.comment("Corruption: cores, veins and the filth they spread").push("corruption");
    }

    public static final ModConfigSpec.BooleanValue CORRUPTION_CONVERTS_BLOCKS = BUILDER
            .comment("Whether spreading veins turn the blocks they cover into corruption, the way sculk veins turn blocks into sculk.",
                    "Off: veins still creep over surfaces, but no block is replaced.")
            .define("convertBlocks", true);

    public static final ModConfigSpec.DoubleValue CORRUPTION_DAMAGE = BUILDER
            .comment("Magic damage dealt every half second to players and non-undead mobs touching corruption or its veins.")
            .defineInRange("contactDamage", 4.0, 0.0, 1000.0);

    public static final ModConfigSpec.DoubleValue CORRUPTION_MANA_DRAIN = BUILDER
            .comment("Mana lost every half second while touching corruption.")
            .defineInRange("manaDrain", 8.0, 0.0, 100000.0);

    public static final ModConfigSpec.DoubleValue CORRUPTION_CHARGE_PER_HEALTH = BUILDER
            .comment("Spread charge gained when corruption kills something, per point of the victim's max health.",
                    "One charge converts about one block, like one experience point for a sculk catalyst.")
            .defineInRange("chargePerMaxHealth", 1.0, 0.0, 100.0);

    public static final ModConfigSpec.IntValue CORRUPTION_SEED_CHARGE = BUILDER
            .comment("Charge a new core spreads around itself when placed.")
            .defineInRange("seedCharge", 24, 0, 1000);

    public static final ModConfigSpec.IntValue CORRUPTION_IDLE_CHARGE = BUILDER
            .comment("Charge a core adds by itself every idleSeconds, so a network keeps creeping without kills. 0 turns it off.")
            .defineInRange("idleCharge", 4, 0, 1000);

    public static final ModConfigSpec.IntValue CORRUPTION_IDLE_SECONDS = BUILDER
            .defineInRange("idleSeconds", 60, 1, 86400);

    public static final ModConfigSpec.IntValue CORRUPTION_GROWTH_RADIUS = BUILDER
            .comment("Beyond about this many blocks from its core a network's charge dies out quickly (sculk catalysts use 24).")
            .defineInRange("growthRadius", 24, 4, 256);

    public static final ModConfigSpec.IntValue CORRUPTION_MAX_BLOCKS = BUILDER
            .comment("A network stops growing at this many veins and filth blocks; extra charge plants a satellite core instead.",
                    "Breaking a core only clears its own network, so this also bounds how much one break dissolves.")
            .defineInRange("maxBlocks", 600, 16, 100000);

    public static final ModConfigSpec.IntValue CORRUPTION_MAX_SATELLITES = BUILDER
            .comment("How many satellite cores one core can plant. Satellites plant their own, with no limit on generations.")
            .defineInRange("maxSatellites", 3, 0, 64);

    public static final ModConfigSpec.IntValue CORRUPTION_SATELLITE_SPACING = BUILDER
            .comment("A satellite core is planted at least this many blocks from every other core.")
            .defineInRange("satelliteSpacing", 16, 2, 256);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();
}
