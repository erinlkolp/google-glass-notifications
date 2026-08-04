package dev.erinlkolp.glassnotify.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;

import org.junit.Test;

public class HelloCodecTest {

    @Test
    public void roundTrips() throws IOException {
        Hello decoded = HelloCodec.decode(HelloCodec.encode(new Hello("V30", "10:F1:F2:EE:90:8F")));

        assertEquals("V30", decoded.deviceName);
        assertEquals("10:F1:F2:EE:90:8F", decoded.deviceAddress);
    }

    @Test
    public void handlesNonAsciiNames() throws IOException {
        // Bluetooth device names are user-editable and routinely contain emoji.
        Hello decoded = HelloCodec.decode(HelloCodec.encode(new Hello("Erin's über phone ✨", "AA:BB:CC:DD:EE:FF")));

        assertEquals("Erin's über phone ✨", decoded.deviceName);
    }

    @Test
    public void rejectsTruncatedInput() {
        try {
            HelloCodec.decode(new byte[] {0, 5, 65});
            fail("expected IOException");
        } catch (IOException expected) {
        }
    }
}
