package dev.logos.dimension.cloudsea;

import java.util.Arrays;

/** World-anchored, continuous cloud density and its cached isosurface; no collision or entities. */
public final class CloudseaCloudField {
    public static final int CELL_SIZE=256, STEP=16, MIN_Y=320, MAX_Y=496;
    public static final int CLOUDSEA_BOSS_CLOUD_ANCHOR_Y=470;
    private static final long SEED=0x59A7132D9L;
    private static final int NX=CELL_SIZE/STEP+1, NY=(MAX_Y-MIN_Y)/STEP+1;
    private static final int[][] CORNERS={{0,0,0},{1,0,0},{1,1,0},{0,1,0},{0,0,1},{1,0,1},{1,1,1},{0,1,1}};
    private static final int[][] TETRA={{0,5,1,6},{0,1,2,6},{0,2,3,6},{0,3,7,6},{0,7,4,6},{0,4,5,6}};
    public record Mesh(int cellX,int cellZ,float[] vertices) {
        public int triangles(){return vertices.length/18;}
    }
    public static double cleftLateral(double x,double z){return x*.8191520443+z*.5735764364;}
    public static double cleftAlong(double x,double z){return -x*.5735764364+z*.8191520443;}
    public static double density(double x,double y,double z){
        // Joined, jittered billows give the cloud rounded lobes rather than a snow-like height field.
        double center=405+CloudseaNoise.noise(x/430,0,z/430,SEED)*9;
        double body=.18-Math.pow((y-center)/25,2);
        int gx=(int)Math.floor(x/128),gz=(int)Math.floor(z/128);
        for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++){
            int px=gx+dx,pz=gz+dz;
            double cx=px*128+64+(CloudseaNoise.unit(CloudseaNoise.hash(px,1,pz,SEED))-.5)*42;
            double cz=pz*128+64+(CloudseaNoise.unit(CloudseaNoise.hash(px,2,pz,SEED))-.5)*42;
            double cy=405+(CloudseaNoise.unit(CloudseaNoise.hash(px,3,pz,SEED))-.5)*36;
            double rx=104+CloudseaNoise.unit(CloudseaNoise.hash(px,4,pz,SEED))*39;
            double rz=99+CloudseaNoise.unit(CloudseaNoise.hash(px,5,pz,SEED))*35;
            double ry=32+CloudseaNoise.unit(CloudseaNoise.hash(px,6,pz,SEED))*24;
            double puff=1-Math.pow((x-cx)/rx,2)-Math.pow((y-cy)/ry,2)-Math.pow((z-cz)/rz,2);
            double blend=Math.max(.3-Math.abs(body-puff),0)/.3;
            body=Math.max(body,puff)+blend*blend*.075;
        }
        body+=.20*CloudseaNoise.noise(x/27,y/24,z/27,SEED+31);
        double along=cleftAlong(x,z);
        double width=84+Math.sin(along/190)*13+CloudseaNoise.noise(along/90,0,0,SEED+71)*9;
        double lip=(Math.abs(cleftLateral(x,z))-width)/15
                +.16*CloudseaNoise.noise(x/29,y/25,z/29,SEED+83);
        // Smooth intersection joins the cloud's irregular lip to its rolling top and underside.
        double h=Math.max(.24-Math.abs(body-lip),0)/.24;
        return Math.min(body,lip)-h*h*.06;
    }
    private static int at(int x,int y,int z){return (y*NX+z)*NX+x;}
    private static float value(float[] values,int x,int y,int z,double ox,double oz){
        return x>=0&&x<NX&&y>=0&&y<NY&&z>=0&&z<NX?values[at(x,y,z)]
                :(float)density(ox+x*STEP,MIN_Y+y*STEP,oz+z*STEP);
    }
    public static Mesh mesh(int cellX,int cellZ){
        double ox=(double)cellX*CELL_SIZE,oz=(double)cellZ*CELL_SIZE;
        float[] values=new float[NX*NY*NX],normals=new float[values.length*3];
        for(int y=0;y<NY;y++)for(int z=0;z<NX;z++)for(int x=0;x<NX;x++)values[at(x,y,z)]=(float)density(ox+x*STEP,MIN_Y+y*STEP,oz+z*STEP);
        for(int y=0;y<NY;y++)for(int z=0;z<NX;z++)for(int x=0;x<NX;x++){
            int i=at(x,y,z)*3;
            normals[i]=value(values,x-1,y,z,ox,oz)-value(values,x+1,y,z,ox,oz);
            normals[i+1]=value(values,x,y-1,z,ox,oz)-value(values,x,y+1,z,ox,oz);
            normals[i+2]=value(values,x,y,z-1,ox,oz)-value(values,x,y,z+1,ox,oz);
        }
        Floats output=new Floats();
        int[] corners=new int[8],positive=new int[4],negative=new int[4];
        float[][] positions=new float[8][3];
        for(int y=0;y<NY-1;y++)for(int z=0;z<NX-1;z++)for(int x=0;x<NX-1;x++){
            boolean inside=false,outside=false;
            for(int c=0;c<8;c++){
                var d=CORNERS[c];corners[c]=at(x+d[0],y+d[1],z+d[2]);
                inside|=values[corners[c]]>0;outside|=values[corners[c]]<=0;
                positions[c][0]=(x+d[0])*STEP;positions[c][1]=MIN_Y+(y+d[1])*STEP;positions[c][2]=(z+d[2])*STEP;
            }
            if(!inside||!outside)continue;
            for(var t:TETRA){
                int count=0,other=0;for(int c:t){if(values[corners[c]]>0)positive[count++]=c;else negative[other++]=c;}
                if(count==0||count==4)continue;
                if(count==1||count==3){
                    int anchor=count==1?positive[0]:negative[0];int[] rest=count==1?negative:positive;
                    for(int j=0;j<3;j++)edge(output,anchor,rest[j],positions,corners,values,normals);
                }else{
                    edge(output,positive[0],negative[0],positions,corners,values,normals);
                    edge(output,positive[0],negative[1],positions,corners,values,normals);
                    edge(output,positive[1],negative[0],positions,corners,values,normals);
                    edge(output,positive[1],negative[0],positions,corners,values,normals);
                    edge(output,positive[0],negative[1],positions,corners,values,normals);
                    edge(output,positive[1],negative[1],positions,corners,values,normals);
                }
            }
        }
        return new Mesh(cellX,cellZ,output.array());
    }
    private static void edge(Floats output,int a,int b,float[][] positions,int[] corners,float[] values,float[] normals){
        int ia=corners[a],ib=corners[b];float t=values[ia]/(values[ia]-values[ib]);
        for(int k=0;k<3;k++)output.add(positions[a][k]+(positions[b][k]-positions[a][k])*t);
        float nx=normals[ia*3]+(normals[ib*3]-normals[ia*3])*t;
        float ny=normals[ia*3+1]+(normals[ib*3+1]-normals[ia*3+1])*t;
        float nz=normals[ia*3+2]+(normals[ib*3+2]-normals[ia*3+2])*t;
        float length=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);if(length<.00001f){ny=1;length=1;}
        output.add(nx/length);output.add(ny/length);output.add(nz/length);
    }
    private static final class Floats {
        private float[] values=new float[8192];private int length;
        void add(float value){if(length==values.length)values=Arrays.copyOf(values,length*2);values[length++]=value;}
        float[] array(){return Arrays.copyOf(values,length);}
    }
    private CloudseaCloudField(){}
}
