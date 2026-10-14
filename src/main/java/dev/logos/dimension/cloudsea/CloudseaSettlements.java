package dev.logos.dimension.cloudsea;

import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Chunk-independent building samples prevent houses being cut off at a generation boundary. */
public final class CloudseaSettlements {
    public record House(int x,int y,int z,boolean tower) {}
    public record Village(int x,int z,List<House> houses) {}
    private final CloudseaTerrain terrain;
    private final LinkedHashMap<Long,Village> cache=new LinkedHashMap<>(32,.75f,true);
    public CloudseaSettlements(CloudseaTerrain terrain){this.terrain=terrain;}
    public synchronized Village village(CloudseaTerrain.Region region) {
        if(CloudseaNoise.unit(CloudseaNoise.hash(region.gx(),1,region.gz(),terrain.seed()+887))>.42)return null;
        long key=((long)region.gx()<<32)^(region.gz()&0xffffffffL);
        var old=cache.get(key);if(old!=null)return old;
        int x=region.x(),z=region.z();
        var offsets=new int[][]{{-28,-18},{0,-22},{28,-18},{-28,20},{0,22},{28,20},{-3,0}};
        var houses=new java.util.ArrayList<House>();
        for(int i=0;i<offsets.length;i++){
            int hx=x+offsets[i][0],hz=z+offsets[i][1],y=terrain.column(hx,hz).highest();
            for(int dx:new int[]{-5,5})for(int dz:new int[]{-6,6})y=Math.max(y,terrain.column(hx+dx,hz+dz).highest());
            houses.add(new House(hx,y+1,hz,i==6));
        }
        var result=new Village(x,z,List.copyOf(houses));cache.put(key,result);
        if(cache.size()>128)cache.remove(cache.keySet().iterator().next());
        return result;
    }
    public Village sample(int x,int z){var r=terrain.nearest(x,z);return Math.abs(x-r.x())<48&&Math.abs(z-r.z())<45?village(r):null;}
    public BlockState blockAt(Village village,int x,int y,int z,int ground) {
        if(village==null)return null;
        for(var h:village.houses){
            int dx=x-h.x,dz=z-h.z,dy=y-h.y,w=h.tower?2:4,d=h.tower?2:5,height=h.tower?20:5;
            if(Math.abs(dx)>w+1||Math.abs(dz)>d+1)continue;
            if(dy<0&&y>ground&&Math.abs(dx)<=w&&Math.abs(dz)<=d&&(dy>=-3||Math.abs(dx)==w&&Math.abs(dz)==d))return CloudseaBlocks.IVORY_BRICKS.get().defaultBlockState();
            if(Math.abs(dx)<=w&&Math.abs(dz)<=d&&dy==0)return CloudseaBlocks.DARK_TIMBER.get().defaultBlockState();
            if(dy>0&&dy<height&&Math.abs(dx)<=w&&Math.abs(dz)<=d){
                boolean wall=Math.abs(dx)==w||Math.abs(dz)==d;
                if(!h.tower&&dz==-d&&dx==0&&dy<=2)return Blocks.AIR.defaultBlockState();
                if(wall){
                    if(Math.abs(dx)==w&&Math.abs(dz)==d||dy%5==0)return CloudseaBlocks.DARK_TIMBER.get().defaultBlockState();
                    if((dy%5==2||dy%5==3)&&(Math.abs(dx)==w&&dz%3==0||Math.abs(dz)==d&&dx%3==0))return CloudseaBlocks.WINDOW.get().defaultBlockState();
                    if(h.tower&&dy>=15&&dy<=18&&Math.abs(dx)+Math.abs(dz)==w)return Blocks.AIR.defaultBlockState();
                    return CloudseaBlocks.IVORY_BRICKS.get().defaultBlockState();
                }
                if(h.tower&&dy%5==0)return CloudseaBlocks.DARK_TIMBER.get().defaultBlockState();
                if(!h.tower&&dy==1&&dx==2&&dz==2)return CloudseaBlocks.DARK_TIMBER.get().defaultBlockState();
                return Blocks.AIR.defaultBlockState();
            }
            if(!h.tower&&dy>=5&&dy<=10){
                int roof=10-Math.abs(dx);
                if(dy==roof)return CloudseaBlocks.SLATE.get().defaultBlockState();
                if(Math.abs(dz)==d&&dy<roof&&Math.abs(dx)<=w)return CloudseaBlocks.IVORY_BRICKS.get().defaultBlockState();
                if(dy<roof)return Blocks.AIR.defaultBlockState();
            }
            if(h.tower&&dy>=20&&dy<=26&&Math.abs(dx)<=Math.max(0,3-(dy-20)/2)&&Math.abs(dz)<=Math.max(0,3-(dy-20)/2))return CloudseaBlocks.SLATE.get().defaultBlockState();
        }
        if(Math.abs(x-village.x)<3||Math.abs(z-village.z)<3){if(y==ground)return CloudseaBlocks.IVORY_BRICKS.get().defaultBlockState();}
        if(Math.abs(x-village.x)==12&&Math.abs(z-village.z)%16==0){
            if(y>ground&&y<ground+4)return CloudseaBlocks.DARK_TIMBER.get().defaultBlockState();
            if(y==ground+4)return CloudseaBlocks.WINDOW.get().defaultBlockState();
        }
        return null;
    }
}
