package id.bagas.lerengoffline;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public final class CsvCodecTest {
    @Test public void exportRowsHaveRealLineBreaksAndRoundTripQuotedCells() {
        StringBuilder csv = new StringBuilder();
        CsvCodec.appendRow(csv, "record_type", "group_code", "participant_id", "label");
        CsvCodec.appendRow(csv, "track", "HIKE-7", "p-01", "Pos, \"Batu\"");

        String[] rows = csv.toString().split("\\n", -1);
        assertEquals("header plus one data row and final line break", 3, rows.length);
        assertEquals(Arrays.asList("record_type", "group_code", "participant_id", "label"), CsvCodec.parseLine(rows[0]));
        List<String> parsed = CsvCodec.parseLine(rows[1]);
        assertEquals(Arrays.asList("track", "HIKE-7", "p-01", "Pos, \"Batu\""), parsed);
    }

    @Test public void quoteInsideUnquotedCellDoesNotShiftColumns() {
        // Excel/hand-written CSV sering menulis label seperti Renz "The Hiker"
        // tanpa membungkus sel. Dulu satu tanda kutip membalik mode kutip dan
        // menelan seluruh kolom sesudahnya, sehingga koordinat terbaca salah.
        List<String> parsed = CsvCodec.parseLine("Renz \"The Hiker\",-6.9,107.6,1200");
        assertEquals(Arrays.asList("Renz \"The Hiker\"", "-6.9", "107.6", "1200"), parsed);
    }

    @Test public void quotedCellStillRoundTripsCommasAndQuotes() {
        assertEquals(Arrays.asList("Pos, \"Batu\"", "2"),
                CsvCodec.parseLine("\"Pos, \"\"Batu\"\"\",2"));
    }
}
