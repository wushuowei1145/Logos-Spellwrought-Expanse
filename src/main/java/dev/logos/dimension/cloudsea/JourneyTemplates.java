package dev.logos.dimension.cloudsea;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.world.level.block.state.BlockState;

/** Immutable, converted internet blueprints; no downloads or palette parsing during chunk generation. */
public final class JourneyTemplates {
    public record Template(int width,int height,int depth,BlockState[] palette,int[] blocks){
        public BlockState at(int x,int y,int z){if(x<0||x>=width||y<0||y>=height||z<0||z>=depth)return null;int index=blocks[(y*depth+z)*width+x];return index<0?null:palette[index];}
    }
    private static final Map<String,Template> TEMPLATES=load();
    private static Map<String,Template> load(){
        var result=new HashMap<String,Template>();
        for(String name:List.of("cottage","bakery","guardhouse","inn","farm","townhouse","townhouse_three","townhouse_four")){
            String path="/data/logos_dimension/journey_templates/"+name+".json";
            try(var stream=Objects.requireNonNull(JourneyTemplates.class.getResourceAsStream(path),path);
                var reader=new InputStreamReader(stream,StandardCharsets.UTF_8)){
                var json=JsonParser.parseReader(reader).getAsJsonObject();
                var palette=json.getAsJsonArray("palette").asList().stream().map(p->BlockState.CODEC.parse(JsonOps.INSTANCE,p).getOrThrow()).toArray(BlockState[]::new);
                var blocks=json.getAsJsonArray("blocks").asList().stream().mapToInt(p->p.getAsInt()).toArray();
                int width=json.get("width").getAsInt(),height=json.get("height").getAsInt(),depth=json.get("depth").getAsInt();
                if(blocks.length!=width*height*depth)throw new IllegalStateException("Invalid blueprint "+name);
                result.put(name,new Template(width,height,depth,palette,blocks));
            }catch(Exception e){throw new IllegalStateException("Cannot load medieval blueprint "+name,e);}
        }
        return Map.copyOf(result);
    }
    public static Template get(String name){return Objects.requireNonNull(TEMPLATES.get(name),name);}
    private JourneyTemplates(){}
}
