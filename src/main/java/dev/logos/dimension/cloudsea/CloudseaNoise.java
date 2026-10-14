package dev.logos.dimension.cloudsea;

/** Bounded value noise shared by pure geometry tests and generation. No mutable RNG or external world reads. */
public final class CloudseaNoise {
    public static long hash(long x,long y,long z,long seed) { long h=seed+x*0x9E3779B97F4A7C15L+y*0xC2B2AE3D27D4EB4FL+z*0x165667B19E3779F9L; h=(h^(h>>>30))*0xBF58476D1CE4E5B9L; h=(h^(h>>>27))*0x94D049BB133111EBL; return h^(h>>>31); }
    public static double unit(long h) { return (h>>>11)*0x1.0p-53; }
    public static double smooth(double t) { return t*t*(3-2*t); }
    public static double lerp(double a,double b,double t) { return a+(b-a)*t; }
    public static double noise(double x,double y,double z,long seed) {
        long ix=(long)Math.floor(x),iy=(long)Math.floor(y),iz=(long)Math.floor(z);
        double tx=smooth(x-ix),ty=smooth(y-iy),tz=smooth(z-iz);
        double a=lerp(unit(hash(ix,iy,iz,seed)),unit(hash(ix+1,iy,iz,seed)),tx);
        double b=lerp(unit(hash(ix,iy,iz+1,seed)),unit(hash(ix+1,iy,iz+1,seed)),tx);
        double c=lerp(unit(hash(ix,iy+1,iz,seed)),unit(hash(ix+1,iy+1,iz,seed)),tx);
        double d=lerp(unit(hash(ix,iy+1,iz+1,seed)),unit(hash(ix+1,iy+1,iz+1,seed)),tx);
        return lerp(lerp(a,b,tz),lerp(c,d,tz),ty)*2-1;
    }
    private CloudseaNoise() {}
}
