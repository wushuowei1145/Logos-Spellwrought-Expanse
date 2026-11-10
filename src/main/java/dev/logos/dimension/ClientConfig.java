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

    public static final ModConfigSpec.IntValue INSTRUMENT_VOICES = BUILDER
            .comment("Notes one instrument may sound at once. When full, the oldest released note is cut first.")
            .defineInRange("instrumentVoices", 32, 4, 64);

    public static final ModConfigSpec.IntValue INSTRUMENT_TOTAL_VOICES = BUILDER
            .comment("Notes all instruments together may sound at once; the game has about 247 sound channels for everything.")
            .defineInRange("instrumentTotalVoices", 96, 8, 160);

    public static final ModConfigSpec.ConfigValue<String> INSTRUMENT_KEYS = BUILDER
            .comment("Keys of the play screen, low to high, one semitone each, starting at C. Letters, digits and , . / ; ' [ ] - = are allowed.",
                    "Default: two piano-style rows, Z S X D C V G B H N J M (lower octave) then Q 2 W 3 E R 5 T 6 Y 7 U I 9 O 0 P.")
            .define("instrumentKeys", "ZSXDCVGBHNJMQ2W3ER5T6Y7UI9O0P");

    public static final ModConfigSpec.ConfigValue<String> MIDI_DEVICE = BUILDER
            .comment("Name of the MIDI keyboard to connect to when an instrument screen opens. Empty: none.",
                    "Pick it in game: instrument screen, Play page, MIDI keyboard.")
            .define("midiDevice", "");

    public static final ModConfigSpec.IntValue MIDI_CHANNEL = BUILDER
            .comment("MIDI channel to listen to: 0 = all channels, 1-16 = only that one (useful for split keyboards).")
            .defineInRange("midiChannel", 0, 0, 16);

    public static final ModConfigSpec.IntValue MIDI_TRANSPOSE = BUILDER
            .comment("Semitones added to every note from the MIDI keyboard.")
            .defineInRange("midiTranspose", 0, -36, 36);

    public static final ModConfigSpec.IntValue MIDI_VELOCITY_CURVE = BUILDER
            .comment("How the MIDI keyboard's key velocity maps to loudness: 0 = light (soft touches still sound), 1 = normal, 2 = heavy.")
            .defineInRange("midiVelocityCurve", 1, 0, 2);

    public static final ModConfigSpec SPEC = BUILDER.build();
}
