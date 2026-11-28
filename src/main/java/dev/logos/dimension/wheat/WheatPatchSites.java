package dev.logos.dimension.wheat;

import java.util.*;

/** 只分析群系连通片和候选点；不读写区块，也不放置任何方块。 */
public final class WheatPatchSites {
    /** 以原版四格群系采样为单位，给 128 格模板预留至少 16 格的边界余量。 */
    public static final int INSET_CELLS=20;
    private static final int MAX_CACHED_CELLS=262144;
    private static final Patch BLOCKED=new Patch(Optional.empty(),0,0,0,0,0,true);
    private static final Patch OUTSIDE=new Patch(Optional.empty(),0,0,0,0,0,false);
    private final int maxSearchCells;
    private final Map<Long,Patch> memo=new HashMap<>();

    @FunctionalInterface public interface Cells { boolean contains(int x,int z); }
    @FunctionalInterface public interface Candidates { boolean contains(int x,int z); }
    public record Site(int quartX,int quartZ) {
        public int blockX(){return quartX*4;}
        public int blockZ(){return quartZ*4;}
    }
    public record Patch(Optional<Site> winner,int cells,int minX,int minZ,int maxX,int maxZ,boolean limited) {}

    public WheatPatchSites(){this(65536);}
    public WheatPatchSites(int maxSearchCells){
        if(maxSearchCells<1)throw new IllegalArgumentException("搜索上限必须为正数");
        this.maxSearchCells=maxSearchCells;
    }

    /** 同一采样器与候选规则在缓存存续期间必须保持不变。 */
    public synchronized Patch find(int x,int z,Cells cells,Candidates candidates){
        if(memo.size()>MAX_CACHED_CELLS)memo.clear();
        long origin=key(x,z);var saved=memo.get(origin);
        if(saved!=null)return saved;
        if(!cells.contains(x,z))return OUTSIDE;
        var region=new HashSet<Long>();var checked=new HashSet<Long>();var pending=new ArrayDeque<Long>();
        region.add(origin);checked.add(origin);pending.add(origin);
        while(!pending.isEmpty()){
            long at=pending.removeFirst();int px=x(at),pz=z(at);
            for(int direction=0;direction<4;direction++){
                int nx=px+(direction==0?1:direction==1?-1:0),nz=pz+(direction==2?1:direction==3?-1:0);
                long next=key(nx,nz);
                if(!checked.add(next))continue;
                var known=memo.get(next);
                if(known!=null)return remember(region,known);
                if(!cells.contains(nx,nz))continue;
                region.add(next);
                // 超限时整片保守不生成，不能将截断的搜索范围误当成另一片麦田。
                if(region.size()>maxSearchCells)return remember(region,BLOCKED);
                pending.addLast(next);
            }
        }
        long sumX=0,sumZ=0;int minX=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        for(long cell:region){
            int cx=x(cell),cz=z(cell);sumX+=cx;sumZ+=cz;
            minX=Math.min(minX,cx);minZ=Math.min(minZ,cz);maxX=Math.max(maxX,cx);maxZ=Math.max(maxZ,cz);
        }
        double centerX=sumX/(double)region.size(),centerZ=sumZ/(double)region.size(),best=Double.POSITIVE_INFINITY;
        Site winner=null;
        for(long cell:region){
            int cx=x(cell),cz=z(cell);
            // 原版起点在区块西北角，先过滤掉其余四格采样点，再检查完整模板和边界余量。
            if(Math.floorMod(cx,4)!=0||Math.floorMod(cz,4)!=0||!candidates.contains(cx,cz)||!fits(region,cx,cz))continue;
            double distance=(cx-centerX)*(cx-centerX)+(cz-centerZ)*(cz-centerZ);
            if(distance<best||(Double.compare(distance,best)==0&&(winner==null||cx<winner.quartX()||cx==winner.quartX()&&cz<winner.quartZ()))){
                best=distance;winner=new Site(cx,cz);
            }
        }
        return remember(region,new Patch(Optional.ofNullable(winner),region.size(),minX,minZ,maxX,maxZ,false));
    }
    private static boolean fits(Set<Long> region,int cx,int cz){
        for(int dx=-INSET_CELLS;dx<=INSET_CELLS;dx++)for(int dz=-INSET_CELLS;dz<=INSET_CELLS;dz++)
            if(!region.contains(key(cx+dx,cz+dz)))return false;
        return true;
    }
    private Patch remember(Set<Long> region,Patch patch){for(long cell:region)memo.put(cell,patch);return patch;}
    private static long key(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
    private static int x(long key){return (int)(key>>32);}
    private static int z(long key){return (int)key;}
}
