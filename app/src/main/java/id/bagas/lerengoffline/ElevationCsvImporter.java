package id.bagas.lerengoffline;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Reads a user-owned CSV point set; no network or Android dependencies. */
public final class ElevationCsvImporter {
    public static final int MAX_SAMPLES = 250_000;

    public static final class Result {
        public final List<LocalElevationEngine.Sample> samples;
        public final int skippedRows;
        public final int dataRows;
        Result(List<LocalElevationEngine.Sample> samples, int skippedRows, int dataRows) {
            this.samples = samples;
            this.skippedRows = skippedRows;
            this.dataRows = dataRows;
        }
    }

    private ElevationCsvImporter() {}

    public static Result parse(Reader input) throws IOException {
        BufferedReader reader = input instanceof BufferedReader ? (BufferedReader)input : new BufferedReader(input);
        String headerLine;
        while ((headerLine = reader.readLine()) != null && headerLine.trim().isEmpty()) { }
        if (headerLine == null) throw new IllegalArgumentException("CSV kosong.");
        List<String> header = CsvCodec.parseLine(headerLine);
        int latitudeColumn = -1, longitudeColumn = -1, elevationColumn = -1;
        for (int i = 0; i < header.size(); i++) {
            String key = header.get(i).replace("\uFEFF", "").trim().toLowerCase(Locale.ROOT).replace(" ", "_");
            if (key.equals("lat") || key.equals("latitude")) latitudeColumn = i;
            else if (key.equals("lon") || key.equals("lng") || key.equals("longitude")) longitudeColumn = i;
            else if (key.equals("elevation_m") || key.equals("elev_m") || key.equals("elevation")
                    || key.equals("elev") || key.equals("altitude_m") || key.equals("altitude") || key.equals("alt") || key.equals("z")
                    || key.equals("height_m") || key.equals("ele")) elevationColumn = i;
        }
        if (latitudeColumn < 0 || longitudeColumn < 0 || elevationColumn < 0)
            throw new IllegalArgumentException("Header wajib memuat latitude, longitude, elevation_m (atau lat, lon, z). Lihat contoh format di aplikasi.");

        List<LocalElevationEngine.Sample> samples = new ArrayList<>();
        int skipped = 0, rows = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            rows++;
            try {
                List<String> cells = CsvCodec.parseLine(line);
                double lat = Double.parseDouble(cell(cells, latitudeColumn).trim());
                double lon = Double.parseDouble(cell(cells, longitudeColumn).trim());
                double elevation = Double.parseDouble(cell(cells, elevationColumn).trim());
                if (!Double.isFinite(lat) || lat < -90 || lat > 90 || !Double.isFinite(lon) || lon < -180 || lon > 180
                        || !Double.isFinite(elevation) || elevation < -12000 || elevation > 100000 || elevation == -9999) {
                    skipped++;
                    continue;
                }
                if (samples.size() >= MAX_SAMPLES)
                    throw new IllegalArgumentException("Dataset melebihi batas " + MAX_SAMPLES + " sampel. Pecah file menjadi beberapa dataset/area.");
                samples.add(new LocalElevationEngine.Sample(lat, lon, elevation));
            } catch (NumberFormatException | IndexOutOfBoundsException invalid) {
                skipped++;
            }
        }
        if (samples.size() < 4) throw new IllegalArgumentException("Hanya ditemukan " + samples.size() + " sampel valid; diperlukan minimal 4 titik.");
        return new Result(samples, skipped, rows);
    }

    private static String cell(List<String> cells, int index) {
        return index >= 0 && index < cells.size() ? cells.get(index) : "";
    }
}
