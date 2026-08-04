package dev.erinlkolp.glassnotify.wire;

import java.io.IOException;

/**
 * Thrown when bytes on the wire violate the protocol. Extends IOException so
 * callers can treat it like any other stream failure: close the socket and
 * reconnect. Mid-stream resynchronisation is never attempted.
 */
public class ProtocolException extends IOException {

    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super(message);
    }
}
