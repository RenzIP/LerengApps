package id.bagas.lerengoffline;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Minimal GPX 1.1 reader/writer for one route.
 *
 * <p>GPX has a single {@code <ele>} field with no datum attribute. This codec therefore never
 * converts elevations: the caller picks the one reference the file is exported with, and only
 * points carrying that reference keep their {@code <ele>}. The chosen label is written to
 * {@code metadata/desc} and a private extension so a later import can stay honest.</p>
 *
 * <p>Pure Java on purpose: it runs on Android and is covered by JVM unit tests.</p>
 */
public final class GpxCodec {
    /** Elevation references understood by {@link TerrainEngine#datumGroup(String)}. */
    public static final String DATUM_ELLIPSOID = "WGS84 ellipsoid";
    public static final String DATUM_EGM96 = "EGM96";
    public static final String DATUM_MSL = "Mean sea level (orthometric)";
    /** Empty means “no elevation in this export”. */
    public static final String DATUM_NONE = "";

    public static final String DATUM_UNRECORDED = "not recorded";

    private static final long MAX_BYTES = 16L * 1024L * 1024L;
    private static final String EXT_NAMESPACE = "urn:lereng-offline:gpx:1";

    public static final class ParsedPoint {
        public final double latitude;
        public final double longitude;
        public final Double elevation;
        public final Long time;
        public final boolean waypoint;
        public final String name;

