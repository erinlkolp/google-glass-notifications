package dev.erinlkolp.glassnotify.glass;

import java.io.IOException;
import java.io.OutputStream;

import dev.erinlkolp.glassnotify.wire.FrameCodec;
import dev.erinlkolp.glassnotify.wire.GlassState;
import dev.erinlkolp.glassnotify.wire.GlassStateCodec;
import dev.erinlkolp.glassnotify.wire.MessageType;

/**
 * Writes Glass's battery state to the phone, on its own thread.
 *
 * <h3>Why a separate thread</h3>
 *
 * The accept thread spends its life blocked in FrameCodec.read, so it cannot
 * also write. More importantly it must not: if the phone on the other end is
 * an older build with no reader, its receive buffer eventually fills and a
 * write here blocks forever. On this thread that is harmless - snapshots keep
 * arriving and rendering exactly as before. On the accept thread it would stop
 * the prism.
 *
 * <h3>Single writer</h3>
 *
 * This is the only code on Glass that writes to the socket, mirroring the
 * discipline LinkClientService documents on the phone. The accept thread reads
 * and never writes; this thread writes and never reads.
 *
 * <h3>Coalescing</h3>
 *
 * {@link #offer} overwrites rather than queues, so several changes arriving
 * before the writer wakes collapse into the newest. State is idempotent - only
 * the current value ever mattered - so there is nothing to lose and no queue to
 * bound.
 *
 * No android.util.Log, so this is testable on the JVM. LinkServerService logs
 * when the thread ends.
 */
public final class StateWriter implements Runnable {

    private final OutputStream out;

    private final Object lock = new Object();

    /** The state waiting to be written, or null if there is none. */
    private GlassState pending; // guarded by lock

    private boolean stopped; // guarded by lock

    /**
     * @param initial the state to send as soon as the thread starts, or null if
     *                none is known yet. Sending on connect rather than only on
     *                change is what lets the phone notice a charge that
     *                finished while the link was down.
     */
    public StateWriter(OutputStream out, GlassState initial) {
        if (out == null) {
            throw new NullPointerException("out");
        }
        this.out = out;
        this.pending = initial;
    }

    /** Never blocks. Safe from any thread, including the main thread. */
    public void offer(GlassState state) {
        synchronized (lock) {
            pending = state;
            lock.notifyAll();
        }
    }

    /** Makes {@link #run} return. Idempotent. */
    public void stop() {
        synchronized (lock) {
            stopped = true;
            lock.notifyAll();
        }
    }

    @Override
    public void run() {
        try {
            while (true) {
                GlassState next;
                synchronized (lock) {
                    while (!stopped && pending == null) {
                        lock.wait();
                    }
                    if (stopped) {
                        return;
                    }
                    next = pending;
                    pending = null;
                }
                // Outside the lock: a blocked write must not also block offer().
                FrameCodec.write(out, MessageType.GLASS_STATE, GlassStateCodec.encode(next));
            }
        } catch (IOException e) {
            // The session is over. The accept thread finds out independently,
            // by its own read failing.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
