package id.bagas.lerengoffline;

import org.junit.Test;

import java.io.StringReader;

import static org.junit.Assert.*;

public final class ElevationCsvImporterTest {
    @Test public void parsesCommonHeadersAndCountsInvalidRows() throws Exception {
        String csv = "\uFEFFlatitude,longitude,elevation_m,label\n"
                + "10.75,20.64,1000,A\n"
                + "10.76,20.65,1100,B\n"
                + "bad,20.66,1200,C\n"
                + "10.77,20.67,1300,D\n"
                + "10.78,20.68,1400,E\n";

        ElevationCsvImporter.Result result = ElevationCsvImporter.parse(new StringReader(csv));

        assertEquals(4, result.samples.size());
        assertEquals(1, result.skippedRows);
        assertEquals(10.75, result.samples.get(0).latitude, 1e-8);
        assertEquals(20.64, result.samples.get(0).longitude, 1e-8);
        assertEquals(1000, result.samples.get(0).elevationMeters, 1e-8);
    }

    @Test public void refusesFilesWithoutRequiredColumns() {
        try {
            ElevationCsvImporter.parse(new StringReader("x,y,z\n1,2,3\n"));
            fail("Expected a helpful header validation error");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("latitude, longitude, elevation_m"));
        }
    }

    @Test public void requiresAtLeastFourValidElevationSamples() {
        try {
            ElevationCsvImporter.parse(new StringReader("lat,lon,z\n10.75,20.64,100\n10.76,20.65,101\n10.77,20.66,102\n"));
            fail("Expected a minimum-sample validation error");
        } catch (Exception expected) {
            assertTrue(expected.getMessage().contains("minimal 4"));
        }
    }
}
