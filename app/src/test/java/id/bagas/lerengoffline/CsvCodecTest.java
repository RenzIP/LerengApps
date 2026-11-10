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
}
