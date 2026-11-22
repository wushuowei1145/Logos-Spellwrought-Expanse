package dev.logos.dimension.client.jiansui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.logos.dimension.LogosDimension;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 第三阶段只看的碎块（docs/JIAN_SUI_BOSS.md 第 13 节）：断柱、斜着的地板块、下坠的魂灯、断链。每块是一组原版方块，
 * 用场地里那几种方块的原版模型拼成，和上面的场地看起来一模一样，不用新贴图。方块清单和摆放由 tools/taixuan_cavity.py
 * 生成在 {@code assets/logos_dimension/jian_sui/fall_debris.json}：摆放的 x、z 是相对空洞竖轴、按画布朝向的偏移，y 是世界高度。
 *
 * <p>每块碎块第一次画时把各方块的模型面烘到碎块自己的坐标里（挨着整块不透明方块的面去掉），之后每帧整块交一次。
 * 资源重载（换资源包）后模型集换了，就重烘。
 */
final class FallProps {
    record Block(BlockState state, int x, int y, int z) {}

    record Piece(String name, Vector3f center, float radius, List<Block> blocks) {}

    /**
     * 一块摆出来的碎块。{@code at} 是相对竖轴的 (dx, 世界 y, dz)；先按 {@code tilt}（度，绕 x、y、z）斜着放，
     * 再绕 {@code axis} 以每秒 {@code speed} 度慢慢翻转；{@code sway} 不为 0 时（断链）改成来回摆。
     */
    record Prop(String piece, Vector3f at, Vector3f axis, float speed, Vector3f tilt, float phase, float sway) {}

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static final String PATH = "jian_sui/fall_debris.json";
    private static @Nullable Map<String, Piece> pieces;
    private static @Nullable List<Prop> props;
    private static @Nullable Object bakedFor;
    private static final Map<String, List<BakedQuad>> BAKED = new HashMap<>();

    private FallProps() {}

    static List<Prop> props() {
        load();
        return props;
    }

    static @Nullable Piece piece(String name) {
        load();
        return pieces.get(name);
    }

    private static synchronized void load() {
        if (pieces != null) return;
        Map<String, Piece> out = new HashMap<>();
        List<Prop> placed = new ArrayList<>();
        try {
            var resource = Minecraft.getInstance().getResourceManager().getResource(LogosDimension.id(PATH));
            if (resource.isPresent()) {
                try (var reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    for (var entry : root.getAsJsonObject("pieces").entrySet()) {
                        var json = entry.getValue().getAsJsonObject();
                        List<Block> blocks = new ArrayList<>();
                        for (var b : json.getAsJsonArray("blocks")) {
                            var a = b.getAsJsonArray();
                            BlockState state = parse(a.get(0).getAsString());
                            if (state != null) blocks.add(new Block(state, a.get(1).getAsInt(), a.get(2).getAsInt(), a.get(3).getAsInt()));
                        }
                        var c = json.getAsJsonArray("center");
                        out.put(entry.getKey(), new Piece(entry.getKey(), new Vector3f(c.get(0).getAsFloat(), c.get(1).getAsFloat(), c.get(2).getAsFloat()),
                                json.get("radius").getAsFloat(), blocks));
                    }
                    for (var p : root.getAsJsonArray("props")) {
                        var json = p.getAsJsonObject();
                        placed.add(new Prop(json.get("piece").getAsString(), vec(json.getAsJsonArray("at")), vec(json.getAsJsonArray("axis")),
                                json.get("speed").getAsFloat(), vec(json.getAsJsonArray("tilt")), json.get("phase").getAsFloat(),
                                json.has("sway") ? json.get("sway").getAsFloat() : 0));
                    }
                }
            }
        } catch (Exception error) {
            LOG.warn("Jian Sui fall debris could not be read: {}", error.toString());
        }
        pieces = out;
        props = placed;
    }

    private static Vector3f vec(com.google.gson.JsonArray a) {
        return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
    }

    private static @Nullable BlockState parse(String text) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text, false).blockState();
        } catch (Exception error) {
            return null;
        }
    }

    /** 这块碎块烘好的模型面（以碎块中心为原点）。 */
    static List<BakedQuad> quads(Piece piece) {
        var models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        if (bakedFor != models) {
            BAKED.clear();
            bakedFor = models;
        }
        return BAKED.computeIfAbsent(piece.name(), k -> bake(piece));
    }

    private static List<BakedQuad> bake(Piece piece) {
        var models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        Map<BlockPos, BlockState> cells = new HashMap<>();
        for (Block b : piece.blocks()) cells.put(new BlockPos(b.x(), b.y(), b.z()), b.state());
        List<BakedQuad> out = new ArrayList<>();
        List<BlockStateModelPart> parts = new ArrayList<>();
        for (Block b : piece.blocks()) {
            parts.clear();
            models.get(b.state()).collectParts(RandomSource.create(b.x() * 31L + b.y() * 17L + b.z()), parts);
            float ox = b.x() - piece.center().x, oy = b.y() - piece.center().y, oz = b.z() - piece.center().z;
            for (BlockStateModelPart part : parts) {
                for (Direction d : Direction.values()) {
                    BlockState next = cells.get(new BlockPos(b.x() + d.getStepX(), b.y() + d.getStepY(), b.z() + d.getStepZ()));
                    if (next != null && next.isSolidRender()) continue;
                    for (BakedQuad q : part.getQuads(d)) out.add(moved(q, ox, oy, oz));
                }
                for (BakedQuad q : part.getQuads(null)) out.add(moved(q, ox, oy, oz));
            }
        }
        return out;
    }

    private static BakedQuad moved(BakedQuad q, float x, float y, float z) {
        return new BakedQuad(new Vector3f(q.position0()).add(x, y, z), new Vector3f(q.position1()).add(x, y, z),
                new Vector3f(q.position2()).add(x, y, z), new Vector3f(q.position3()).add(x, y, z),
                q.packedUV0(), q.packedUV1(), q.packedUV2(), q.packedUV3(), q.direction(), q.materialInfo(), q.bakedNormals(), q.bakedColors());
    }
}
