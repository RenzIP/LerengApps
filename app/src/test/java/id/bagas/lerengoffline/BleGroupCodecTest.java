package id.bagas.lerengoffline;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class BleGroupCodecTest {
    private static final String GROUP = "LERENGABCD";
    private static final String PEER = "1A2B3C4D";

    @Test public void compactPayloadIsEightBytesAndRepeatable() {
        byte[] first = BleGroupCodec.createCompactPayload(GROUP, PEER);
        byte[] second = BleGroupCodec.createCompactPayload("  lerengabcd ", PEER.toLowerCase());
        assertNotNull(first);
        assertEquals(BleGroupCodec.COMPACT_PAYLOAD_BYTES, first.length);
        assertEquals(8, first.length);
        assertArrayEquals(first, second);
    }

    @Test public void compactPayloadRoundTrips() {
        byte[] payload = BleGroupCodec.createCompactPayload(GROUP, PEER);
        assertEquals(PEER, BleGroupCodec.parseCompactPeerId(payload, GROUP));
    }

    @Test public void compactPayloadRejectsOtherOuting() {
        byte[] payload = BleGroupCodec.createCompactPayload(GROUP, PEER);
        assertNull(BleGroupCodec.parseCompactPeerId(payload, "LERENGZZZZ"));
    }

    @Test public void compactAndFullPayloadsAreNotInterchangeable() {
        byte[] compact = BleGroupCodec.createCompactPayload(GROUP, PEER);
        byte[] full = BleGroupCodec.createPayload(GROUP, PEER);
        assertNotNull(full);
        assertEquals(10, full.length);
        // Bentuk 8 byte hanya dibaca lewat jalur manufacturer data, bentuk 10 byte lewat Service Data.
        assertNull(BleGroupCodec.parsePeerId(compact, GROUP));
        assertNull(BleGroupCodec.parseCompactPeerId(full, GROUP));
        assertEquals(PEER, BleGroupCodec.parsePeerId(full, GROUP));
    }

    @Test public void compactPayloadRejectsBadInput() {
        assertNull(BleGroupCodec.createCompactPayload("PENDEK", PEER));
        assertNull(BleGroupCodec.createCompactPayload(GROUP, "123"));
        assertNull(BleGroupCodec.parseCompactPeerId(new byte[7], GROUP));
        assertNull(BleGroupCodec.parseCompactPeerId(null, GROUP));
        assertNull(BleGroupCodec.parseCompactPeerId(BleGroupCodec.createCompactPayload(GROUP, PEER), ""));
    }
}
