package dev.erinlkolp.glassnotify.phone;

import java.io.IOException;
import java.io.InputStream;

import dev.erinlkolp.glassnotify.wire.Frame;
import dev.erinlkolp.glassnotify.wire.FrameCodec;
import dev.erinlkolp.glassnotify.wire.GlassStateCodec;
import dev.erinlkolp.glassnotify.wire.MessageType;
import dev.erinlkolp.glassnotify.wire.Protocol;

/**
 * Reads the reverse channel: Glass to phone.
 *
 * <h3>What this may touch</h3>
 *
 * The input stream, and nothing else. Not the output stream, not
 * {@code wakeLock}, not {@code backoff}, not {@code connectedSocket}.
 * {@link LinkClientService} is built on there being exactly one writer, and
 * this class exists alongside that guarantee rather than inside it. A reply
 * sent from here would break the invariant its whole threading design rests
 * on. If you find yourself wanting to write from this class, add a flag the
 * worker thread reads instead - that is the pattern {@code onSnapshot} already
 * uses.
 *
 * <h3>Failure handling</h3>
 *
 * {@link #run} never throws and never escalates. A dead socket, a malformed
 * body, a version we do not know: log nothing, deliver nothing, return. It does
 * not tear the link down or disturb the backoff, because the PING loop is the
 * only liveness authority on this end and two of them would fight. The worst
 * case is that the reverse channel goes quiet for one session while
 * notifications keep flowing normally, which is the correct trade.
 *
 * <h3>Why there is no logging in here</h3>
 *
 * {@code android.util.Log} throws "not mocked" under plain JUnit, and keeping
 * this class free of Android lets the whole reader be tested on the JVM against
 * a fragmented stream. The caller logs when the thread ends.
 */
public final class LinkReader implements Runnable {

    /** Called on the reader thread. Implementations must not block. */
    public interface Listener {
        void onGlassState(dev.erinlkolp.glassnotify.wire.GlassState state);
    }

    private final InputStream in;
    private final Listener listener;

    public LinkReader(InputStream in, Listener listener) {
        if (in == null) {
            throw new NullPointerException("in");
        }
        if (listener == null) {
            throw new NullPointerException("listener");
        }
        this.in = in;
        this.listener = listener;
    }

    @Override
    public void run() {
        try {
            while (true) {
                Frame frame = FrameCodec.read(in);

                if (frame.version != Protocol.VERSION) {
                    // Unlike Glass, the phone does not surface this. Glass
                    // shows a mismatch because the wearer would otherwise see
                    // a blank prism and assume the app is broken; here the
                    // forward path is still working and there is nothing to
                    // explain.
                    return;
                }

                if (frame.type == MessageType.GLASS_STATE) {
                    listener.onGlassState(GlassStateCodec.decode(frame.body));
                }
                // Anything else is ignored, so a newer Glass can add messages
                // without breaking an older phone.
            }
        } catch (IOException e) {
            // Includes ProtocolException. Either way the session is over.
        }
    }
}
