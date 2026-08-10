package dev.erinlkolp.glassnotify.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import dev.erinlkolp.glassnotify.wire.FrameCodec;
import dev.erinlkolp.glassnotify.wire.GlassState;
import dev.erinlkolp.glassnotify.wire.GlassStateCodec;
import dev.erinlkolp.glassnotify.wire.MessageType;

public class LinkReaderTest {

    private final List<GlassState> seen = new ArrayList<GlassState>();

    private final LinkReader.Listener collector = new LinkReader.Listener() {
        @Override
        public void onGlassState(GlassState state) {
            seen.add(state);
        }
    };

    private static byte[] framed(GlassState... states) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (GlassState state : states) {
            FrameCodec.write(bytes, MessageType.GLASS_STATE, GlassStateCodec.encode(state));
        }
        return bytes.toByteArray();
    }

    private void readAll(InputStream in) {
        new LinkReader(in, collector).run();
    }

    @Test
    public void deliversASingleState() throws Exception {
        readAll(new ByteArrayInputStream(framed(new GlassState(100, true))));
        assertEquals(1, seen.size());
        assertEquals(new GlassState(100, true), seen.get(0));
    }

    @Test
    public void deliversEveryStateInOrder() throws Exception {
        readAll(new ByteArrayInputStream(
                framed(new GlassState(98, true), new GlassState(99, true),
                        new GlassState(100, true))));
        assertEquals(3, seen.size());
        assertEquals(98, seen.get(0).batteryLevel);
        assertEquals(99, seen.get(1).batteryLevel);
        assertEquals(100, seen.get(2).batteryLevel);
    }

    @Test
    public void survivesAStreamThatFragmentsEveryByte() throws Exception {
        // A real RFCOMM socket splits wherever it likes. One byte per read is
        // the worst case FrameCodec has to cope with.
        readAll(new ChunkedInputStream(
                framed(new GlassState(100, true), new GlassState(42, false)), 1));
        assertEquals(2, seen.size());
        assertEquals(new GlassState(42, false), seen.get(1));
    }

    @Test
    public void ignoresFrameTypesItDoesNotKnow() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        FrameCodec.write(bytes, MessageType.PING, new byte[0]);
        FrameCodec.write(bytes, 99, new byte[] {1, 2, 3});
        FrameCodec.write(bytes, MessageType.GLASS_STATE,
                GlassStateCodec.encode(new GlassState(100, true)));

        readAll(new ByteArrayInputStream(bytes.toByteArray()));

        // Skipped cleanly rather than desynchronising the stream: the state
        // frame behind them still arrives.
        assertEquals(1, seen.size());
        assertEquals(100, seen.get(0).batteryLevel);
    }

    @Test
    public void stopsOnAnUnknownProtocolVersion() throws Exception {
        byte[] good = framed(new GlassState(100, true));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        // A frame whose version byte is not ours, hand-assembled: length 2,
        // version 99, type GLASS_STATE, then a valid frame behind it.
        bytes.write(new byte[] {0, 0, 0, 4, 99, (byte) MessageType.GLASS_STATE, 100, 1});
        bytes.write(good);

        readAll(new ByteArrayInputStream(bytes.toByteArray()));

        // Nothing delivered, and it did not press on into the frame behind.
        assertTrue(seen.isEmpty());
    }

    @Test
    public void returnsQuietlyOnATruncatedStream() throws Exception {
        byte[] full = framed(new GlassState(100, true));
        byte[] cut = new byte[full.length - 1];
        System.arraycopy(full, 0, cut, 0, cut.length);

        // The contract is that run() never throws - the caller's only job is
        // to notice the thread ended.
        readAll(new ByteArrayInputStream(cut));

        assertTrue(seen.isEmpty());
    }

    @Test
    public void returnsQuietlyOnAnEmptyStream() {
        readAll(new ByteArrayInputStream(new byte[0]));
        assertTrue(seen.isEmpty());
    }

    @Test
    public void returnsQuietlyOnACorruptStateBody() throws Exception {
        // Length 3 so the body is one byte too long for a state, and the level
        // byte is out of range. GlassStateCodec raises ProtocolException.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(new byte[] {0, 0, 0, 4, 1, (byte) MessageType.GLASS_STATE, (byte) 200, 1});

        readAll(new ByteArrayInputStream(bytes.toByteArray()));

        assertTrue(seen.isEmpty());
    }
}
