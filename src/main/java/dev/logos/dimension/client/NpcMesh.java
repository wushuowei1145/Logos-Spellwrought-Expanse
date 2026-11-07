package dev.logos.dimension.client;

import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.cache.model.GeoBone;
import com.geckolib.cache.model.GeoQuad;
import com.geckolib.cache.model.GeoVertex;
import com.geckolib.cache.model.cuboid.CuboidGeoBone;
import com.geckolib.cache.model.cuboid.GeoCube;
import com.geckolib.renderer.base.RenderPassInfo;
import com.geckolib.util.RenderUtil;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.logos.dimension.npc.NpcParts;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * 一个 NPC 模型预先算好的网格，代替 GeckoLib 逐方块绘制。
 *
 * <p>GeckoLib 每帧对每个方块都要新建三个四元数、连乘几次矩阵，再为每个面新建法线向量。神狐的方块几乎都各自带旋转，
 * 一只就有几百个，这部分占了渲染线程的大头，还每秒分配几百 MB。方块相对所在骨骼的位置其实不会变，
 * 所以这里提前把方块的旋转算进顶点，每帧只需要乘一次骨骼的矩阵。画出来的顶点和 GeckoLib 一致
 * （{@code npc_fox_render_bench} 场景逐顶点核对）。
 *
 * <p>没选中的零件直接跳过整棵子树，不再为每个隐藏组建骨骼快照。隐藏结果按外观缓存，外观不变就不用重算。
 *
 * <p>NPC 用 {@code entityCutout}，不剔除背面，薄发片和扁平方块要靠它两面可见。但封闭的方块只要贴图不透明，
 * 背面一定被自己的正面挡住，所以这种方块在写顶点之前就跳过背面，顶点数少一半左右。
 *
 * <p>面的窄边投影到屏幕上不足 {@link #MIN_FACE_PIXELS} 像素时不画，薄片的侧边离远一点就不到这么宽。
 * 这是按屏幕大小连续变化的，没有固定的切换距离，近处不受影响。
 *
 * <p>光影包会把看得见的 NPC 再画一遍到阴影贴图里，而且阴影通道是平行投影，上面两条判断都不能照搬（见 {@link Cull}）：
 * 背面按光线方向判断，窄边按离玩家的距离判断（{@link #SHADOW_MIN_EDGE}）。阴影里只留朝向太阳的面，深度和不跳过时一样。
 *
 * <p>最后是写入：LWJGL 3.4 的每次原生内存写入都要过一遍 {@code MemorySegment.reinterpret} 的权限检查，
 * 一个顶点要写十次。所以先把一只 NPC 的顶点攒在数组里，再一次写进去（{@link VertexSink}）。
 * 光影包换了顶点格式时经 Sodium 的公开接口写入（{@link ShaderCompat}），格式转换是 Iris 自己注册的。
 */
final class NpcMesh {
    /** 一个顶点：x y z u v。 */
    static final int VERTEX_FLOATS = 5;
    private static final int QUAD_VERTICES = 4;
    private static final int QUAD_FLOATS = QUAD_VERTICES * VERTEX_FLOATS;
    /** 外观组合理论上有几十万种，缓存满了就清掉重来。 */
    private static final int MAX_LOOKS = 1024;
    /** {@code entityCutout} 丢弃 alpha 低于 0.1 的像素。 */
    private static final int CUTOUT_ALPHA = 26;
    /** {@link Node#flags} 的低三位：扁平方块的法线要朝正向的轴。 */
    private static final int FLIPS = 7;
    /** {@link Node#flags} 的一位：这个面所在的方块封闭且不透明，背对镜头时可以不画。 */
    private static final int CULLABLE = 8;
    /**
     * {@link Node#flags} 的一位：同一个方块里有和这个面正对着的另一个面，背对太阳时阴影里可以不画。
     * 比 {@link #CULLABLE} 宽：扁平方块和半透明方块也算，阴影只要轮廓，透过小孔看到背面那点差别在模糊的影子里看不出来。
     * 只有一个面的方块不算，它朝哪边都要投影。
     */
    private static final int PAIRED = 16;
    /**
     * 背面判定留的余量（格）。镜头实际位置和渲染用的原点差着视角摇晃那一点距离，
     * 只有明确背对的面才跳过，几乎侧对的面照画。
     */
    private static final float BACK_FACE_MARGIN = .15f;
    /**
     * 面的窄边投影到屏幕上不足这么多像素就不画。25 格外一只神狐只有约 66 像素高，却有约 1000 个面朝向镜头，
     * 平均一个面两个像素，这么细的面只会增加顶点，看不出区别。方块面积最大的那一对面不按窄边算，见 {@link #faceSize}。
     */
    private static final float MIN_FACE_PIXELS = 2;
    /**
     * 阴影里窄边不足这么多格的面不画：离玩家每远一格，阈值加 {@link #SHADOW_MIN_EDGE_PER_BLOCK}，
     * 但不小于 {@link #SHADOW_MIN_EDGE}。神狐一个模型像素约 0.03 格，所以 8 格内细到一个模型像素的耳尖、发梢都还在影子里；
     * 16 格外只留三个模型像素以上的，25 格外五个以上。阴影贴图也是这样往外变粗的（Photon 默认的 2048 贴图，
     * 8 格处一个像素约 0.026 格，32 格处约 0.045 格），再加上软阴影的模糊，影子的形状看不出区别。
     */
    private static final float SHADOW_MIN_EDGE = .025f, SHADOW_MIN_EDGE_PER_BLOCK = .004f;
    /** 姿势的缩放超过这个值就是界面预览，不是世界里的实体：世界里最大也就是属性放大后的 NPC。 */
    private static final float MAX_WORLD_SCALE = 8;
    /** {@code DefaultVertexFormat.ENTITY} 一个顶点 36 字节，正好 9 个 int。 */
    private static final int VERTEX_INTS = 9;
    private static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
    /** 只在渲染线程上用。这么大足够放下任何一根骨骼的所有面，放满了就先写一次。 */
    private static final int[] STAGING = new int[1 << 18];
    private static int staged, bulkWrites;
    /** 写过多少个面：下标 0 是主通道，1 是光影包的阴影。基准场景打印并核对，游戏本身不看。 */
    static final long[] QUADS = new long[2];
    /** 只有基准场景才会关掉，好对比跳过小面前后的截图。 */
    static boolean skipSmallFaces = true;
    /** 核对时用的光照和覆盖层，取什么值都行。 */
    private static final int LIGHT = 0x00F000F0, OVERLAY = 0x000A0003;

    /** 决定隐藏哪些骨骼的全部因素。 */
    record LookKey(Map<String, String> appearance, @Nullable String expression, Set<String> extras) {}

    /** 一次接收一批顶点：每个顶点 9 个 int，布局同 {@code DefaultVertexFormat.ENTITY}。 */
    @FunctionalInterface
    interface VertexSink {
        void write(int[] data, int ints);
    }

    /** 封闭方块的背面怎么判断。 */
    private enum Cull {
        /** 界面预览：原点不是镜头，什么都不跳过。 */
        NONE,
        /** 世界里：坐标以镜头为原点，顶点坐标就是视线方向。 */
        CAMERA,
        /**
         * 光影包的阴影：坐标在太阳的视图空间里，平行投影，光线就是 z 轴，朝向太阳的面法线 z 为正。
         * 按镜头那样用顶点坐标当视线方向是错的：离玩家远的方块会选错面，影子上缺一块。
         */
        LIGHT
    }

    private static final class Node {
        final GeoBone bone;
        final int id;
        Node[] children = {};
        /** 每个面 4 个顶点。 */
        float[] vertices = {};
        /** 每个面 3 个数：法线。 */
        float[] normals = {};
        /** 每个面一个掩码：低三位是扁平方块的法线在哪些轴上要朝正向（见 {@link RenderUtil#fixInvertedFlatCube}），再加 {@link #CULLABLE}。 */
        byte[] flags = {};
        /** 每个面拿来和屏幕上不画的阈值比的长度，见 {@link NpcMesh#faceSize}。 */
        float[] sizes = {};
        /** 每个面的窄边。阴影只看这个：长条拼成的一片，影子的形状主要来自里面更宽的方块。 */
        float[] narrowEdges = {};

        Node(GeoBone bone, int id) {
            this.bone = bone;
            this.id = id;
        }
    }

    final BakedGeoModel model;
    private final Node[] roots;
    private final Object2IntOpenHashMap<String> ids = new Object2IntOpenHashMap<>();
    private final List<Node> nodes = new ArrayList<>();
    private final GeoBone[] glowBones;
    private final Map<LookKey, boolean[]> looks = new HashMap<>();

    /** @param texture 模型的贴图，用来判断方块是否透明；读不到（{@code null}）时一律不跳过背面 */
    NpcMesh(BakedGeoModel model, String glowPrefix, @Nullable NativeImage texture) {
        this.model = model;
        ids.defaultReturnValue(-1);
        roots = build(model.topLevelBones(), texture);
        List<GeoBone> glow = new ArrayList<>();
        for (Node node : nodes) if (node.bone.name().startsWith(glowPrefix)) glow.add(node.bone);
        glowBones = glow.toArray(new GeoBone[0]);
    }

    private Node[] build(GeoBone[] bones, @Nullable NativeImage texture) {
        Node[] out = new Node[bones.length];
        for (int i = 0; i < bones.length; i++) {
            Node node = new Node(bones[i], nodes.size());
            nodes.add(node);
            ids.put(bones[i].name(), node.id);
            if (bones[i] instanceof CuboidGeoBone cuboid) bake(node, cuboid, texture);
            node.children = build(bones[i].children(), texture);
            out[i] = node;
        }
        return out;
    }

    private static void bake(Node node, CuboidGeoBone bone, @Nullable NativeImage texture) {
        int quads = 0;
        for (GeoCube cube : bone.cubes) for (GeoQuad quad : cube.quads()) if (quad != null) quads++;
        node.vertices = new float[quads * QUAD_FLOATS];
        node.normals = new float[quads * 3];
        node.flags = new byte[quads];
        node.sizes = new float[quads];
        node.narrowEdges = new float[quads];
        int v = 0, q = 0;
        for (GeoCube cube : bone.cubes) {
            // 和 GeoCube.render 同样的步骤，只是提前算好
            PoseStack local = new PoseStack();
            cube.translateToPivotPoint(local);
            cube.rotate(local);
            cube.translateAwayFromPivotPoint(local);
            Matrix4f position = local.last().pose();
            Matrix3f normal = local.last().normal();
            byte flags = (byte) (flips(cube) | (closedAndOpaque(cube, texture) ? CULLABLE : 0));
            float largest = 0;
            for (GeoQuad quad : cube.quads()) {
                if (quad != null && quad.vertices().length == QUAD_VERTICES) largest = Math.max(largest, area(quad));
            }
            for (GeoQuad quad : cube.quads()) {
                if (quad == null) continue;
                if (quad.vertices().length != QUAD_VERTICES) throw new IllegalStateException("GeckoLib 的面不是四个顶点：" + bone.name());
                Vector3f n = normal.transform(quad.normalVec());
                node.normals[q * 3] = n.x;
                node.normals[q * 3 + 1] = n.y;
                node.normals[q * 3 + 2] = n.z;
                node.sizes[q] = faceSize(quad, area(quad) >= largest * .999f);
                node.narrowEdges[q] = faceSize(quad, false);
                node.flags[q++] = (byte) (flags | (hasOpposite(cube, quad) ? PAIRED : 0));
                for (GeoVertex vertex : quad.vertices()) {
                    Vector4f p = position.transform(new Vector4f(vertex.posX(), vertex.posY(), vertex.posZ(), 1));
                    node.vertices[v++] = p.x;
                    node.vertices[v++] = p.y;
                    node.vertices[v++] = p.z;
                    node.vertices[v++] = vertex.texU();
                    node.vertices[v++] = vertex.texV();
                }
            }
        }
    }

    /**
     * 拿来和不画的阈值比的长度（骨骼空间，未算渲染缩放）。普通的面是窄边，比如薄片的侧边。
     * 方块最大的一对面（{@code main}）用长边的两倍，也就是整个面都小于阈值的一半才不画：这个模型的裙子、头发是一排一模型像素宽的长条拼的，
     * 每条都很窄，合起来却是一整片，按窄边算就会在某个距离上整片消失。
     */
    private static float faceSize(GeoQuad quad, boolean main) {
        GeoVertex a = quad.vertices()[0], b = quad.vertices()[1], c = quad.vertices()[2];
        float first = squaredDistance(a, b), second = squaredDistance(b, c);
        return main ? 2 * (float) Math.sqrt(Math.max(first, second)) : (float) Math.sqrt(Math.min(first, second));
    }

    private static boolean hasOpposite(GeoCube cube, GeoQuad quad) {
        for (GeoQuad other : cube.quads()) {
            if (other != null && quad.normalX() * other.normalX() + quad.normalY() * other.normalY() + quad.normalZ() * other.normalZ() < -.99f) return true;
        }
        return false;
    }

    private static float area(GeoQuad quad) {
        GeoVertex a = quad.vertices()[0], b = quad.vertices()[1], c = quad.vertices()[2];
        return (float) Math.sqrt(squaredDistance(a, b) * squaredDistance(b, c));
    }

    private static float squaredDistance(GeoVertex a, GeoVertex b) {
        float x = a.posX() - b.posX(), y = a.posY() - b.posY(), z = a.posZ() - b.posZ();
        return x * x + y * y + z * z;
    }

    private static byte flips(GeoCube cube) {
        boolean x = cube.size().x() == 0, y = cube.size().y() == 0, z = cube.size().z() == 0;
        return (byte) ((y || z ? 1 : 0) | (x || z ? 2 : 0) | (x || y ? 4 : 0));
    }

    /** 六个面都在、都朝外、都不透明的方块，背面才一定看不见。任何一条拿不准就算不是。 */
    private static boolean closedAndOpaque(GeoCube cube, @Nullable NativeImage texture) {
        if (texture == null || cube.size().x() <= 0 || cube.size().y() <= 0 || cube.size().z() <= 0) return false;
        double cx = 0, cy = 0, cz = 0;
        int corners = 0;
        for (GeoQuad quad : cube.quads()) {
            if (quad == null) return false;
            for (GeoVertex vertex : quad.vertices()) {
                cx += vertex.posX();
                cy += vertex.posY();
                cz += vertex.posZ();
                corners++;
            }
        }
        if (corners != 6 * QUAD_VERTICES) return false;
        cx /= corners;
        cy /= corners;
        cz /= corners;
        for (GeoQuad quad : cube.quads()) {
            GeoVertex first = quad.vertices()[0];
            double outward = quad.normalX() * (first.posX() - cx) + quad.normalY() * (first.posY() - cy) + quad.normalZ() * (first.posZ() - cz);
            if (outward <= 0 || !opaque(quad, texture)) return false;
        }
        return true;
    }

    /** 这个面覆盖的贴图范围里没有会被 cutout 丢弃的像素。 */
    private static boolean opaque(GeoQuad quad, NativeImage texture) {
        float minU = 1, maxU = 0, minV = 1, maxV = 0;
        for (GeoVertex vertex : quad.vertices()) {
            minU = Math.min(minU, vertex.texU());
            maxU = Math.max(maxU, vertex.texU());
            minV = Math.min(minV, vertex.texV());
            maxV = Math.max(maxV, vertex.texV());
        }
        int w = texture.getWidth(), h = texture.getHeight();
        int x0 = Math.clamp((long) Math.floor(minU * w + 1e-3), 0, w - 1), x1 = Math.clamp((long) Math.ceil(maxU * w - 1e-3) - 1, x0, w - 1);
        int y0 = Math.clamp((long) Math.floor(minV * h + 1e-3), 0, h - 1), y1 = Math.clamp((long) Math.ceil(maxV * h - 1e-3) - 1, y0, h - 1);
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                if (texture.getPixel(x, y) >>> 24 < CUTOUT_ALPHA) return false;
            }
        }
        return true;
    }

    /** 方块总数和其中能跳过背面的数量，供基准场景打印。 */
    int[] cubeCounts() {
        int all = 0, cullable = 0;
        for (Node node : nodes) {
            for (int q = 0; q < node.flags.length; q++) {
                all++;
                if ((node.flags[q] & CULLABLE) != 0) cullable++;
            }
        }
        return new int[]{all, cullable};
    }

    /** 模型里名字以发光前缀开头的骨骼；神狐没有，这时就是空数组。 */
    GeoBone[] glowBones() {
        return glowBones;
    }

    /** 骨骼在 {@link #hidden} 结果里的下标；模型里没有这根骨骼时是 -1。 */
    int id(String bone) {
        return ids.getInt(bone);
    }

    /** 这个外观下每根骨骼（按 {@link #id}）是否隐藏，包括隐藏组下面的所有子骨骼。 */
    boolean[] hidden(LookKey key, NpcParts.Race race) {
        boolean[] cached = looks.get(key);
        if (cached != null) return cached;
        if (looks.size() >= MAX_LOOKS) looks.clear();
        Set<String> groups = new java.util.HashSet<>(race.hiddenGroups(key.appearance(), key.expression()));
        groups.addAll(key.extras());
        boolean[] hidden = new boolean[nodes.size()];
        for (Node root : roots) mark(root, groups, false, hidden);
        looks.put(new LookKey(Map.copyOf(key.appearance()), key.expression(), key.extras()), hidden);
        return hidden;
    }

    private static void mark(Node node, Set<String> groups, boolean parentHidden, boolean[] out) {
        boolean hidden = parentHidden || groups.contains(node.bone.name());
        out[node.id] = hidden;
        for (Node child : node.children) mark(child, groups, hidden, out);
    }

    /** 一只 NPC 这一次绘制的全部参数。 */
    private record Pass(RenderPassInfo<?> info, PoseStack stack, boolean @Nullable [] hidden, VertexConsumer out, @Nullable VertexSink bulk,
                        int light, int overlay, int color, float minEdge, Cull cull) {}

    /** 在 {@code RenderPassInfo.renderPosed} 里调用：骨骼快照这时已经挂上。 */
    void render(RenderPassInfo<?> info, PoseStack stack, boolean @Nullable [] hidden, VertexConsumer out, int light, int overlay, int color) {
        Matrix4f root = stack.last().pose();
        float scale = (float) Math.sqrt(root.m00() * root.m00() + root.m01() * root.m01() + root.m02() * root.m02());
        // 物品栏、Jade 这类预览按界面坐标放大绘制，原点不是镜头，距离和背面的判断都不成立，所以什么都不跳过
        Cull cull = Cull.NONE;
        float minEdge = 0;
        if (scale < MAX_WORLD_SCALE) {
            if (ShaderCompat.renderingShadowPass()) {
                cull = Cull.LIGHT;
                // xy 平面垂直于光线，原点是玩家：阴影贴图的精度就是按这个距离往外变低的
                float fromPlayer = (float) Math.sqrt(root.m30() * root.m30() + root.m31() * root.m31());
                minEdge = Math.max(SHADOW_MIN_EDGE, SHADOW_MIN_EDGE_PER_BLOCK * fromPlayer) / scale;
            } else {
                cull = Cull.CAMERA;
                var mc = Minecraft.getInstance();
                float distance = (float) Math.sqrt(root.m30() * root.m30() + root.m31() * root.m31() + root.m32() * root.m32());
                float pixelsPerBlockAtOne = mc.getWindow().getHeight() / (2 * (float) Math.tan(Math.toRadians(mc.options.fov().get()) / 2));
                minEdge = MIN_FACE_PIXELS * distance / (pixelsPerBlockAtOne * scale);
            }
        }
        Pass pass = new Pass(info, stack, hidden, out, bulkSink(out), light, overlay, color, skipSmallFaces ? minEdge : 0, cull);
        staged = 0;
        for (Node node : roots) render(node, pass);
        if (pass.bulk != null) flush(pass.bulk);
    }

    private static @Nullable VertexSink bulkSink(VertexConsumer out) {
        if (!LITTLE_ENDIAN || !(out instanceof BulkVertexTarget target)) return null;
        if (target.logos_dimension$acceptsEntityVertices()) return target::logos_dimension$writeEntityVertices;
        return target.logos_dimension$acceptsQuads() ? ShaderCompat.sodiumSink(target) : null;
    }

    private void render(Node node, Pass pass) {
        boolean[] hidden = pass.hidden;
        if (hidden != null && hidden[node.id]) return;
        GeoBone bone = node.bone;
        PoseStack stack = pass.stack;
        stack.pushPose();
        RenderUtil.prepMatrixForBoneAndUpdateListeners(stack, bone, pass.info);
        var snapshot = bone.frameSnapshot;
        if (snapshot == null || !snapshot.isHidden()) {
            if (bone instanceof CuboidGeoBone) emit(node, stack.last(), pass);
            else {
                if (pass.bulk != null) flush(pass.bulk);
                bone.render(pass.info, stack, pass.out, pass.light, pass.overlay, pass.color);
            }
        }
        if (snapshot == null || !snapshot.areChildrenHidden()) {
            for (Node child : node.children) render(child, pass);
        }
        stack.popPose();
    }

    /** 把攒下的顶点一次写进缓冲区。 */
    private static void flush(VertexSink bulk) {
        if (staged == 0) return;
        bulk.write(STAGING, staged);
        staged = 0;
        bulkWrites++;
    }

    /** 批量写入发生过多少次。基准场景用它确认实际渲染走的确实是批量写入。 */
    static int bulkWrites() {
        return bulkWrites;
    }

    private static int packedNormal(float x, float y, float z) {
        return normalByte(x) | normalByte(y) << 8 | normalByte(z) << 16;
    }

    /** 和 {@code BufferBuilder.normalIntValue} 一样的换算。 */
    private static int normalByte(float c) {
        return (int) (Mth.clamp(c, -1, 1) * 127) & 255;
    }

    private static void emit(Node node, PoseStack.Pose pose, Pass pass) {
        float[] v = node.vertices, n = node.normals, sizes = pass.cull == Cull.LIGHT ? node.narrowEdges : node.sizes;
        byte[] flags = node.flags;
        if (flags.length == 0) return;
        VertexConsumer out = pass.out;
        VertexSink bulk = pass.bulk;
        int light = pass.light, overlay = pass.overlay, color = pass.color, abgr = ARGB.toABGR(color);
        float minEdge = pass.minEdge;
        Cull cull = pass.cull;
        if (bulk != null && staged + flags.length * QUAD_VERTICES * VERTEX_INTS > STAGING.length) flush(bulk);
        int[] s = STAGING;
        int w = staged, start = w;
        Matrix4f m = pose.pose();
        Matrix3f r = pose.normal();
        float m00 = m.m00(), m01 = m.m01(), m02 = m.m02();
        float m10 = m.m10(), m11 = m.m11(), m12 = m.m12();
        float m20 = m.m20(), m21 = m.m21(), m22 = m.m22();
        float m30 = m.m30(), m31 = m.m31(), m32 = m.m32();
        float r00 = r.m00(), r01 = r.m01(), r02 = r.m02();
        float r10 = r.m10(), r11 = r.m11(), r12 = r.m12();
        float r20 = r.m20(), r21 = r.m21(), r22 = r.m22();
        for (int q = 0, i = 0; q < flags.length; q++) {
            if (sizes[q] < minEdge) {
                i += QUAD_FLOATS;
                continue;
            }
            float a = n[q * 3], b = n[q * 3 + 1], c = n[q * 3 + 2];
            float nx = r00 * a + r10 * b + r20 * c;
            float ny = r01 * a + r11 * b + r21 * c;
            float nz = r02 * a + r12 * b + r22 * c;
            int flag = flags[q];
            boolean back = false;
            if (cull == Cull.CAMERA && (flag & CULLABLE) != 0) {
                float x = v[i], y = v[i + 1], z = v[i + 2];
                back = nx * (m00 * x + m10 * y + m20 * z + m30) + ny * (m01 * x + m11 * y + m21 * z + m31) + nz * (m02 * x + m12 * y + m22 * z + m32) > BACK_FACE_MARGIN;
            } else if (cull == Cull.LIGHT && (flag & PAIRED) != 0) {
                back = nz < 0;
            }
            if (back) {
                i += QUAD_FLOATS;
                continue;
            }
            if ((flag & FLIPS) != 0) {
                if ((flag & 1) != 0 && nx < 0) nx = -nx;
                if ((flag & 2) != 0 && ny < 0) ny = -ny;
                if ((flag & 4) != 0 && nz < 0) nz = -nz;
            }
            if (bulk != null) {
                int normal = packedNormal(nx, ny, nz);
                for (int k = 0; k < QUAD_VERTICES; k++, i += VERTEX_FLOATS, w += VERTEX_INTS) {
                    float x = v[i], y = v[i + 1], z = v[i + 2];
                    // 布局同 BufferBuilder 对 DefaultVertexFormat.ENTITY 的写法
                    s[w] = Float.floatToRawIntBits(m00 * x + m10 * y + m20 * z + m30);
                    s[w + 1] = Float.floatToRawIntBits(m01 * x + m11 * y + m21 * z + m31);
                    s[w + 2] = Float.floatToRawIntBits(m02 * x + m12 * y + m22 * z + m32);
                    s[w + 3] = abgr;
                    s[w + 4] = Float.floatToRawIntBits(v[i + 3]);
                    s[w + 5] = Float.floatToRawIntBits(v[i + 4]);
                    s[w + 6] = overlay;
                    s[w + 7] = light;
                    s[w + 8] = normal;
                }
            } else {
                for (int k = 0; k < QUAD_VERTICES; k++, i += VERTEX_FLOATS) {
                    float x = v[i], y = v[i + 1], z = v[i + 2];
                    out.addVertex(m00 * x + m10 * y + m20 * z + m30, m01 * x + m11 * y + m21 * z + m31, m02 * x + m12 * y + m22 * z + m32,
                            color, v[i + 3], v[i + 4], overlay, light, nx, ny, nz);
                }
            }
        }
        QUADS[cull == Cull.LIGHT ? 1 : 0] += (w - start) / (VERTEX_INTS * QUAD_VERTICES);
        staged = w;
    }

    /**
     * 核对用：同一个姿势下，把每根骨骼分别用这里和 GeckoLib 画一遍，返回第一处不一致的描述，全部一致返回 null。
     * 只在 {@code npc_fox_render_bench} 场景里用。
     */
    @Nullable String compareWithGeckoLib(PoseStack.Pose pose) {
        for (Node node : nodes) {
            if (!(node.bone instanceof CuboidGeoBone bone)) continue;
            VertexLog ours = new VertexLog(), theirs = new VertexLog();
            emit(node, pose, new Pass(null, null, null, ours, null, LIGHT, OVERLAY, -1, 0, Cull.NONE));
            for (GeoCube cube : bone.cubes) {
                PoseStack stack = new PoseStack();
                stack.last().set(pose);
                cube.render(stack, theirs, LIGHT, OVERLAY, -1);
            }
            String difference = ours.compare(theirs);
            if (difference != null) return node.bone.name() + "：" + difference;
        }
        return null;
    }

    /**
     * 核对用：同样的顶点一个走 {@code BufferBuilder.addVertex}，一个走批量写入，写出来的字节要一样（每个顶点最后一个字节是填充，不比）。
     * 再各接一个普通顶点，确认批量写入之后缓冲区的状态也对。只在 {@code npc_fox_render_bench} 场景里用。
     */
    @Nullable String compareBulkWrite(PoseStack.Pose pose) {
        String direct = compareBulkWrite(pose, target -> target.logos_dimension$acceptsEntityVertices() ? target::logos_dimension$writeEntityVertices : null);
        return direct != null ? "直接写入：" + direct : compareBulkWrite(pose, ShaderCompat::sodiumSink);
    }

    private @Nullable String compareBulkWrite(PoseStack.Pose pose, java.util.function.Function<BulkVertexTarget, @Nullable VertexSink> sinks) {
        try (ByteBufferBuilder slowBytes = new ByteBufferBuilder(1 << 20); ByteBufferBuilder fastBytes = new ByteBufferBuilder(1 << 20)) {
            BufferBuilder slow = new BufferBuilder(slowBytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.ENTITY);
            BufferBuilder fast = new BufferBuilder(fastBytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.ENTITY);
            // 没装 Sodium 时只比直接写入
            VertexSink sink = fast instanceof BulkVertexTarget target ? sinks.apply(target) : null;
            if (sink == null) return null;
            staged = 0;
            for (Node node : nodes) {
                emit(node, pose, new Pass(null, null, null, slow, null, LIGHT, OVERLAY, -1, 0, Cull.NONE));
                emit(node, pose, new Pass(null, null, null, fast, sink, LIGHT, OVERLAY, -1, 0, Cull.NONE));
            }
            flush(sink);
            for (BufferBuilder builder : List.of(slow, fast)) {
                for (int i = 0; i < QUAD_VERTICES; i++) builder.addVertex(i, 2, 3, -1, .25f, .5f, OVERLAY, LIGHT, 0, 1, 0);
            }
            try (MeshData a = slow.buildOrThrow(); MeshData b = fast.buildOrThrow()) {
                if (a.drawState().vertexCount() != b.drawState().vertexCount()) return "顶点数 " + a.drawState().vertexCount() + " 对 " + b.drawState().vertexCount();
                var x = a.vertexBuffer();
                var y = b.vertexBuffer();
                if (x.remaining() != y.remaining()) return "字节数 " + x.remaining() + " 对 " + y.remaining();
                int size = DefaultVertexFormat.ENTITY.getVertexSize();
                for (int i = 0; i < x.remaining(); i++) {
                    if (i % size != size - 1 && x.get(x.position() + i) != y.get(y.position() + i)) return "第 " + i / size + " 个顶点的第 " + i % size + " 个字节不同";
                }
            }
        }
        return null;
    }

    /** 把写进来的顶点记下来，供 {@link #compareWithGeckoLib} 比较。 */
    private static final class VertexLog implements VertexConsumer {
        private static final float TOLERANCE = 2e-4f;
        private final List<float[]> vertices = new ArrayList<>();
        private float[] current;

        @Override public VertexConsumer addVertex(float x, float y, float z) {
            current = new float[]{x, y, z, 0, 0, 0, 0, 0};
            vertices.add(current);
            return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setColor(int color) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { current[3] = u; current[4] = v; return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { current[5] = x; current[6] = y; current[7] = z; return this; }
        @Override public VertexConsumer setLineWidth(float width) { return this; }

        @Nullable String compare(VertexLog other) {
            if (vertices.size() != other.vertices.size()) return "顶点数 " + vertices.size() + " 对 " + other.vertices.size();
            for (int i = 0; i < vertices.size(); i++) {
                float[] a = vertices.get(i), b = other.vertices.get(i);
                for (int k = 0; k < a.length; k++) {
                    if (Math.abs(a[k] - b[k]) > TOLERANCE) return "第 " + i + " 个顶点的第 " + k + " 项 " + a[k] + " 对 " + b[k];
                }
            }
            return null;
        }
    }
}
