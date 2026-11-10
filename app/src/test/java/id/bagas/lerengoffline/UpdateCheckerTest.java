package id.bagas.lerengoffline;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class UpdateCheckerTest {

    private static String manifest(int versionCode, boolean mandatory, int minSupported, String apkUrl) {
        return "{\"schema\":\"lereng.update/1\","
                + "\"versionCode\":" + versionCode + ","
                + "\"versionName\":\"2.5.1\","
                + "\"mandatory\":" + mandatory + ","
                + "\"minSupportedVersionCode\":" + minSupported + ","
                + "\"apkUrl\":\"" + apkUrl + "\","
                + "\"notes\":[\"Perbaikan peta rute\",\"Ekspor lebih cepat\"],"
                + "\"sizeBytes\":298736,"
                + "\"sha256\":\"abc\"}";
    }

    @Test public void currentVersionHasNoUpdate() throws Exception {
        assertNull(UpdateChecker.parse(manifest(9, true, 0, "https://example.com/app.apk"), 9));
    }

    @Test public void olderManifestIsIgnored() throws Exception {
        assertNull(UpdateChecker.parse(manifest(8, true, 0, "https://example.com/app.apk"), 9));
    }

    @Test public void newerOptionalUpdateIsReported() throws Exception {
        UpdateChecker.UpdateInfo info =
                UpdateChecker.parse(manifest(10, false, 0, "https://example.com/app.apk"), 9);
        assertNotNull(info);
        assertEquals(10, info.versionCode);
        assertEquals("2.5.1", info.versionName);
        assertFalse(info.mandatory);
        assertEquals(298736L, info.sizeBytes);
        assertTrue(info.notes.contains("Perbaikan peta rute"));
        assertTrue(info.notes.contains("• Ekspor lebih cepat"));
    }

    @Test public void mandatoryFlagIsHonoured() throws Exception {
        UpdateChecker.UpdateInfo info =
                UpdateChecker.parse(manifest(10, true, 0, "https://example.com/app.apk"), 9);
        assertNotNull(info);
        assertTrue(info.mandatory);
    }

    @Test public void raisingMinSupportedForcesMandatory() throws Exception {
        UpdateChecker.UpdateInfo info =
                UpdateChecker.parse(manifest(10, false, 9, "https://example.com/app.apk"), 8);
        assertNotNull(info);
        assertTrue(info.mandatory);
    }

    @Test public void minSupportedAtOrBelowInstalledKeepsUpdateOptional() throws Exception {
        UpdateChecker.UpdateInfo info =
                UpdateChecker.parse(manifest(10, false, 8, "https://example.com/app.apk"), 8);
        assertNotNull(info);
        assertFalse(info.mandatory);
    }

    @Test public void plainHttpDownloadLinkIsRejected() {
        try {
            UpdateChecker.parse(manifest(10, false, 0, "http://example.com/app.apk"), 9);
            fail("http apkUrl seharusnya ditolak");
        } catch (Exception exc) {
            assertTrue(String.valueOf(exc.getMessage()).contains("HTTPS"));
        }
    }

    @Test public void unknownSchemaIsRejected() {
        try {
            UpdateChecker.parse("{\"schema\":\"lain/1\",\"versionCode\":10,"
                    + "\"apkUrl\":\"https://example.com/app.apk\"}", 9);
            fail("skema asing seharusnya ditolak");
        } catch (Exception exc) {
            assertTrue(String.valueOf(exc.getMessage()).contains("skema"));
        }
    }

    @Test public void missingVersionCodeIsRejected() {
        try {
            UpdateChecker.parse("{\"schema\":\"lereng.update/1\","
                    + "\"apkUrl\":\"https://example.com/app.apk\"}", 9);
            fail("versionCode kosong seharusnya ditolak");
        } catch (Exception exc) {
            assertTrue(String.valueOf(exc.getMessage()).contains("versionCode"));
        }
    }
}
