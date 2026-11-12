package id.bagas.lerengoffline;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public final class TerrainEngineTest {
    private List<TerrainEngine.Sample> regular(String datum) {
        List<TerrainEngine.Sample> s=new ArrayList<>();
        for(int y=0;y<4;y++) for(int x=0;x<4;x++) s.add(new TerrainEngine.Sample(10+y*.0005,20+x*.0005,100+y*10+x,datum));
        return s;
    }
    @Test public void regularSyntheticGridProducesCellsAndContours(){
        TerrainEngine.Result r=TerrainEngine.build(regular("WGS84 ellipsoid"),"WGS84 ellipsoid",5);
        assertFalse(r.cells.isEmpty()); assertFalse(r.contours.isEmpty()); assertTrue(r.coveragePercent>0);
        assertTrue("A sufficiently covered connected grid may render",r.shouldRenderSurface);
    }
    @Test public void nearlyCollinearTrackProducesNoArea(){
        List<TerrainEngine.Sample> s=new ArrayList<>(); for(int i=0;i<20;i++)s.add(new TerrainEngine.Sample(10+i*.0002,20,100+i,"WGS84 ellipsoid"));
        assertFalse(TerrainEngine.build(s,"WGS84 ellipsoid",5).hasSurface());
    }
    @Test public void gapBeyondRadiusRemainsMissing(){
        List<TerrainEngine.Sample> s=new ArrayList<>();
        for(int cluster=0;cluster<2;cluster++)for(int y=0;y<4;y++)for(int x=0;x<4;x++)
            s.add(new TerrainEngine.Sample(10+y*.0004,20+cluster*.006+x*.0004,100+y+x,"WGS84 ellipsoid"));
        TerrainEngine.Result r=TerrainEngine.build(s,"WGS84 ellipsoid",5);
        assertFalse("Supported local patches should render",r.cells.isEmpty());
        assertTrue("Large unsampled gaps must remain visibly missing",r.coveragePercent<100);
        assertFalse("Separated sparse patches should be shown as measured points, not terrain",r.shouldRenderSurface);
    }
    @Test public void repeatedFixesAtFewLocationsDoNotCountAsDistinctCoverage(){
        List<TerrainEngine.Sample> ten=new ArrayList<>();
        for(int y=0;y<2;y++)for(int x=0;x<5;x++)ten.add(new TerrainEngine.Sample(10+y*.0005,20+x*.0005,100+y*10+x,"WGS84 ellipsoid"));
        List<TerrainEngine.Sample> repeated=new ArrayList<>(); for(int i=0;i<20;i++)repeated.addAll(ten);
        TerrainEngine.Result r=TerrainEngine.build(repeated,"WGS84 ellipsoid",5);
        assertEquals(10,r.distinctSampleCount); assertFalse(r.hasSurface());
    }
    @Test public void contoursOnlyComeFromValidCells(){
        TerrainEngine.Result r=TerrainEngine.build(regular("WGS84 ellipsoid"),"WGS84 ellipsoid",5);
        for(TerrainEngine.Segment c:r.contours){boolean inside=false;for(TerrainEngine.Cell cell:r.cells){double minX=Math.min(Math.min(cell.nw.xMeters,cell.ne.xMeters),Math.min(cell.sw.xMeters,cell.se.xMeters));double maxX=Math.max(Math.max(cell.nw.xMeters,cell.ne.xMeters),Math.max(cell.sw.xMeters,cell.se.xMeters));double minY=Math.min(Math.min(cell.nw.yMeters,cell.ne.yMeters),Math.min(cell.sw.yMeters,cell.se.yMeters));double maxY=Math.max(Math.max(cell.nw.yMeters,cell.ne.yMeters),Math.max(cell.sw.yMeters,cell.se.yMeters));if(c.x1>=minX&&c.x1<=maxX&&c.y1>=minY&&c.y1<=maxY){inside=true;break;}}assertTrue(inside);}
    }
    @Test public void differentDatumsAreNotCombined(){
        List<TerrainEngine.Sample> s=regular("WGS84 ellipsoid"); s.addAll(regular("EGM2008 mean sea level"));
        TerrainEngine.Result r=TerrainEngine.build(s,"WGS84 ellipsoid",5); assertEquals(16,r.distinctSampleCount); assertEquals("WGS84_ELLIPSOID",r.datumGroup); assertTrue(TerrainEngine.compatibleDatum("WGS84","ellipsoid")); assertFalse(TerrainEngine.compatibleDatum("WGS84","EGM2008"));
        assertTrue(TerrainEngine.compatibleDatum("EGM2008", "iOS Core Location approximate orthometric mean sea level"));
        assertFalse(TerrainEngine.compatibleDatum("EGM96", "iOS Core Location approximate orthometric mean sea level"));
    }

    @Test public void levelsFollowRequestedInterval(){
        assertEquals(Arrays.asList(105.0,110.0,115.0,120.0),TerrainEngine.levels(5,101,123));
        assertEquals(Arrays.asList(120.0),TerrainEngine.levels(20,101,123));
    }

    @Test public void contoursSurviveLevelsThatMatchNodeElevations(){
        List<TerrainEngine.Sample> s=new ArrayList<>();
        for(int y=0;y<4;y++)for(int x=0;x<4;x++)
            s.add(new TerrainEngine.Sample(10+y*.0005,20+x*.0005,100+x*10,"WGS84 ellipsoid"));
        TerrainEngine.Result r=TerrainEngine.build(s,"WGS84 ellipsoid",10);
        assertFalse(r.cells.isEmpty());
        for(double level:new double[]{110,120,130}){
            boolean found=false;
            for(TerrainEngine.Segment c:r.contours) if(Math.abs(c.elevationMeters-level)<1e-6){found=true;break;}
            assertTrue("kontur level "+level+" hilang saat level sama dengan elevasi simpul",found);
        }
    }

    @Test public void requestedIntervalIsReportedBack(){
        TerrainEngine.Result r=TerrainEngine.build(regular("WGS84 ellipsoid"),"WGS84 ellipsoid",20);
        assertEquals(20.0,r.contourIntervalMeters,1e-9);
    }

    @Test public void requestedIntervalSurvivesEmptyResult(){
        List<TerrainEngine.Sample> s=new ArrayList<>();
        for(int i=0;i<3;i++)s.add(new TerrainEngine.Sample(10,20+i*.0002,100+i,"WGS84 ellipsoid"));
        TerrainEngine.Result r=TerrainEngine.build(s,"WGS84 ellipsoid",20);
        assertFalse(r.hasSurface());
        assertEquals(20.0,r.contourIntervalMeters,1e-9);
    }
}
