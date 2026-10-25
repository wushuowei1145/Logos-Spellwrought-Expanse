package dev.logos.dimension;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 特征排序回归测试。
 *
 * <p>原版 {@code FeatureSorter} 会把整块维度里所有群系的 placed feature 按阶段展平成一串，
 * 相邻两个之间连一条"先于"边，再做拓扑排序。只要**同一份特征在两个群系里的相对先后
 * 不一致**（哪怕是不同阶段拼出来的），就会报 {@code Feature order cycle found}，
 * 表现为区块生成直接失败——游戏里就是新地形一片都刷不出来。
 *
 * <p>这个测试把同样的图在单元层重建一遍。已知的历史事故：蘑菇生成原本插在甘蔗/睡莲/露头
 * 之前，而精灵森林的第 9 阶段是"甘蔗 → 睡莲 → 露头 → 蘑菇"，两份顺序相反，形成环。
 */
class FeatureOrderTest {
    private static final List<String> BIOMES=List.of(
        "aether","cloudsea_amber_whirls","cloudsea_ancient_beech","cloudsea_aurora_dunes",
        "cloudsea_blue_bell_steps","cloudsea_cloudroot_shelves","cloudsea_copper_copse",
        "cloudsea_dawn_ribbons","cloudsea_demon_depths","cloudsea_dream_basins","cloudsea_echo_folds",
        "cloudsea_fernwood","cloudsea_fir_heart","cloudsea_frost_spires","cloudsea_glass_lagoons",
        "cloudsea_halo_spires","cloudsea_ivory_steps","cloudsea_jade_whirls","cloudsea_lantern_shelves",
        "cloudsea_lilac_steps","cloudsea_moon_birch","cloudsea_moon_folds","cloudsea_moss_vale",
        "cloudsea_opal_dunes","cloudsea_pale_spires","cloudsea_pearl_basins","cloudsea_reverse_gardens",
        "cloudsea_root_caves","cloudsea_rose_dunes","cloudsea_silk_folds","cloudsea_star_ribbons",
        "cloudsea_starflower_glade","cloudsea_sunlit_glade","cloudsea_tide_ribbons",
        "cloudsea_verdant_whirls","elven_forest","mercury","salt","sanctum","sulfur");

    private JsonObject json(String path)throws IOException{
        try(var input=getClass().getResourceAsStream("/data/logos_dimension/"+path+".json")){
            assertNotNull(input,path);
            return JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private List<String> flattened(String biome)throws IOException{
        var out=new ArrayList<String>();
        for(var step:json("worldgen/biome/"+biome).getAsJsonArray("features"))
            for(var feature:step.getAsJsonArray())out.add(feature.getAsString());
        return out;
    }

    @Test void noFeatureOrderCyclesAcrossBiomes()throws Exception{
        Map<String,Set<String>> edges=new LinkedHashMap<>();
        for(String biome:BIOMES){
            var list=flattened(biome);
            for(int i=0;i<list.size()-1;i++)
                edges.computeIfAbsent(list.get(i),k->new LinkedHashSet<>()).add(list.get(i+1));
        }
        var cycle=findCycle(edges);
        assertTrue(cycle.isEmpty(),"存在特征顺序环：" + String.join(" -> ",cycle));
    }

    /** 同一份特征不能同时出现在两个阶段，否则排序器无从取舍。 */
    @Test void sharedFeaturesKeepOneStep()throws Exception{
        Map<String,Set<Integer>> steps=new HashMap<>();
        for(String biome:BIOMES){
            var features=json("worldgen/biome/"+biome).getAsJsonArray("features");
            for(int i=0;i<features.size();i++)
                for(var feature:features.get(i).getAsJsonArray())
                    steps.computeIfAbsent(feature.getAsString(),k->new TreeSet<>()).add(i);
        }
        for(var entry:steps.entrySet())
            assertEquals(1,entry.getValue().size(),
                entry.getKey()+" 出现在多个生成阶段："+entry.getValue());
    }

    private List<String> findCycle(Map<String,Set<String>> edges){
        Map<String,Integer> color=new HashMap<>();
        var path=new ArrayList<String>();
        for(String node:edges.keySet()){
            var found=dfs(node,edges,color,path);
            if(found!=null)return found;
        }
        return List.of();
    }

    private List<String> dfs(String node,Map<String,Set<String>> edges,
                             Map<String,Integer> color,List<String> path){
        color.put(node,1);
        path.add(node);
        for(String next:edges.getOrDefault(node,Set.of())){
            int state=color.getOrDefault(next,0);
            if(state==1){
                var cycle=new ArrayList<>(path.subList(path.indexOf(next),path.size()));
                cycle.add(next);
                return cycle;
            }
            if(state==0){
                var found=dfs(next,edges,color,path);
                if(found!=null)return found;
            }
        }
        color.put(node,2);
        path.remove(path.size()-1);
        return null;
    }
}
