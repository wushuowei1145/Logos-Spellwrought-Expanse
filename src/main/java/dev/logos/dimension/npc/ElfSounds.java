package dev.logos.dimension.npc;

import java.util.function.Supplier;

import dev.logos.dimension.LogosDimension;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 森林精灵的语音（对标村民的各种情况）。声音文件由 tools/forest_elf_sounds.py 按素材标签归类、转成 ogg，
 * 并写 sounds.json；这里只登记精灵会用到的事件。中性嗓音的事件也写在 sounds.json 里，留给以后的幼年与中性外观。
 */
public final class ElfSounds {
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, LogosDimension.ID);

    /** 闲时：心情愉快、思索、疑惑。 */
    public static final Supplier<SoundEvent> AMBIENT = event("ambient");
    /** 被玩家搭话：语气友好、热情、高兴。 */
    public static final Supplier<SoundEvent> GREET = event("greet");
    /** 肯定。 */
    public static final Supplier<SoundEvent> YES = event("yes");
    /** 不满（战斗中被搭话）。 */
    public static final Supplier<SoundEvent> NO = event("no");
    /** 发现敌人。 */
    public static final Supplier<SoundEvent> ALERT = event("alert");
    /** 放箭时发力。 */
    public static final Supplier<SoundEvent> ATTACK = event("attack");
    /** 放治疗法术。 */
    public static final Supplier<SoundEvent> CAST = event("cast");
    public static final Supplier<SoundEvent> HURT = event("hurt");
    public static final Supplier<SoundEvent> DEATH = event("death");

    private static Supplier<SoundEvent> event(String name) {
        String path = "entity.forest_elf." + name;
        return SOUNDS.register(path, () -> SoundEvent.createVariableRangeEvent(LogosDimension.id(path)));
    }

    public static void register(IEventBus bus) {
        SOUNDS.register(bus);
    }

    private ElfSounds() {}
}
