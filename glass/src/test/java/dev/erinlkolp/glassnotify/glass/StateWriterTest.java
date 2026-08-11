package dev.erinlkolp.glassnotify.glass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.junit.Test;

import dev.erinlkolp.glassnotify.wire.Frame;
import dev.erinlkolp.glassnotify.wire.FrameCodec;
import dev.erinlkolp.glassnotify.wire.GlassState;
import dev.erinlkolp.glassnotify.wire.GlassStateCodec;
import dev.erinlkolp.glassnotify.wire.MessageType;

public class StateWriterTest {

    /** One GLASS_STATE frame is 4 length bytes + version + type + 2 body. */
    private static final int FRAME_BYTES = 8;

    private final ByteArrayOutputStream sink = new ByteArrayOutputStream();

    /**
     * Waits for the writer to produce at least the given number of bytes.
     * ByteArrayOutputStream's methods are synchronized, so polling size() from
     * this thread is safe. A deadline rather than a fixed sleep keeps the test
     * fast when it passes and honest when it hangs.
     */
    private void awaitBytes(int count) throws Exception {
        long deadline = System.currentTimeMillis() + 2000L;
        while (sink.size() < count) {
            if (System.currentTimeMillis() > deadline) {
                fail("expected " + count + " bytes, only saw " + sink.size());
            }
            Thread.sleep(5L);
        }
    }

    private java.util.List<GlassState> decodeAll() throws Exception {
        java.util.List<GlassState> states = new java.util.ArrayList<GlassState>();
        ByteArrayInputStream in = new ByteArrayInputStream(sink.toByteArray());
        while (in.available() > 0) {
            Frame frame = FrameCodec.read(in);
            assertEquals(MessageType.GLASS_STATE, frame.type);
            states.add(GlassStateCodec.decode(frame.body));
        }
        return states;
    }

    @Test
    public void sendsTheInitialStateWithoutBeingAsked() throws Exception {
        // A connection must learn Glass's state immediately, not at the next
        // battery movement. This is what makes "alert on reconnect if still
        // charging" work at all.
        StateWriter writer = new StateWriter(sink, new GlassState(100, true));
        Thread thread = new Thread(writer);
        thread.start();

        awaitBytes(FRAME_BYTES);
        writer.stop();
        thread.join(2000L);

        assertFalse(thread.isAlive());
        assertEquals(1, decodeAll().size());
        assertEquals(new GlassState(100, true), decodeAll().get(0));
    }

    @Test
    public void sendsNothingUntilThereIsSomethingToSend() throws Exception {
        // A null initial state means no usable battery broadcast has arrived.
        StateWriter writer = new StateWriter(sink, null);
        Thread thread = new Thread(writer);
        thread.start();

        Thread.sleep(100L);
        assertEquals(0, sink.size());

        writer.offer(new GlassState(64, false));
        awaitBytes(FRAME_BYTES);
        writer.stop();
        thread.join(2000L);

        assertEquals(1, decodeAll().size());
        assertEquals(new GlassState(64, false), decodeAll().get(0));
    }

    @Test
    public void sendsEachOfferedState() throws Exception {
        StateWriter writer = new StateWriter(sink, null);
        Thread thread = new Thread(writer);
        thread.start();

        writer.offer(new GlassState(98, true));
        awaitBytes(FRAME_BYTES);
        writer.offer(new GlassState(100, true));
        awaitBytes(FRAME_BYTES * 2);

        writer.stop();
        thread.join(2000L);

        assertEquals(2, decodeAll().size());
        assertEquals(98, decodeAll().get(0).batteryLevel);
        assertEquals(100, decodeAll().get(1).batteryLevel);
    }

    @Test
    public void stopEndsTheThreadEvenWithNothingPending() throws Exception {
        StateWriter writer = new StateWriter(sink, null);
        Thread thread = new Thread(writer);
        thread.start();

        writer.stop();
        thread.join(2000L);

        assertFalse(thread.isAlive());
        assertEquals(0, sink.size());
    }

    @Test
    public void offerAfterStopIsHarmless() throws Exception {
        StateWriter writer = new StateWriter(sink, null);
        Thread thread = new Thread(writer);
        thread.start();
        writer.stop();
        thread.join(2000L);

        // The watcher fires on the main thread and can land after a session
        // has already been torn down. It must not throw.
        writer.offer(new GlassState(100, true));

        assertEquals(0, sink.size());
    }

    @Test
    public void aDeadStreamEndsTheThreadRatherThanThrowing() throws Exception {
        java.io.OutputStream broken = new java.io.OutputStream() {
            @Override
            public void write(int b) throws java.io.IOException {
                throw new java.io.IOException("socket closed");
            }
        };

        StateWriter writer = new StateWriter(broken, new GlassState(100, true));
        Thread thread = new Thread(writer);
        thread.start();
        thread.join(2000L);

        // run() must swallow it. An uncaught IOException on this thread would
        // be a crash with no handler.
        assertFalse(thread.isAlive());
    }
}
