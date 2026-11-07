package dev.logos.dimension.client;

/**
 * 能一次写入多个实体顶点的缓冲区，由 mixin 加到 {@code BufferBuilder} 上（见 {@link NpcMesh}）。
 * 别的模组继承了 {@code BufferBuilder} 时一律不接受，调用方退回逐顶点写。
 */
public interface BulkVertexTarget {
    /** 现在能不能直接写入 {@code DefaultVertexFormat.ENTITY} 的四边形：缓冲区用的就是原版实体格式。 */
    boolean logos_dimension$acceptsEntityVertices();

    /** 这是不是一个正在写四边形的普通缓冲区，不管顶点格式。光影包换了格式时要靠 {@link ShaderCompat} 转换。 */
    boolean logos_dimension$acceptsQuads();

    /** 把 {@code ints} 个 int 的顶点数据（每个顶点 9 个 int，布局同原版实体格式）写进缓冲区并记入顶点数。 */
    void logos_dimension$writeEntityVertices(int[] data, int ints);

    /**
     * 结束上一个顶点。Sodium 的批量写入不会替别人收尾，而 Iris 要在一个面的最后一个顶点结束时才补全这个面的切线，
     * 不先调用这个方法，前一个逐顶点写的面就缺数据。
     */
    void logos_dimension$endLastVertex();
}
