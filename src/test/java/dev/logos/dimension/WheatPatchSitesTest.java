package dev.logos.dimension;

import dev.logos.dimension.wheat.WheatPatchSites;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 验证连片去重、窄连接、缺口、探索顺序和并发；不依赖已加载区块。 */
class WheatPatchSitesTest {
    private static WheatPatchSites.Candidates grid(){return (x,z)->Math.floorMod(x,48)==0&&Math.floorMod(z,48)==0;}
    private static WheatPatchSites.Cells square(int cx,int cz,int radius){return (x,z)->Math.abs(x-cx)<=radius&&Math.abs(z-cz)<=radius;}
    @Test void oneWinnerForEveryCandidateInTheSamePatch(){
        var map=new WheatPatchSites();var land=square(0,0,100);
        var patch=map.find(-96,96,land,grid());
        assertEquals(new WheatPatchSites.Site(0,0),patch.winner().orElseThrow());
        int allowed=0;
        for(int x=-96;x<=96;x+=48)for(int z=-96;z<=96;z+=48){
            var selected=map.find(x,z,land,grid());assertSame(patch,selected);
            if(selected.winner().orElseThrow().equals(new WheatPatchSites.Site(x,z)))allowed++;
        }
        assertEquals(1,allowed);
    }
    @Test void twoDisconnectedFieldsKeepTheirOwnCircle(){
        var map=new WheatPatchSites();var left=square(0,0,50);var right=square(192,0,50);
        WheatPatchSites.Cells land=(x,z)->left.contains(x,z)||right.contains(x,z);
        assertEquals(new WheatPatchSites.Site(0,0),map.find(0,0,land,grid()).winner().orElseThrow());
        assertEquals(new WheatPatchSites.Site(192,0),map.find(192,0,land,grid()).winner().orElseThrow());
    }
    @Test void aOneCellWideConnectionStillCountsAsOneField(){
        var left=square(0,0,64);var right=square(224,0,64);
        WheatPatchSites.Cells land=(x,z)->left.contains(x,z)||right.contains(x,z)||(x>=64&&x<=160&&z==0);
        var map=new WheatPatchSites();
        assertEquals(map.find(0,0,land,grid()).winner(),map.find(224,0,land,grid()).winner());
    }
    @Test void aHoleRejectsTheCenterAndTheWholeFootprintIsChecked(){
        WheatPatchSites.Cells land=(x,z)->Math.abs(x)<=80&&Math.abs(z)<=80&&!(Math.abs(x)<3&&Math.abs(z)<3);
        var winner=new WheatPatchSites().find(-48,0,land,grid()).winner().orElseThrow();
        assertNotEquals(new WheatPatchSites.Site(0,0),winner);
        for(int x=-20;x<=20;x++)for(int z=-20;z<=20;z++)assertTrue(land.contains(winner.quartX()+x,winner.quartZ()+z));
    }
    @Test void aNarrowFieldDoesNotReceiveATruncatedCircle(){
        WheatPatchSites.Cells land=(x,z)->Math.abs(x)<20&&Math.abs(z)<100;
        var patch=new WheatPatchSites().find(0,0,land,grid());
        assertTrue(patch.winner().isEmpty());assertFalse(patch.limited());
    }
    @Test void equalChoicesAreStableAcrossOrderAndCacheRecreation(){
        var land=square(0,0,64);WheatPatchSites.Candidates choices=(x,z)->z==0&&(x==-24||x==24);
        for(int[] start:new int[][]{{-60,60},{60,-60},{0,0}}){
            var winner=new WheatPatchSites().find(start[0],start[1],land,choices).winner().orElseThrow();
            assertEquals(new WheatPatchSites.Site(-24,0),winner);
        }
        assertEquals(new WheatPatchSites.Site(-96,-144),new WheatPatchSites().find(-80,-130,square(-96,-144,50),grid()).winner().orElseThrow());
    }
    @Test void anOverflowNeverTurnsUnsearchedPiecesIntoExtraFields(){
        var map=new WheatPatchSites(128);var land=square(0,0,100);
        for(int[] start:new int[][]{{-96,96},{96,-96},{0,0},{48,48},{-48,-48}}){
            var patch=map.find(start[0],start[1],land,grid());assertTrue(patch.limited());assertTrue(patch.winner().isEmpty());
        }
    }
    @Test void concurrentRequestsElectTheSameWinner()throws Exception{
        var map=new WheatPatchSites();var land=square(0,0,64);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var jobs=new ArrayList<Future<WheatPatchSites.Patch>>();
            for(int i=0;i<12;i++){int x=i-6;jobs.add(executor.submit(()->map.find(x,-x,land,grid())));}
            for(var job:jobs)assertEquals(new WheatPatchSites.Site(0,0),job.get().winner().orElseThrow());
        }
    }
}
