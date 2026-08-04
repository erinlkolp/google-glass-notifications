package dev.erinlkolp.glassnotify.wire;

/** One decoded frame: header fields plus the still-encoded body. */
public final class Frame {

    /** Protocol version claimed by the sender. May not match Protocol.VERSION. */
    public final int version;

    /** One of the MessageType constants. May be unrecognised. */
    public final int type;

    /** Type-specific payload, decoded by HelloCodec or SnapshotCodec. */
    public final byte[] body;

    public Frame(int version, int type, byte[] body) {
        if (body == null) {
            throw new NullPointerException("body");
        }
        this.version = version;
        this.type = type;
        this.body = body;
    }
}
