package dev.logos.dimension;

import dev.logos.dimension.world.NaturalTreeShape;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NaturalTreeShapeTest {
    @Test void everyBranchConnectsToTheTrunk(){
        for(var form:NaturalTreeShape.Form.values())for(long seed=0;seed<12;seed++){
            var tree=NaturalTreeShape.create(form,10,seed);var wood=new HashSet<String>();
            for(var v:tree.voxels())if(v.part()!=NaturalTreeShape.Part.LEAF)wood.add(v.x()+","+v.y()+","+v.z());
            var queue=new ArrayDeque<int[]>();queue.add(new int[]{0,0,0});var seen=new HashSet<String>();
            while(!queue.isEmpty()){
                var p=queue.remove();var key=p[0]+","+p[1]+","+p[2];if(!wood.contains(key)||!seen.add(key))continue;
                for(int axis=0;axis<3;axis++)for(int sign:new int[]{-1,1}){var n=p.clone();n[axis]+=sign;queue.add(n);}
            }
            assertEquals(wood,seen,form+" has floating wood");
        }
    }
    @Test void crownsHaveDenseVolumeRatherThanSeparatedLeafPlates(){
        for(var form:NaturalTreeShape.Form.values()){
            var t=NaturalTreeShape.create(form,10,73);var leaves=t.voxels().stream().filter(v->v.part()==NaturalTreeShape.Part.LEAF).toList();
            assertTrue(leaves.size()>85,form+" crown too sparse");
            assertTrue(leaves.stream().map(NaturalTreeShape.Voxel::y).distinct().count()>=4);
            assertTrue(leaves.stream().anyMatch(v->Math.abs(v.x())>=3));
        }
    }
    @Test void allTreesFitTheirJitteredChunkAnchorsAndHaveStableShapes(){
        for(var f:NaturalTreeShape.Form.values()){
            var t=NaturalTreeShape.create(f,14,921);assertEquals(t.voxels(),NaturalTreeShape.create(f,14,921).voxels());
            for(var v:t.voxels()){assertTrue(Math.abs(v.x())<=5&&Math.abs(v.z())<=5);assertTrue(v.y()>=0&&v.y()<=20);}
            assertEquals(NaturalTreeShape.Part.AIR,t.at(-6,9,0));
        }
    }
}
