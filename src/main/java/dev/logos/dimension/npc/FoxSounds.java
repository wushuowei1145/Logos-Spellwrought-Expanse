package dev.logos.dimension.npc;

import java.util.function.Supplier;

import dev.logos.dimension.LogosDimension;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 神狐的语音（对标村民与森林精灵）。声音文件由 tools/divine_fox_assets.py 按素材标签归类、复制，并写 sounds.json；
 * 素材是维护者转好的 ogg（声音素材/神狐女/shenhu_female_ogg）。
 */
public final class FoxSounds {
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, LogosDimension.ID);

    /** 闲时：疑惑、附和。 */
    public static final Supplier<SoundEvent> AMBIENT = event("ambient");
    /** 被玩家搭话：高兴、附和。 */
    public static final Supplier<SoundEvent> GREET = event("greet");
    /** 肯定。 */
    public static final Supplier<SoundEvent> YES = event("yes");
    /** 不满（战斗中被搭话）。 */
    public static final Supplier<SoundEvent> NO = event("no");
    /** 发现敌人：一声不屑的「哼」。 */
    public static final Supplier<SoundEvent> ALERT = event("alert");
    /** 出刀、放箭时发力。 */
    public static final Supplier<SoundEvent> ATTACK = event("attack");
    /** 放狐火缠绕。 */
    public static final Supplier<SoundEvent> CAST = event("cast");
    public static final Supplier<SoundEvent> HURT = event("hurt");
    public static final Supplier<SoundEvent> DEATH = event("death");

    private static Supplier<SoundEvent> event(String name) {
        String path = "entity.divine_fox." + name;
        return SOUNDS.register(path, () -> SoundEvent.createVariableRangeEvent(LogosDimension.id(path)));
    }

    public static void register(IEventBus bus) {
        SOUNDS.register(bus);
    }

    private FoxSounds() {}
}
