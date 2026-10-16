package dev.logos.dimension.cloudsea;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.SimplexNoise;

/** Broad temperate continents with continuous climate, watersheds and landforms. */
public final class CloudseaTerrain {
    public static final int MIN_Y=-128, HEIGHT=640, WATER_LEVEL=80, REGION_SIZE=6144;
    public record Region(int gx,int gz,int x,int z,CloudseaBiome biome) {}
    public record Column(CloudseaBiome biome,int ground,int lowerBottom,int lowerTop,int upperBottom,int upperTop,
                         int water,double moisture,double mountain,boolean river,boolean ocean,boolean glacier) {
        public boolean sheet(int y){return false;}
        public int highest(){return Math.max(ground,water);}
    }
    private final long seed;
    private final SimplexNoise[] fields=new SimplexNoise[160];
    public CloudseaTerrain(long seed){this.seed=seed;for(int i=0;i<fields.length;i++)fields[i]=new SimplexNoise(RandomSource.create(seed+i*7919L));}
    public long seed(){return seed;}
    private double noise(double x,double z,double size,long salt){return fields[(int)salt].getValue(x/size,z/size);}
    public double latitude(int z){return Math.floorMod(z+24_000,48_000)-24_000;}
    public double coast(int x){return 6800+noise(x,0,5100,31)*1900+noise(x,0,1100,32)*270;}
    public double riverX(int basin,int z){return basin*3100.0+Math.sin(z/870.0+basin*.71)*260+noise(basin*3100.0,z,1800,39)*180;}
    public double riverDistance(int x,int z){int basin=(int)Math.round(x/3100.0);double d=Double.MAX_VALUE;for(int i=basin-1;i<=basin+1;i++)d=Math.min(d,Math.abs(x-riverX(i,z)));return d;}
    public double moisture(int x,int z){return noise(x,z,1400,97)*.5+.5;}
    public Region region(int gx,int gz){
        long h=CloudseaNoise.hash(gx,0,gz,seed+19);
        int x=gx*REGION_SIZE+REGION_SIZE/2+(int)((CloudseaNoise.unit(h)-.5)*1200);
        int z=gz*REGION_SIZE+REGION_SIZE/2+(int)((CloudseaNoise.unit(h^9271)-.5)*1200);
        return new Region(gx,gz,x,z,column(x,z).biome());
    }
    public Region nearest(int x,int z){return region(Math.floorDiv(x,REGION_SIZE),Math.floorDiv(z,REGION_SIZE));}
    public Column column(int x,int z){
        double lat=latitude(z),shore=Math.min(coast(x)-lat,lat+18_500+noise(x,z,4000,34)*1000);
        double moisture=moisture(x,z),broad=noise(x,z,2200,41),rolling=noise(x,z,480,43),fine=noise(x,z,92,45);
        double range=Math.exp(-Math.pow((lat+6600+noise(x,z,2600,51)*800)/2100,2));
        double mountain=range*Math.pow(1-Math.abs(noise(x,z,730,53)),1.8);
        double terrain=WATER_LEVEL+20+broad*17+rolling*10+fine*1.8+mountain*190
                +range*(noise(x,z,185,54)*24+noise(x,z,42,55)*5);
        double coastBlend=CloudseaNoise.smooth(Math.clamp((shore+200)/600,0,1));
        double shelf=WATER_LEVEL-28+noise(x,z,1300,61)*10+Math.clamp(shore/12,-28,15);
        terrain=CloudseaNoise.lerp(shelf,terrain,coastBlend);
        if(shore>0&&shore<180&&noise(x,z,1100,63)>.05)terrain+=Math.sin(Math.PI*shore/180)*14;
        boolean ocean=shore<60;
        double distance=riverDistance(x,z),valley=1-CloudseaNoise.smooth(Math.clamp((distance-17)/180,0,1));
        int riverLevel=WATER_LEVEL+(int)(Math.max(0,coast(x)-lat-250)/1800);
        if(!ocean)terrain=CloudseaNoise.lerp(terrain,riverLevel-5+fine*.6,valley);
        double lake=Math.hypot(noise(x,z,930,71),noise(x,z,1100,73));
        boolean lakeBasin=!ocean&&range<.35&&lake<.32;
        if(lakeBasin)terrain=CloudseaNoise.lerp(terrain,Math.min(terrain,WATER_LEVEL-4),CloudseaNoise.smooth(Math.clamp((.32-lake)/.22,0,1)));
        boolean glacial=range>.65&&lake<.3&&distance>220;
        if(glacial)terrain=CloudseaNoise.lerp(terrain,242,CloudseaNoise.smooth(Math.clamp((.3-lake)/.16,0,1)));
        boolean river=!ocean&&distance<19;
        double fault=Math.abs(noise(x,z,610,83));
        boolean gorge=!ocean&&range>.15&&range<.65&&fault<.032&&distance>200;
        if(gorge)terrain-=24*(1-fault/.032);
        int ground=(int)Math.clamp(terrain,12,358),water=glacial&&terrain<250?250:ocean||lakeBasin?WATER_LEVEL:terrain<riverLevel&&distance<200?riverLevel:-1000;
        CloudseaBiome biome;
        if(ocean)biome=shore<-150?CloudseaBiome.TIDE_RIBBONS:CloudseaBiome.PEARL_BASINS;
        else if(shore<220)biome=CloudseaBiome.OPAL_DUNES;
        else if(river)biome=range>.4?CloudseaBiome.ECHO_FOLDS:CloudseaBiome.STAR_RIBBONS;
        else if(lakeBasin)biome=CloudseaBiome.GLASS_LAGOONS;
        else if(glacial)biome=CloudseaBiome.FROST_SPIRES;
        else if(ground>282)biome=moisture>.5?CloudseaBiome.FROST_SPIRES:CloudseaBiome.PALE_SPIRES;
        else if(ground>220)biome=CloudseaBiome.HALO_SPIRES;
        else if(gorge)biome=CloudseaBiome.MOON_FOLDS;
        else if(range>.35)biome=moisture>.52?CloudseaBiome.CLOUDROOT_SHELVES:CloudseaBiome.BLUE_BELL_STEPS;
        else if(lat<-9500)biome=moisture>.55?CloudseaBiome.AURORA_DUNES:CloudseaBiome.LILAC_STEPS;
        else if(lat<-3500&&moisture>.35)biome=moisture>.7?CloudseaBiome.REVERSE_GARDENS:CloudseaBiome.IVORY_STEPS;
        else if(terrain<90&&moisture>.7)biome=CloudseaBiome.DREAM_BASINS;
        else if(moisture>.84)biome=CloudseaBiome.LANTERN_SHELVES;
        else if(moisture>.72)biome=CloudseaBiome.JADE_WHIRLS;
        else if(moisture>.64)biome=CloudseaBiome.VERDANT_WHIRLS;
        else if(rolling>.27)biome=CloudseaBiome.AMBER_WHIRLS;
        else if(rolling<-.3)biome=CloudseaBiome.ROSE_DUNES;
        else if(broad>.3)biome=CloudseaBiome.DAWN_RIBBONS;
        else biome=CloudseaBiome.SILK_FOLDS;
        return new Column(biome,ground,1,0,1,0,water,moisture,mountain,river,ocean,glacial);
    }
    public boolean cave(int x,int y,int z,Column c){
        if(y<MIN_Y+5||y>c.ground()-9||y>=c.water()-5&&c.water()>0)return false;
        double a=CloudseaNoise.noise(x/67.0,y/32.0,z/67.0,seed+131),b=CloudseaNoise.noise(x/49.0,y/40.0,z/49.0,seed+149);
        return a*a+b*b<.032 || y<38&&CloudseaNoise.noise(x/125.0,y/42.0,z/125.0,seed+157)>.61;
    }
}
