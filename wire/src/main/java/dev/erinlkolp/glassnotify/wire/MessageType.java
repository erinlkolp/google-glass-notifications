package dev.erinlkolp.glassnotify.wire;

/** Frame type codes. */
public final class MessageType {

    public static final int HELLO = 1;
    public static final int SNAPSHOT = 2;
    public static final int PING = 3;

    private MessageType() {
    }
}
