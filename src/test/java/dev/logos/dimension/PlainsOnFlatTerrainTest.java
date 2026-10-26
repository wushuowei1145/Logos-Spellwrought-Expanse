package dev.logos.dimension;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 草原群系必须长在最平的地形上。
 *
 * <p>原版平原看起来平，是因为平原被分配给了"侵蚀程度最高（地形最平）"的格子；我们之前把
 * 五个草原群系按气候轮转分布，结果它们会落到陡坡和沟壑上。这个测试钉住新的分配规则：
 *
 * <ul>
 *   <li>长风草原 / 野花草甸 / 高地草甸 → 只出现在侵蚀 0.65（最平）的格子；</li>
 *   <li>牧草坡地 → 侵蚀 0.15（缓坡）；</li>
 *   <li>旷野丘陵 → 侵蚀 -0.65（起伏最大）。</li>
 * </ul>
 */
class PlainsOnFlatTerrainTest {
    /** 气候参数可能是单值，也可能是区间（例如根洞穴群系用 [-1.2, 1.2] 覆盖全部）。 */
    private record Point(String biome, Set<Double> erosion){}

    private static Set<Double> numbers(JsonElement element){
        var out=new LinkedHashSet<Double>();
        if(element.isJsonArray()) for(var part:element.getAsJsonArray()) out.add(part.getAsDouble());
        else out.add(element.getAsDouble());
        return out;
    }

    private List<Point> climatePoints()throws IOException{
        try(var input=PlainsOnFlatTerrainTest.class.getResourceAsStream(
                "/data/logos_dimension/dimension/cloudsea.json")){
            assertNotNull(input,"找不到云海维度文件");
            var root=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
            var array=root.getAsJsonObject("generator").getAsJsonObject("biome_source").getAsJsonArray("biomes");
            var points=new ArrayList<Point>();
            for(var element:array){
                var entry=element.getAsJsonObject();
                var params=entry.getAsJsonObject("parameters");
                points.add(new Point(entry.get("biome").getAsString(),numbers(params.get("erosion"))));
            }
            return points;
        }
    }

    @Test void flatPlainsOnlyAppearOnFlatErosion()throws Exception{
        var points=climatePoints();
        assertTrue(points.size()>1000,"气候点数量异常："+points.size());
        var flat=new String[]{"logos_dimension:cloudsea_silk_folds",
            "logos_dimension:cloudsea_rose_dunes",
            "logos_dimension:cloudsea_blue_bell_steps"};
        for(String biome:flat){
            var erosions=new TreeSet<Double>();
            for(var point:points)
                if(point.biome().equals(biome)) erosions.addAll(point.erosion());
            assertFalse(erosions.isEmpty(),"群系没有分配到任何气候点："+biome);
            assertEquals(new TreeSet<>(Set.of(0.65)),erosions,
                biome+" 应只出现在侵蚀 0.65（最平）的格子，实际："+erosions);
        }
    }

    @Test void rollingBiomesFollowTheirOwnErosion()throws Exception{
        var points=climatePoints();
        for(var expected:Map.of("logos_dimension:cloudsea_amber_whirls",0.15,
                                "logos_dimension:cloudsea_dawn_ribbons",-0.65).entrySet()){
            var erosions=new TreeSet<Double>();
            for(var point:points)
                if(point.biome().equals(expected.getKey())) erosions.addAll(point.erosion());
            assertEquals(new TreeSet<>(Set.of(expected.getValue())),erosions,
                expected.getKey()+" 的侵蚀档位不对："+erosions);
        }
    }

    /** 草原整体占比不能因为改分配而崩掉（原来约 6.7%）。 */
    @Test void plainsStillCoverEnoughClimateSpace()throws Exception{
        var plains=Set.of("logos_dimension:cloudsea_silk_folds",
            "logos_dimension:cloudsea_rose_dunes",
            "logos_dimension:cloudsea_blue_bell_steps",
            "logos_dimension:cloudsea_amber_whirls",
            "logos_dimension:cloudsea_dawn_ribbons");
        int total=0, plain=0;
        for(var point:climatePoints()){
            total++;
            if(plains.contains(point.biome())) plain++;
        }
        double share=100.0*plain/total;
        assertTrue(share>4.0&&share<12.0,"草原占比异常："+plain+"/"+total+" = "+share+"%");
    }
}