        ParsedPoint(double latitude, double longitude, Double elevation, Long time,
                    boolean waypoint, String name) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.elevation = elevation;
            this.time = time;
            this.waypoint = waypoint;
            this.name = name == null ? "" : name;
        }
    }

    public static final class Result {
        public final List<ParsedPoint> points;
        public final int skipped;
        public final String creator;
        /** Value of {@code lereng:elevationReference}, when the file came from this app. */
        public final String declaredReference;

        Result(List<ParsedPoint> points, int skipped, String creator, String declaredReference) {
            this.points = points;
            this.skipped = skipped;
            this.creator = creator == null ? "" : creator;
            this.declaredReference = declaredReference == null ? "" : declaredReference;
        }

        public boolean hasElevation() {
            for (ParsedPoint p : points) if (p.elevation != null) return true;
            return false;
        }

        public boolean hasTime() {
            for (ParsedPoint p : points) if (p.time != null) return true;
            return false;
        }

        public int waypointCount() {
            int n = 0;
            for (ParsedPoint p : points) if (p.waypoint) n++;
            return n;
        }
    }

    private GpxCodec() {}

    // ------------------------------------------------------------------ writing

    /**
     * Builds a GPX document for one project. Points are ordered by time. Elevation is written only
     * when {@code datumLabel} is non-empty; callers must strip {@code gpsAltitude} from points that
     * belong to a different reference instead of relabelling them.
     */
    public static String build(String name, String description, String datumLabel,
                               List<SurveyDatabase.Point> input, long generatedAt) {
        List<SurveyDatabase.Point> points = new ArrayList<>();
        if (input != null) for (SurveyDatabase.Point p : input) if (valid(p)) points.add(p);
        points.sort(Comparator.comparingLong((SurveyDatabase.Point p) -> p.time));

        boolean withElevation = datumLabel != null && !datumLabel.trim().isEmpty();
        String cleanName = name == null || name.trim().isEmpty() ? "Jalur Lereng Offline" : name.trim();
        String cleanDescription = description == null ? "" : description.trim();

        StringBuilder b = new StringBuilder(512 + points.size() * 160);
        b.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        b.append("<gpx version=\"1.1\" creator=\"Lereng Offline\"");
        b.append(" xmlns=\"http://www.topografix.com/GPX/1/1\"");
        b.append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"");
        b.append(" xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\"");
        b.append(" xmlns:lereng=\"").append(EXT_NAMESPACE).append("\">\n");

        b.append("  <metadata>\n");
        b.append("    <name>").append(escape(cleanName)).append("</name>\n");
        if (!cleanDescription.isEmpty())
            b.append("    <desc>").append(escape(cleanDescription)).append("</desc>\n");
        b.append("    <time>").append(Instant.ofEpochMilli(generatedAt)).append("</time>\n");
        b.append("    <extensions>\n");
        b.append("      <lereng:elevationReference>")
                .append(escape(withElevation ? datumLabel : DATUM_UNRECORDED))
                .append("</lereng:elevationReference>\n");
        b.append("    </extensions>\n");
        b.append("  </metadata>\n");

        for (SurveyDatabase.Point p : points) {
            if (!p.waypoint) continue;
            b.append("  <wpt lat=\"").append(coordinate(p.lat)).append("\" lon=\"")
                    .append(coordinate(p.lon)).append("\">\n");
            appendMeasurements(b, p, withElevation, "    ");
            String label = p.label == null ? "" : p.label.trim();
            if (!label.isEmpty()) b.append("    <name>").append(escape(label)).append("</name>\n");
            b.append("    <sym>Flag, Blue</sym>\n");
            b.append("  </wpt>\n");
        }

        boolean hasTrack = false;
        for (SurveyDatabase.Point p : points) if (!p.waypoint) { hasTrack = true; break; }
        if (hasTrack) {
            b.append("  <trk>\n");
            b.append("    <name>").append(escape(cleanName)).append("</name>\n");
            b.append("    <desc>").append(escape("Jalur proyek; bukan kontur area.")).append("</desc>\n");
            b.append("    <trkseg>\n");
            for (SurveyDatabase.Point p : points) {
                if (p.waypoint) continue;
                b.append("      <trkpt lat=\"").append(coordinate(p.lat)).append("\" lon=\"")
                        .append(coordinate(p.lon)).append("\">\n");
                appendMeasurements(b, p, withElevation, "        ");
                b.append("      </trkpt>\n");
            }
            b.append("    </trkseg>\n");
            b.append("  </trk>\n");
        }
        b.append("</gpx>\n");
        return b.toString();
    }

    private static void appendMeasurements(StringBuilder b, SurveyDatabase.Point p,
                                           boolean withElevation, String indent) {
        if (withElevation && p.gpsAltitude != null && Double.isFinite(p.gpsAltitude)) {
            b.append(indent).append("<ele>")
                    .append(String.format(Locale.US, "%.2f", p.gpsAltitude))
                    .append("</ele>\n");
        }
        if (p.time > 0) b.append(indent).append("<time>")
                .append(Instant.ofEpochMilli(p.time)).append("</time>\n");
    }

    /** Returns a copy of {@code p} without elevation, so it can be exported under another datum. */
    public static SurveyDatabase.Point withoutElevation(SurveyDatabase.Point p) {
        SurveyDatabase.Point copy = new SurveyDatabase.Point(p.surveyId, p.time, p.lat, p.lon, null,
                p.horizontalAccuracy, p.verticalAccuracy, p.pressureHpa, p.barometerRelativeAltitude,
                p.source, p.label, p.participantId, p.participantAlias, p.groupCode, p.waypoint);
        copy.id = p.id;
        return copy;
    }

    private static String coordinate(double value) {
        return String.format(Locale.US, "%.7f", value);
    }

    private static boolean valid(SurveyDatabase.Point p) {
        return p != null && Double.isFinite(p.lat) && Double.isFinite(p.lon)
                && p.lat >= -90 && p.lat <= 90 && p.lon >= -180 && p.lon <= 180;
    }

    // ------------------------------------------------------------------ reading

    /** Parses a GPX document with limited size and external-entity protection. */
    public static Result parse(InputStream input) throws Exception {
        if (input == null) throw new IllegalArgumentException("File GPX kosong atau tidak dapat dibuka.");
        byte[] bytes = readCapped(input, MAX_BYTES);
        String raw = new String(bytes, StandardCharsets.UTF_8);
        if (raw.regionMatches(true, 0, "\uFEFF", 0, 1)) raw = raw.substring(1);
        if (raw.toLowerCase(Locale.ROOT).contains("<!doctype"))
            throw new IllegalArgumentException("GPX dengan DOCTYPE tidak didukung.");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        try { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true); } catch (Exception ignored) {}
        try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) {}
        try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignored) {}
        try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignored) {}
        try { factory.setXIncludeAware(false); } catch (Exception ignored) {}

        Document document;
        try {
            document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new IllegalArgumentException("XML tidak valid: " + safeMessage(e));
        }
        Element root = document.getDocumentElement();
        if (root == null || !"gpx".equalsIgnoreCase(localName(root)))
            throw new IllegalArgumentException("File bukan GPX (akar <gpx> tidak ditemukan).");
        String creator = root.getAttribute("creator");
        String declaredReference = "";
        Element metadata = childElement(root, "metadata");
        if (metadata != null) {
            Element extensions = childElement(metadata, "extensions");
            if (extensions != null) declaredReference = childText(extensions, "elevationReference");
        }

        List<Element> elements = new ArrayList<>();
        collect(root, "trkpt", elements);
        // Sebagian aplikasi mengekspor jalur sebagai rute (<rtept>), bukan track.
        // Tanpa ini file yang sah ditolak sebagai "tidak ada titik".
        collect(root, "rtept", elements);
        collect(root, "wpt", elements);

        List<ParsedPoint> points = new ArrayList<>(elements.size());
        int skipped = 0;
        for (Element element : elements) {
            double lat, lon;
            try {
                lat = Double.parseDouble(element.getAttribute("lat").trim());
                lon = Double.parseDouble(element.getAttribute("lon").trim());
            } catch (Exception e) { skipped++; continue; }
            if (!Double.isFinite(lat) || !Double.isFinite(lon)
                    || lat < -90 || lat > 90 || lon < -180 || lon > 180) { skipped++; continue; }

            Double elevation = parseElevation(childText(element, "ele"));
            Long time = parseTime(childText(element, "time"));
            boolean waypoint = "wpt".equalsIgnoreCase(localName(element));
            String name = firstNonEmpty(childText(element, "name"), childText(element, "desc"),
                    childText(element, "cmt"));
            points.add(new ParsedPoint(lat, lon, elevation, time, waypoint, name));
        }
        return new Result(points, skipped, creator, declaredReference);
    }

    private static byte[] readCapped(InputStream input, long cap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > cap) throw new IOException("File GPX lebih dari 16 MB.");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void collect(Node node, String wanted, List<Element> out) {
        if (node instanceof Element && wanted.equalsIgnoreCase(localName(node))) out.add((Element) node);
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE || child.hasChildNodes()) collect(child, wanted, out);
        }
    }

    private static String childText(Element parent, String name) {
        Element child = childElement(parent, name);
        return child == null || child.getTextContent() == null ? "" : child.getTextContent().trim();
    }

    private static Element childElement(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && name.equalsIgnoreCase(localName(child)))
                return (Element) child;
        }
        return null;
    }

    private static String localName(Node node) {
        String local = node.getLocalName();
        if (local != null) return local;
        String name = node.getNodeName();
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private static Double parseElevation(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            double parsed = Double.parseDouble(value.trim());
            if (!Double.isFinite(parsed) || parsed < -12000 || parsed > 100000) return null;
            return parsed;
        } catch (NumberFormatException e) { return null; }
    }

    /** GPX times are UTC ISO-8601; offset and local fallbacks are accepted, epoch millis too. */
    static Long parseTime(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String clean = value.trim();
        try { return Instant.parse(clean).toEpochMilli(); } catch (DateTimeParseException ignored) {}
        try { return OffsetDateTime.parse(clean).toInstant().toEpochMilli(); } catch (DateTimeParseException ignored) {}
        // GPX 1.1 mewajibkan waktu UTC. Timestamp tanpa offset karena itu dibaca
        // sebagai UTC, bukan zona perangkat: memakai zona perangkat menggeser
        // seluruh jalur 7 jam di Indonesia dan membuat impor ulang terlihat baru.
        try { return LocalDateTime.parse(clean).toInstant(ZoneOffset.UTC).toEpochMilli(); }
        catch (DateTimeParseException ignored) {}
        try { return Long.parseLong(clean); } catch (NumberFormatException ignored) {}
        return null;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }

    private static String escape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&apos;"); break;
                default:
                    if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') out.append(' ');
                    else out.append(c);
            }
        }
        return out.toString();
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
