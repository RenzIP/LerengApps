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
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    value.append('"');
                    i++;
                } else {
                    quoted = !quoted;
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
