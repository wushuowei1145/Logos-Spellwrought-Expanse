package dev.logos.dimension;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 草原使用连片的平地；长风主体通过原版密度函数压低起伏。
 *
 * <p>原版平原看起来平，是因为平原被分配给了"侵蚀程度最高（地形最平）"的格子；我们之前把
 * 五个草原群系按气候轮转分布，结果它们会落到陡坡和沟壑上。这个测试钉住新的分配规则：
 *
 * <ul>
 *   <li>长风草原主体扩展为连续气候范围（由数据包压平），其余平原仍在侵蚀 0.65 档；</li>
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
            assertEquals(new TreeSet<>(biome.equals("logos_dimension:cloudsea_silk_folds")?Set.of(.15,.65,1.2):Set.of(.65)),erosions,
                biome+" 的平地气候档位不符，实际："+erosions);
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

    @Test void longwindHasAContinuousTemperateBandInsteadOfRidgeStripes()throws Exception{
        try(var input=getClass().getResourceAsStream("/data/logos_dimension/dimension/cloudsea.json")){
            var entries=JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(input),StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("generator").getAsJsonObject("biome_source").getAsJsonArray("biomes");
            boolean continuous=false;
            var columns=new HashMap<String,String>();
            for(var e:entries){
                var entry=e.getAsJsonObject();String id=entry.get("biome").getAsString();
                var p=entry.getAsJsonObject("parameters");
                if(id.equals("logos_dimension:cloudsea_silk_folds")&&p.get("temperature").isJsonArray()){
                    assertEquals(.15,p.getAsJsonArray("erosion").get(0).getAsDouble(),1e-8);
                    assertEquals(1.2,p.getAsJsonArray("erosion").get(1).getAsDouble(),1e-8);
                    assertTrue(p.getAsJsonArray("humidity").get(1).getAsDouble()>=0);
                    assertTrue(p.getAsJsonArray("weirdness").get(0).getAsDouble()<=-1);
                    assertTrue(p.getAsJsonArray("weirdness").get(1).getAsDouble()>=1);
                    continuous=true;continue;
                }
                if(!Set.of("logos_dimension:cloudsea_silk_folds","logos_dimension:cloudsea_blue_bell_steps","logos_dimension:cloudsea_rose_dunes").contains(id))continue;
                String key=p.get("temperature")+"/"+p.get("humidity")+"/"+p.get("continentalness")+"/"+p.get("erosion");
                String previous=columns.putIfAbsent(key,id);
                if(previous!=null)assertEquals(previous,id,"同一平坦气候带不能随山脊值切成多种小条");
            }
            assertTrue(continuous,"缺少连片温带平原的气候范围");
        }
    }
}
