package dev.logos.dimension.jiansui;

import dev.logos.dimension.LogosDimension;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 剑祟（docs/JIAN_SUI_BOSS.md）的实体注册。 */
public final class JianSuiEntities {
    private static final DeferredRegister<EntityType<?>> TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, LogosDimension.ID);

    /** 剑祟本体：四格高（2 倍制作、渲染缩放 0.5），眼睛约 3.28 格高。 */
    public static final Supplier<EntityType<JianSui>> JIAN_SUI = TYPES.register("jian_sui", () ->
            EntityType.Builder.of(JianSui::new, MobCategory.MONSTER).sized(1.5f, 4.0f).eyeHeight(3.28f).fireImmune().clientTrackingRange(10).updateInterval(2)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, LogosDimension.id("jian_sui"))));

    /** 第三阶段的场景：不存档；追踪 10 个区块（空洞半径 36，人在里面都收得到），位置不会变，所以很少发位置。 */
    public static final Supplier<EntityType<FallStage>> FALL_STAGE = TYPES.register("jian_sui_fall_stage", () ->
            EntityType.Builder.<FallStage>of(FallStage::new, MobCategory.MISC).sized(1f, 1f).clientTrackingRange(10).updateInterval(100).noSave()
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, LogosDimension.id("jian_sui_fall_stage"))));

    private JianSuiEntities() {}

    public static void register(IEventBus bus) {
        TYPES.register(bus);
        JianSuiBlocks.register(bus);
        bus.addListener((EntityAttributeCreationEvent event) -> event.put(JIAN_SUI.get(), JianSui.createAttributes().build()));
        bus.addListener((net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) -> {
            var registrar = event.registrar("1");
            registrar.playToClient(JianSuiCue.TYPE, JianSuiCue.CODEC);
            // 终结里撬剑：开始时告诉附近的人谁在撬、撬哪一只；撬的人按一下攻击键告诉服务端
            registrar.playToClient(JianSuiPry.Start.TYPE, JianSuiPry.Start.CODEC);
            registrar.playToServer(JianSuiPry.TYPE, JianSuiPry.CODEC, (payload, context) -> {
                if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) JianSuiFight.pryPress(player);
            });
        });
    }
}
