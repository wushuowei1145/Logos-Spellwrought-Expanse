package dev.logos.dimension.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.logos.dimension.client.BulkVertexTarget;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** 只加方法，不改原版任何行为：没人调用这些方法时 {@code BufferBuilder} 和原来完全一样。 */
@Mixin(BufferBuilder.class)
public abstract class BufferBuilderBulkMixin implements BulkVertexTarget {
    private static final int ENTITY_VERTEX_SIZE = 36, ENTITY_VERTEX_INTS = ENTITY_VERTEX_SIZE / Integer.BYTES, MAX_VERTICES = 16777215;

    @Shadow @Final private ByteBufferBuilder buffer;
    @Shadow @Final private VertexFormat.Mode mode;
    @Shadow @Final private int vertexSize;
    @Shadow @Final private boolean fullFormat;
    @Shadow private long vertexPointer;
    @Shadow private int vertices;
    @Shadow private boolean building;

    @Shadow private void endLastVertex() {
        throw new AssertionError();
    }

    @Override
    public boolean logos_dimension$acceptsEntityVertices() {
        return logos_dimension$acceptsQuads() && fullFormat && vertexSize == ENTITY_VERTEX_SIZE;
    }

    @Override
    public boolean logos_dimension$acceptsQuads() {
        return building && mode == VertexFormat.Mode.QUADS && ((Object) this).getClass() == BufferBuilder.class;
    }

    /** 和 {@code beginVertex} 做同样的记账，只是一次记多个。 */
    @Override
    public void logos_dimension$writeEntityVertices(int[] data, int ints) {
        int count = ints / ENTITY_VERTEX_INTS;
        endLastVertex();
        if (vertices + count > MAX_VERTICES) throw new IllegalStateException("Trying to write too many vertices (>16777215) into BufferBuilder");
        long pointer = buffer.reserve(count * vertexSize);
        vertices += count;
        vertexPointer = pointer + (long) (count - 1) * vertexSize;
        MemoryUtil.memIntBuffer(pointer, ints).put(data, 0, ints);
    }

    @Override
    public void logos_dimension$endLastVertex() {
        endLastVertex();
    }
}
