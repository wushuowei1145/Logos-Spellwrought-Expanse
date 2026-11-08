package dev.logos.dimension.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** 视线前方的菱形棱柱，末端在原版攻击距离处；用多面体与碰撞箱的真实交集检查命中和遮挡。 */
public final class DiamondHitbox {
    private static final double EPSILON = 1e-7;
    private static final int[][] EDGES = {{0,1},{0,2},{0,4},{1,3},{1,5},{2,3},{2,6},{3,7},{4,5},{4,6},{5,7},{6,7}};
    private final Vec3 center;
    private final Vec3[] planes;
    private final Vec3[] corners;
    private final AABB bounds;

    public DiamondHitbox(Vec3 origin, Vec3 look, Vec3 rightFallback, double reach, double halfWidth, double halfHeight) {
        if (!(reach>0 && halfWidth>0 && halfHeight>0)) throw new IllegalArgumentException("菱形判定尺寸必须为正");
        Vec3 forward=look.normalize(), right=forward.cross(new Vec3(0,1,0));
        right=right.lengthSqr()<EPSILON?rightFallback.normalize():right.normalize();
        Vec3 up=right.cross(forward).normalize();
        double half=reach/2;center=origin.add(forward.scale(half));
        Vec3 f=forward.scale(1/half),r=right.scale(1/halfWidth),u=up.scale(1/halfHeight);
        planes=new Vec3[]{f.add(r),f.subtract(r),f.scale(-1).add(r),f.scale(-1).subtract(r),u,u.scale(-1)};
        Vec3[] ring={forward.scale(-half),right.scale(halfWidth),forward.scale(half),right.scale(-halfWidth)};
        corners=new Vec3[8];
        double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=-minX,maxY=maxX,maxZ=maxX;
        for(int i=0;i<8;i++){
            corners[i]=center.add(ring[i%4]).add(up.scale(i<4?-halfHeight:halfHeight));
            minX=Math.min(minX,corners[i].x);minY=Math.min(minY,corners[i].y);minZ=Math.min(minZ,corners[i].z);
            maxX=Math.max(maxX,corners[i].x);maxY=Math.max(maxY,corners[i].y);maxZ=Math.max(maxZ,corners[i].z);
        }
        bounds=new AABB(minX,minY,minZ,maxX,maxY,maxZ).inflate(EPSILON);
    }
    public AABB bounds(){return bounds;}
    public boolean contains(Vec3 point){
        Vec3 p=point.subtract(center);
        for(var n:planes)if(n.dot(p)>1+EPSILON)return false;
        return true;
    }

    /** 线段裁剪到六个半空间，包含端点已在内部的情况。 */
    private Vec3[] clip(Vec3 a,Vec3 b){
        Vec3 delta=b.subtract(a),relative=a.subtract(center);double lo=0,hi=1;
        for(var n:planes){
            double available=1-n.dot(relative),speed=n.dot(delta);
            if(Math.abs(speed)<EPSILON){if(available< -EPSILON)return new Vec3[0];continue;}
            double t=available/speed;if(speed>0)hi=Math.min(hi,t);else lo=Math.max(lo,t);
            if(lo>hi+EPSILON)return new Vec3[0];
        }
        return new Vec3[]{a.add(delta.scale(Math.clamp(lo,0,1))),a.add(delta.scale(Math.clamp(hi,0,1)))};
    }
    private static void add(List<Vec3> points,Vec3 p){
        for(var old:points)if(old.distanceToSqr(p)<EPSILON)return;
        points.add(p);
    }

    /** 交集的顶点：双方内部顶点和双方棱线的裁剪交点，避免只按实体中心漏掉大型目标。 */
    public List<Vec3> contacts(AABB box){
        List<Vec3> points=new ArrayList<>();if(!box.intersects(bounds))return points;
        Vec3[] vertices=new Vec3[8];
        for(int i=0;i<8;i++){
            vertices[i]=new Vec3((i&1)==0?box.minX:box.maxX,(i&2)==0?box.minY:box.maxY,(i&4)==0?box.minZ:box.maxZ);
            if(contains(vertices[i]))add(points,vertices[i]);
            if(box.inflate(EPSILON).contains(corners[i]))add(points,corners[i]);
        }
        for(var edge:EDGES)for(var p:clip(vertices[edge[0]],vertices[edge[1]]))add(points,p);
        for(int i=0;i<4;i++){
            int next=(i+1)%4;
            for(var pair:new int[][]{{i,next},{i+4,next+4},{i,i+4}}){
                box.clip(corners[pair[0]],corners[pair[1]]).ifPresent(p->add(points,p));
                box.clip(corners[pair[1]],corners[pair[0]]).ifPresent(p->add(points,p));
            }
        }
        return points;
    }
    public boolean intersects(AABB box){return !contacts(box).isEmpty();}

    public static List<Entity> targets(ServerPlayer player){
        Vec3 from=player.getEyePosition(),look=player.getLookAngle();double reach=player.entityInteractionRange();
        double yaw=Math.toRadians(player.getYRot());
        var shape=new DiamondHitbox(from,look,new Vec3(Math.cos(yaw),0,Math.sin(yaw)),reach,reach*.45,1.5);
        var candidates=player.level().getEntities(player,shape.bounds(),e->e instanceof LivingEntity living&&living.isAlive()
                &&!e.isSpectator()&&e.isPickable()&&!e.isPassengerOfSameVehicle(player)
                &&!(e instanceof TamableAnimal pet&&pet.isOwnedBy(player)));
        candidates.sort(Comparator.comparingDouble(e->e.getBoundingBox().distanceToSqr(from)));
        List<Entity> targets=new ArrayList<>();
        for(var entity:candidates){
            if(entity.getBoundingBox().distanceToSqr(from)>reach*reach)continue;
            var points=shape.contacts(entity.getBoundingBox());points.sort(Comparator.comparingDouble(from::distanceToSqr));
            // 只检查真实交集里的点；最近的十二点足够覆盖小目标与大型目标的可见边缘，并限制密集生物群开销。
            boolean visible=points.stream().filter(p->p.distanceToSqr(from)<=reach*reach+EPSILON).limit(12).anyMatch(p->
                    player.level().clip(new ClipContext(from,p,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,player)).getType()==HitResult.Type.MISS);
            if(visible){targets.add(entity);if(targets.size()==8)break;}
        }
        return targets;
    }
}
