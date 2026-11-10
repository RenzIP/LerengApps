package id.bagas.lerengoffline;

import android.os.ParcelUuid;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

public final class BleGroupCodec {
    private static final String SERVICE_UUID_TEXT = "9d7a4f80-6f2b-4c21-9a11-4c6572656e67";
    private static final String IDENTITY_CHARACTERISTIC_UUID_TEXT = "1a6cde71-9d0a-4c4e-a274-4c6572656e67";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int GROUP_TAG_BYTES = 6;
    private static final int ID_BYTES = 4;
    private static final int COMPACT_TAG_BYTES = 4;

    /**
     * Company ID untuk manufacturer data. 0xFFFF dipakai bersama (bukan milik vendor) karena
     * khusus pertukaran sesama aplikasi Lereng; iOS hanya bisa membaca kehadiran Android lewat
     * manufacturer data, tidak lewat Service Data kustom.
     */
    public static final int MANUFACTURER_ID = 0xFFFF;
    /** Delapan byte: empat byte potongan SHA-256 kode outing dan empat byte ID peserta. */
    public static final int COMPACT_PAYLOAD_BYTES = COMPACT_TAG_BYTES + ID_BYTES;

    private BleGroupCodec() {}

    public static ParcelUuid serviceUuid() { return ParcelUuid.fromString(SERVICE_UUID_TEXT); }
    public static UUID identityCharacteristicUuid() { return UUID.fromString(IDENTITY_CHARACTERISTIC_UUID_TEXT); }

    public static String normalizeGroupCode(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean isValidGroupCode(String value) {
        String normalized = normalizeGroupCode(value);
        return normalized.matches("[A-Z0-9]{8,16}");
    }

    public static String newGroupCode() {
        StringBuilder b = new StringBuilder(10);
        synchronized (RANDOM) {
            for (int i = 0; i < 10; i++) b.append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)]);
        }
        return b.toString();
    }

    public static String newParticipantId() {
        byte[] bytes = new byte[ID_BYTES];
        synchronized (RANDOM) { RANDOM.nextBytes(bytes); }
        return toHex(bytes);
    }

    public static boolean isValidParticipantId(String id) {
        return id != null && id.matches("(?i)[0-9a-f]{8}");
    }

    /** Ten bytes total: six bytes of an outing hash and four bytes of one-trip participant ID. */
    public static byte[] createPayload(String groupCode, String participantId) {
        String normalized = normalizeGroupCode(groupCode);
        if (!isValidGroupCode(normalized) || !isValidParticipantId(participantId)) return null;
        byte[] digest = sha256(normalized.getBytes(StandardCharsets.UTF_8));
        byte[] id = fromHex(participantId);
        byte[] payload = new byte[GROUP_TAG_BYTES + ID_BYTES];
        System.arraycopy(digest, 0, payload, 0, GROUP_TAG_BYTES);
        System.arraycopy(id, 0, payload, GROUP_TAG_BYTES, ID_BYTES);
        return payload;
    }

    /** Returns a peer ID only for the same outing; callers must discard other scan fields. */
    public static String parsePeerId(byte[] payload, String expectedGroupCode) {
        return parsePeerId(payload, expectedGroupCode, GROUP_TAG_BYTES);
    }

    /**
     * Delapan byte untuk manufacturer data di scan response. Lebih pendek dari Service Data
     * karena scan response legacy hanya punya 31 byte dan service UUID 128-bit sudah memakai 18.
     */
    public static byte[] createCompactPayload(String groupCode, String participantId) {
        String normalized = normalizeGroupCode(groupCode);
        if (!isValidGroupCode(normalized) || !isValidParticipantId(participantId)) return null;
        byte[] digest = sha256(normalized.getBytes(StandardCharsets.UTF_8));
        byte[] id = fromHex(participantId);
        byte[] payload = new byte[COMPACT_PAYLOAD_BYTES];
        System.arraycopy(digest, 0, payload, 0, COMPACT_TAG_BYTES);
        System.arraycopy(id, 0, payload, COMPACT_TAG_BYTES, ID_BYTES);
        return payload;
    }

    /** Pasangan dari {@link #createCompactPayload}; iOS memakai bentuk yang sama. */
    public static String parseCompactPeerId(byte[] payload, String expectedGroupCode) {
        return parsePeerId(payload, expectedGroupCode, COMPACT_TAG_BYTES);
    }

    private static String parsePeerId(byte[] payload, String expectedGroupCode, int tagBytes) {
        if (payload == null || payload.length != tagBytes + ID_BYTES || !isValidGroupCode(expectedGroupCode)) return null;
        byte[] expectedDigest = sha256(normalizeGroupCode(expectedGroupCode).getBytes(StandardCharsets.UTF_8));
        byte[] expectedTag = Arrays.copyOf(expectedDigest, tagBytes);
        byte[] suppliedTag = Arrays.copyOfRange(payload, 0, tagBytes);
        if (!MessageDigest.isEqual(expectedTag, suppliedTag)) return null;
        return toHex(Arrays.copyOfRange(payload, tagBytes, payload.length));
    }

    private static byte[] sha256(byte[] input) {
        try { return MessageDigest.getInstance("SHA-256").digest(input); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder b = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            int v = value & 0xff;
            b.append(HEX[v >>> 4]).append(HEX[v & 0x0f]);
        }
        return b.toString();
    }

    private static byte[] fromHex(String value) {
        String s = value.toUpperCase(Locale.ROOT);
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte)((Character.digit(s.charAt(i * 2), 16) << 4) | Character.digit(s.charAt(i * 2 + 1), 16));
        return out;
    }
}
