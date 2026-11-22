package dev.logos.dimension.client.corruption;

import com.geckolib.renderer.base.BoneSnapshots;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.RenderPassInfo;
import dev.logos.dimension.client.NpcRenderer;
import dev.logos.dimension.elven.blight.ElfPrincess;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

/**
 * 腐化女王之战里的精灵公主：用同伴精灵公主的模型和动画（{@code elf_princess_companion}）。
 * 入场的荷花苞（{@code SummonBud}）她在这里用不到，连带子骨骼一起不画（同 {@code CompanionClient.PrincessRenderer}）。
 */
public class FightPrincessRenderer<R extends LivingEntityRenderState & GeoRenderState> extends NpcRenderer<ElfPrincess, R> {
    public FightPrincessRenderer(EntityRendererProvider.Context context) {
        super(context, "elf_princess_companion");
    }

    @Override
    public void adjustModelBonesForRender(RenderPassInfo<R> info, BoneSnapshots snapshots) {
        super.adjustModelBonesForRender(info, snapshots);
        snapshots.ifPresent("SummonBud", s -> {
            s.skipRender(true);
            s.skipChildrenRender(true);
        });
    }
}
