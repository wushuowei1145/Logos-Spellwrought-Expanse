package dev.logos.dimension.client;

import com.geckolib.constant.dataticket.DataTicket;
import com.geckolib.renderer.base.*;
import dev.logos.dimension.elven.blight.CorruptedElfQueen;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.jspecify.annotations.Nullable;

/** 自爆后同时停止普通几何和发光骨骼，不改变其他 NPC 的隐身与渲染规则。 */
public final class QueenRenderer<R extends LivingEntityRenderState & GeoRenderState> extends NpcRenderer<CorruptedElfQueen, R> {
    private static final DataTicket<Boolean> GONE = DataTicket.create("queen_exploded", Boolean.class);
    public QueenRenderer(EntityRendererProvider.Context c) { super(c, "corrupted_elf_queen"); }
    @Override public void addRenderData(CorruptedElfQueen q, Void related, R state, float partial) { super.addRenderData(q, related, state, partial); state.addGeckolibData(GONE, q.isInvisible()); }
    @Override public void adjustRenderPose(RenderPassInfo<R> info) { if (!info.getOrDefaultGeckolibData(GONE, false)) super.adjustRenderPose(info); }
    @Override public void submitRenderTasks(RenderPassInfo<R> info, OrderedSubmitNodeCollector out, @Nullable RenderType type) { if (!info.getOrDefaultGeckolibData(GONE, false)) super.submitRenderTasks(info, out, type); }
}
