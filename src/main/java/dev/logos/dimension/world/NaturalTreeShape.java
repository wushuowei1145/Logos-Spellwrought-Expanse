package dev.logos.dimension.world;

import java.util.ArrayList;
import java.util.List;

/** Block-scale trees: connected wood supports overlapping, solid leaf clusters. No world or registry access. */
public final class NaturalTreeShape {
    public enum Form { ROUND, CONIFER, UMBRELLA, WEEPING }
    public enum Part { AIR, WOOD_Y, WOOD_X, WOOD_Z, LEAF }
    private static final Part[] PARTS=Part.values();
    public record Voxel(int x,int y,int z,Part part) {}
    public static final class Blueprint {
        private static final int WIDTH=11, HEIGHT=25;
        private final byte[] cells=new byte[WIDTH*WIDTH*HEIGHT];
        private int maxY;
        private void put(int x,int y,int z,Part part){
            if(Math.abs(x)>5||Math.abs(z)>5||y<0||y>=HEIGHT)return;
            int i=(y*WIDTH+z+5)*WIDTH+x+5;
            if(part!=Part.LEAF||cells[i]==0)cells[i]=(byte)part.ordinal();
            maxY=Math.max(maxY,y);
        }
        public Part at(int x,int y,int z){
            if(Math.abs(x)>5||Math.abs(z)>5||y<0||y>=HEIGHT)return Part.AIR;
            return PARTS[cells[(y*WIDTH+z+5)*WIDTH+x+5]];
        }
        public int maxY(){return maxY;}
        public List<Voxel> voxels(){
            var list=new ArrayList<Voxel>();
            for(int y=0;y<=maxY;y++)for(int z=-5;z<=5;z++)for(int x=-5;x<=5;x++){
                var p=at(x,y,z);if(p!=Part.AIR)list.add(new Voxel(x,y,z,p));
            }
            return List.copyOf(list);
        }
    }
    public static Blueprint create(Form form,int height,long seed){
        height=Math.clamp(height,6,15);
        var b=new Blueprint();
        for(int y=0;y<height;y++)b.put(0,y,0,Part.WOOD_Y);
        switch(form){
            case ROUND -> {
                crown(b,0,height-1,0,3.5,2.8,3.2,seed);
                for(int side=0;side<4;side++)branch(b,side,height-3,2);
                if((seed&1)==0)crown(b,2,height-2,-1,2.5,1.8,2.4,seed+7);
            }
            case CONIFER -> {
                for(int y=3;y<=height+1;y++){
                    int radius=y>=height?1:Math.min(4,1+(height-y)/3);
                    if(y%3==0&&y<height-1)radius=Math.max(1,radius-1);
                    for(int z=-radius;z<=radius;z++)for(int x=-radius;x<=radius;x++)
                        if(x*x+z*z<=radius*radius+1)b.put(x,y,z,Part.LEAF);
                    if(y%3==1&&radius>1)for(int side=0;side<4;side++)branch(b,side,y,radius-1);
                }
            }
            case UMBRELLA -> {
                int lean=(seed&1)==0?1:-1;
                for(int y=height-3;y<height;y++)b.put(lean,y,0,Part.WOOD_Y);
                b.put(lean,height-4,0,Part.WOOD_X);
                crown(b,lean,height,0,4.4,1.7,3.8,seed);
                crown(b,-lean*2,height-2,1,2.8,1.5,2.8,seed+13);
                for(int side=0;side<4;side++)branch(b,side,height-2,3);
            }
            case WEEPING -> {
                crown(b,0,height-1,0,4.3,2.2,4.0,seed);
                for(int side=0;side<4;side++)branch(b,side,height-3,3);
                for(int z=-4;z<=4;z++)for(int x=-4;x<=4;x++){
                    int r=x*x+z*z;
                    if(r<10||r>18||Math.floorMod(x*7+z*11+(int)seed,4)==0)continue;
                    int drop=2+Math.floorMod(x*3-z+(int)seed,3);
                    for(int y=height-2;y>=height-2-drop;y--)b.put(x,y,z,Part.LEAF);
                }
            }
        }
        return b;
    }
    private static void crown(Blueprint b,int cx,int cy,int cz,double rx,double ry,double rz,long seed){
        for(int y=cy-(int)Math.ceil(ry);y<=cy+ry;y++)for(int z=cz-(int)Math.ceil(rz);z<=cz+rz;z++)for(int x=cx-(int)Math.ceil(rx);x<=cx+rx;x++){
            double d=Math.pow((x-cx)/rx,2)+Math.pow((y-cy)/ry,2)+Math.pow((z-cz)/rz,2);
            // Only the silhouette loses occasional corners; the interior remains dense.
            if(d<=1.05&&(d<.80||Math.floorMod(x*13+y*7+z*17+(int)seed,7)!=0))b.put(x,y,z,Part.LEAF);
        }
    }
    private static void branch(Blueprint b,int side,int y,int length){
        int dx=side==0?1:side==1?-1:0,dz=side==2?1:side==3?-1:0;
        for(int i=1;i<=length;i++)b.put(dx*i,y,dz*i,dx!=0?Part.WOOD_X:Part.WOOD_Z);
        b.put(dx*length,y+1,dz*length,Part.WOOD_Y);
    }
    private NaturalTreeShape(){}
}
