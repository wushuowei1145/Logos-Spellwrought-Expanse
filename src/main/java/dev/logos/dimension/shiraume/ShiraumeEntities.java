package dev.logos.dimension.shiraume;

import dev.logos.dimension.LogosDimension;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 九尾妖狐 白梅：两种形态、狐火飞弹与提示包的注册（docs/SHIRAUME_BOSS.md）。 */
public final class ShiraumeEntities {
    private static final DeferredRegister<EntityType<?>> TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, LogosDimension.ID);
    /** 消散的边缘和花瓣：原版樱花的粉（同流火的花散）。 */
    public static final int CHERRY = 0xF4A6CF;

    /** 人形：发顶约 3.4 格、眼睛约 2.62 格（2 倍制作、渲染缩放 0.5，模型约是玩家的两倍高）。 */
    public static final Supplier<EntityType<Shiraume>> SHIRAUME = TYPES.register("shiraume", () ->
            EntityType.Builder.of(Shiraume::new, MobCategory.MONSTER).sized(1.3f, 3.4f).eyeHeight(2.62f).fireImmune().clientTrackingRange(10)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, LogosDimension.id("shiraume"))));

    /**
     * 狐形：肩高约 2.3 格、头顶约 2.9 格；身长 4.3 格比判定箱长，像劫掠兽一样只算躯干。
     * 大跳、跳闪按弧线走，位置每两刻发一次（默认三刻），客户端看到的弧线才圆。
     */
    public static final Supplier<EntityType<ShiraumeBeast>> BEAST = TYPES.register("shiraume_beast", () ->
            EntityType.Builder.of(ShiraumeBeast::new, MobCategory.MONSTER).sized(2.6f, 2.8f).eyeHeight(2.6f).fireImmune().clientTrackingRange(10).updateInterval(2)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, LogosDimension.id("shiraume_beast"))));

    /** 狐形八分之一血以下从尾尖放出的狐火飞弹。 */
    public static final Supplier<EntityType<FoxfireBolt>> FOXFIRE = TYPES.register("shiraume_foxfire", () ->
            EntityType.Builder.<FoxfireBolt>of(FoxfireBolt::new, MobCategory.MISC).sized(.6f, .6f).clientTrackingRange(6).updateInterval(2).noSave()
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, LogosDimension.id("shiraume_foxfire"))));

    private ShiraumeEntities() {}

    public static void register(IEventBus bus) {
        TYPES.register(bus);
        bus.addListener((EntityAttributeCreationEvent event) -> {
            event.put(SHIRAUME.get(), Shiraume.createAttributes().build());
            event.put(BEAST.get(), ShiraumeBeast.createAttributes().build());
        });
        bus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1").playToClient(ShiraumeCue.TYPE, ShiraumeCue.CODEC));
    }
}
