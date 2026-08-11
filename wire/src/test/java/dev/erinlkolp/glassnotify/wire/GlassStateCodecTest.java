package dev.erinlkolp.glassnotify.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class GlassStateCodecTest {

    @Test
    public void roundTripsWhilePlugged() throws Exception {
        GlassState original = new GlassState(100, true);
        assertEquals(original, GlassStateCodec.decode(GlassStateCodec.encode(original)));
    }

    @Test
    public void roundTripsWhileUnplugged() throws Exception {
        GlassState original = new GlassState(37, false);
        assertEquals(original, GlassStateCodec.decode(GlassStateCodec.encode(original)));
    }

    @Test
    public void roundTripsBothBounds() throws Exception {
        assertEquals(new GlassState(0, false),
                GlassStateCodec.decode(GlassStateCodec.encode(new GlassState(0, false))));
        assertEquals(new GlassState(100, true),
                GlassStateCodec.decode(GlassStateCodec.encode(new GlassState(100, true))));
    }

    @Test
    public void bodyIsTwoBytes() throws Exception {
        // Small enough that the write is effectively atomic at the RFCOMM
        // layer, which matters for a message sent from a thread that must
        // never stall. Spec section 5.2.
        assertEquals(2, GlassStateCodec.encode(new GlassState(100, true)).length);
    }

    @Test
    public void rejectsAnOutOfRangeLevelOnTheWire() {
        // A corrupt or hostile frame must raise ProtocolException - an
        // IOException the reader already handles - not IllegalArgumentException
        // escaping from the GlassState constructor.
        try {
            GlassStateCodec.decode(new byte[] {(byte) 200, 1});
            fail("expected ProtocolException");
        } catch (java.io.IOException expected) {
            assertEquals(ProtocolException.class, expected.getClass());
        }
    }

    @Test
    public void rejectsATruncatedBody() {
        try {
            GlassStateCodec.decode(new byte[] {50});
            fail("expected an IOException");
        } catch (java.io.IOException expected) {
        }
    }
}
