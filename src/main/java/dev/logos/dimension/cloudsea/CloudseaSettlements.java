package dev.logos.dimension.cloudsea;

import java.util.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Sparse settlements seek a river bank and reject steep or submerged plots. Sampling is chunk-independent. */
public final class CloudseaSettlements {
    public record House(int x,int y,int z,boolean tower,String template,int turn){}
    public record Village(int x,int z,List<House> houses,boolean town,int floor){}
    private final CloudseaTerrain terrain;
    private final Map<Long,Optional<Village>> cache=new LinkedHashMap<>(32,.75f,true);
    public CloudseaSettlements(CloudseaTerrain terrain){this.terrain=terrain;}
    public synchronized Village village(CloudseaTerrain.Region region){
        long key=((long)region.gx()<<32)^(region.gz()&0xffffffffL);var cached=cache.get(key);if(cached!=null)return cached.orElse(null);
        var result=create(region);cache.put(key,Optional.ofNullable(result));if(cache.size()>128)cache.remove(cache.keySet().iterator().next());return result;
    }
    private Village create(CloudseaTerrain.Region region){
        long seed=CloudseaNoise.hash(region.gx(),1,region.gz(),terrain.seed()+887);
        if(CloudseaNoise.unit(seed)>.46)return null;
        int z=region.z();int basin=(int)Math.round(region.x()/3100.0),x=(int)terrain.riverX(basin,z)+115;
        var center=terrain.column(x,z);if(center.ocean()||center.ground()>170||center.ground()<=center.water())return null;
        boolean town=CloudseaNoise.unit(seed^927123)<.16;var houses=new ArrayList<House>();
        if(town){
            for(int row=-3;row<=3;row++)for(int col=-3;col<=3;col++){
                if(col==0||row==0)continue;
                add(houses,x+col*13,z+row*15,(row+col)%7==0?"townhouse_four":(row+col)%4==0?"townhouse_three":(row+col)%3==0?"inn":"townhouse",col<0?1:3);
            }
            add(houses,x+47,z+41,"bakery",0);add(houses,x-46,z-42,"guardhouse",2);
        }else{
            add(houses,x-25,z-23,"cottage",1);add(houses,x+31,z-29,"farm",3);
            add(houses,x+28,z+31,"bakery",3);add(houses,x-26,z+32,"inn",1);
            add(houses,x+5,z+57,"guardhouse",0);
        }
        if(houses.size()<(town?8:3))return null;
        return new Village(x,z,List.copyOf(houses),town,center.ground());
    }
    private void add(List<House> houses,int x,int z,String name,int turn){
        var t=JourneyTemplates.get(name);int extent=Math.max(t.width(),t.depth())/2+1,min=1000,max=-1000;
        for(int dx:new int[]{-extent,0,extent})for(int dz:new int[]{-extent,0,extent}){var c=terrain.column(x+dx,z+dz);if(c.ground()<=c.water())return;min=Math.min(min,c.ground());max=Math.max(max,c.ground());}
        if(max-min>8)return;
        houses.add(new House(x,max,z,name.equals("guardhouse"),name,turn));
    }
    public Village sample(int x,int z){
        int gx=Math.floorDiv(x,CloudseaTerrain.REGION_SIZE),gz=Math.floorDiv(z,CloudseaTerrain.REGION_SIZE);
        var v=village(terrain.region(gx,gz));if(v!=null&&Math.abs(x-v.x)<132&&Math.abs(z-v.z)<132)return v;
        return null;
    }
    public BlockState blockAt(Village village,int x,int y,int z,int ground){
        if(village==null)return ruin(x,y,z,ground);
        for(var h:village.houses){
            var t=JourneyTemplates.get(h.template);int dx=x-h.x,dz=z-h.z;
            int lx=switch(h.turn){case 1->-dz;case 2->-dx;case 3->dz;default->dx;}+t.width()/2;
            int lz=switch(h.turn){case 1->dx;case 2->-dz;case 3->-dx;default->dz;}+t.depth()/2;
            if(lx<0||lx>=t.width()||lz<0||lz>=t.depth())continue;
            if(y<h.y&&y>ground)return Blocks.STONE_BRICKS.defaultBlockState();
            var state=t.at(lx,y-h.y,lz);if(state!=null)return state.rotate(Rotation.values()[h.turn]);
        }
        int dx=x-village.x,dz=z-village.z;
        if(village.town&&Math.abs(dx)<60&&Math.abs(dz)<61){
            boolean edge=Math.abs(dx)==57||Math.abs(dz)==59;
            boolean gate=Math.abs(dx)<3||Math.abs(dz)<3;
            if(edge&&!gate&&y>ground&&y<=ground+6)return (y==ground+6&&(x+z)%2==0?Blocks.STONE_BRICK_SLAB:Blocks.STONE_BRICKS).defaultBlockState();
            if(Math.abs(dx)>51&&Math.abs(dz)>53&&y>ground&&y<=ground+12){boolean wall=Math.abs(dx)==52||Math.abs(dx)==57||Math.abs(dz)==54||Math.abs(dz)==59;return (wall||y==ground+9?Blocks.STONE_BRICKS:Blocks.AIR).defaultBlockState();}
        }
        if(village.town&&Math.abs(dx)<57&&Math.abs(dz)<59&&y==ground)return ((x*13+z*7)%11==0?Blocks.MOSSY_COBBLESTONE:Blocks.COBBLESTONE).defaultBlockState();
        if(village.town&&((Math.abs(dx)>59&&Math.abs(dx)<63&&Math.abs(dz)<64)||(Math.abs(dz)>61&&Math.abs(dz)<65&&Math.abs(dx)<64))){
            if(y==ground)return Blocks.WATER.defaultBlockState();
            if(y==ground+1)return Blocks.AIR.defaultBlockState();
        }
        int pathX=dx-(int)(Math.sin(dz/29.0)*2),pathZ=dz-(int)(Math.sin(dx/31.0)*2);
        if((Math.abs(pathX)<3||Math.abs(pathZ)<2)&&y==ground)return (village.town?Blocks.COBBLESTONE:Blocks.DIRT_PATH).defaultBlockState();
        if(!village.town&&Math.abs(dx)>48&&Math.abs(dx)<84&&Math.abs(dz)<55){
            boolean water=Math.floorMod(x,9)==0;
            if(y==ground)return (water?Blocks.WATER.defaultBlockState():Blocks.FARMLAND.defaultBlockState().setValue(BlockStateProperties.MOISTURE,7));
            if(!water&&y==ground+1)return Blocks.WHEAT.defaultBlockState().setValue(BlockStateProperties.AGE_7,7);
        }
        if(!village.town&&dx>42&&dx<76&&dz>66&&dz<104){
            if(y==ground+1&&Math.floorMod(x,4)==0)return CloudseaBlocks.GRAPEVINE.get().defaultBlockState();
            if(y==ground+1&&Math.floorMod(z,9)==0)return Blocks.OAK_FENCE.defaultBlockState();
        }
        if(!village.town&&dx>-88&&dx<-42&&dz>65&&dz<108&&y>ground&&y<ground+10){
            int ax=(int)Math.round((dx+65)/12.0)*12-65,az=(int)Math.round((dz-80)/14.0)*14+80;
            int localX=dx-ax,localZ=dz-az,dy=y-terrain.column(village.x+ax,village.z+az).ground();
            if(localX==0&&localZ==0&&dy<=4)return Blocks.OAK_LOG.defaultBlockState();
            if(dy>=3&&dy<=6&&Math.abs(localX)<=2&&Math.abs(localZ)<=2&&localX*localX+localZ*localZ<8)
                return Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT,true);
        }
        return null;
    }
    private BlockState ruin(int x,int y,int z,int ground){
        if(y<ground-1||y>ground+3)return null;
        int gx=Math.floorDiv(x,1536),gz=Math.floorDiv(z,1536);long hash=CloudseaNoise.hash(gx,1,gz,terrain.seed()+991);
        if(CloudseaNoise.unit(hash)>.055)return null;
        int dx=x-(gx*1536+760),dz=z-(gz*1536+760);
        if(Math.abs(dx)>6||Math.abs(dz)>6)return null;
        var c=terrain.column(x,z);if(c.ocean()||c.river()||c.ground()>230)return null;
        if((Math.abs(dx)==5||Math.abs(dz)==5)&&y>=ground-1&&y<=ground+Math.floorMod(dx*7+dz*3,4))return (Math.floorMod(x+z,3)==0?Blocks.MOSSY_STONE_BRICKS:Blocks.CRACKED_STONE_BRICKS).defaultBlockState();
        return null;
    }
}
