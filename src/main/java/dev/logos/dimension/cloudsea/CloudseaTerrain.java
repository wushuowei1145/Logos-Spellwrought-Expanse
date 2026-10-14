package dev.logos.dimension.cloudsea;

/** The surface keeps a continuous explorable floor beneath its suspended geological sheets. */
public final class CloudseaTerrain {
    public static final int MIN_Y=-128, HEIGHT=640, WATER_LEVEL=141, REGION_SIZE=1536;
    public record Region(int gx,int gz,int x,int z,CloudseaBiome biome) {}
    public record Column(CloudseaBiome biome,int ground,int lowerBottom,int lowerTop,int upperBottom,int upperTop) {
        public boolean sheet(int y) { return y>=lowerBottom&&y<=lowerTop || y>=upperBottom&&y<=upperTop; }
        public int highest() { return Math.max(ground,Math.max(lowerTop,upperTop)); }
    }
    private final long seed;
    public CloudseaTerrain(long seed) { this.seed=seed; }
    public long seed() { return seed; }
    public Region region(int gx,int gz) {
        long salt=CloudseaNoise.hash(gx,0,gz,seed+19);
        int x=gx*REGION_SIZE+REGION_SIZE/2+(int)((CloudseaNoise.unit(salt)-.5)*400);
        int z=gz*REGION_SIZE+REGION_SIZE/2+(int)((CloudseaNoise.unit(salt^9271)-.5)*400);
        int kind=Math.floorMod(gx+5*gz+(int)(seed&15),CloudseaBiome.values().length);
        return new Region(gx,gz,x,z,CloudseaBiome.values()[kind]);
    }
    public Region nearest(int x,int z) {
        int gx=Math.floorDiv(x,REGION_SIZE),gz=Math.floorDiv(z,REGION_SIZE); Region nearest=null;double best=Double.MAX_VALUE;
        for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++) { var r=region(gx+dx,gz+dz);double d=(double)(r.x-x)*(r.x-x)+(double)(r.z-z)*(r.z-z);if(d<best){best=d;nearest=r;} }
        return nearest;
    }
    public Column column(int x,int z) {
        var r=nearest(x,z);var b=r.biome();double n=CloudseaNoise.noise(x/410.0,0,z/410.0,seed+31),fine=CloudseaNoise.noise(x/85.0,0,z/85.0,seed+43);
        double wave=Math.sin((x+z*.3)/95.0),petal=Math.sin(Math.atan2(z-r.z,x-r.x)*6+Math.hypot(x-r.x,z-r.z)/180.0);
        double level=b.base+n*b.relief+fine*5;
        switch(b.form) {
            case PETAL -> level+=petal*14;
            case BASIN -> level=b.base+n*19+fine*4;
            case STEPS -> level=Math.floor(level/9)*9;
            case DUNE -> level+=wave*12+Math.cos(z/133.0)*8;
            case SPIRE -> level+=Math.pow(Math.max(0,CloudseaNoise.noise(x/120.0,0,z/120.0,seed+67)),3)*105;
            default -> {}
        }
        // Ease adjacent height families across the broad biome edge rather than creating sheer palette seams.
        double boundary=Double.MAX_VALUE; Region second=null; double best=Math.hypot(x-r.x,z-r.z);
        int gx=Math.floorDiv(x,REGION_SIZE),gz=Math.floorDiv(z,REGION_SIZE);
        for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++){var q=region(gx+dx,gz+dz);if(q.gx==r.gx&&q.gz==r.gz)continue;double distance=Math.hypot(x-q.x,z-q.z)-best;if(distance<boundary){boundary=distance;second=q;}}
        if(second!=null&&boundary<96)level=CloudseaNoise.lerp((b.base+second.biome.base)*.5+n*25,level,CloudseaNoise.smooth(Math.max(0,boundary)/96));
        int ground=(int)Math.max(110,Math.min(285,level));int lb=1,lt=0,ub=1,ut=0;
        int dx=Math.floorMod(x,512)-256,dz=Math.floorMod(z,512)-256;
        int variant=b.ordinal()/8;
        int bend=dx-(int)(Math.sin(dz/79.0)*13),radius=94+variant*17+(int)(Math.sin(dz/110.0)*10);
        if(b.form==CloudseaBiome.Form.FOLD && bend>=0 && bend<=radius && Math.abs(dz)<180) {
            int cy=b.base+99-(int)(Math.pow(Math.abs(dz)/180.0,4)*30);double arc=Math.sqrt(Math.max(0,radius*radius-bend*bend));
            lt=(int)(cy-arc);lb=lt-9;ut=(int)(cy+arc);ub=ut-10;
        } else if(b.form==CloudseaBiome.Form.HANGING && dx*dx+dz*dz<105*105) {
            ut=b.base+91+(int)(Math.sin(dx/55.0)*10);ub=ut-5-(int)(Math.sqrt(Math.max(0,1-(dx*dx+dz*dz)/(105.0*105)))*18);
        } else if(b.form==CloudseaBiome.Form.RIBBON && Math.abs(dx-Math.sin(dz/100.0)*60)<44) {
            ut=b.base+74+(int)(Math.sin(dz/140.0)*22);ub=ut-7;
        }
        return new Column(b,ground,lb,lt,ub,ut);
    }
    public boolean cave(int x,int y,int z,Column c) {
        if(y<MIN_Y+5||y>c.ground-9)return false;
        if(y<-65) return y>-116 && Math.abs(CloudseaNoise.noise(x/180.0,y/34.0,z/180.0,seed+113))<.58;
        double a=CloudseaNoise.noise(x/57.0,y/28.0,z/57.0,seed+131);
        double b=CloudseaNoise.noise(x/43.0,y/38.0,z/43.0,seed+149);
        return a*a+b*b<.048 || CloudseaNoise.noise(x/100.0,y/40.0,z/100.0,seed+157)>.66;
    }
}
