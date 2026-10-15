package dev.logos.dimension;

import dev.logos.dimension.cloudsea.CloudseaCloudField;
import org.junit.jupiter.api.Test;
import java.util.TreeSet;
import static org.junit.jupiter.api.Assertions.*;

class CloudseaCloudFieldTest {
    @Test void skyCleftIsOpenAndCloudsHaveRealVerticalThickness(){
        for(int z=-900;z<=900;z+=100){
            double x=-z*.5735764364/.8191520443;
            assertTrue(CloudseaCloudField.density(x,408,z)<0);
        }
        assertTrue(CloudseaCloudField.density(320,408,80)>0);
        assertTrue(CloudseaCloudField.density(320,300,80)<0);
        assertTrue(CloudseaCloudField.density(320,510,80)<0);
    }
    @Test void adjacentCloudMeshesShareTheirBoundary(){
        var a=CloudseaCloudField.mesh(0,0);var b=CloudseaCloudField.mesh(1,0);
        assertEquals(edge(a,256),edge(b,0));
        assertFalse(edge(a,256).isEmpty());
    }
    private TreeSet<String> edge(CloudseaCloudField.Mesh mesh,int x){
        var set=new TreeSet<String>();var v=mesh.vertices();
        for(int i=0;i<v.length;i+=6)if(Math.abs(v[i]-x)<.001)set.add(Math.round(v[i+1]*1000)+":"+Math.round(v[i+2]*1000));
        return set;
    }
    @Test void meshIsBoundedFiniteAndNotAPlane(){
        var mesh=CloudseaCloudField.mesh(-1,1);var v=mesh.vertices();
        assertTrue(mesh.triangles()>0&&mesh.triangles()<20_000);
        float min=Float.MAX_VALUE,max=-Float.MAX_VALUE;
        for(int i=0;i<v.length;i+=6){
            for(int k=0;k<6;k++)assertTrue(Float.isFinite(v[i+k]));
            assertTrue(v[i]>=0&&v[i]<=256&&v[i+2]>=0&&v[i+2]<=256);
            min=Math.min(min,v[i+1]);max=Math.max(max,v[i+1]);
            assertEquals(1,Math.sqrt(v[i+3]*v[i+3]+v[i+4]*v[i+4]+v[i+5]*v[i+5]),.002);
        }
        assertTrue(max-min>45,"Clouds must have substantial depth");
    }
}
