package id.bagas.lerengoffline;

import java.util.ArrayList;
import java.util.List;

/** Minimal CSV helpers shared by group export/import. */
public final class CsvCodec {
    private CsvCodec() {}

    public static void appendRow(StringBuilder output, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) output.append(',');
            output.append(escape(cells[i]));
        }
        output.append('\n');
    }

    public static String escape(String value) {
        if (value == null) value = "";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    public static List<String> parseLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted) {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        value.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else if (value.length() == 0) {
                    // Kutip pembuka hanya sah di awal sel. Tanpa aturan ini,
                    // tanda kutip di tengah label seperti Renz "The Hiker"
                    // membuat seluruh kolom sesudahnya bergeser.
                    quoted = true;
                } else {
                    value.append('"');
                }
            } else if (c == ',' && !quoted) {
                result.add(value.toString());
                value.setLength(0);
            } else {
                value.append(c);
            }
        }
        result.add(value.toString());
        return result;
    }
}
