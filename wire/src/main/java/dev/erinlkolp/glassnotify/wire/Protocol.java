package dev.erinlkolp.glassnotify.wire;

import java.util.UUID;

/** Wire-protocol constants shared by both apps. */
public final class Protocol {

    /** Bumped on any incompatible change to framing or message bodies. */
    public static final int VERSION = 1;

    /**
     * Frames larger than this are rejected before any allocation, so a corrupted
     * length field cannot be turned into an OutOfMemoryError.
     */
    public static final int MAX_FRAME_BYTES = 64 * 1024;

    /** Snapshots carry at most this many items. */
    public static final int MAX_ITEMS = 20;

    /** Body text is truncated to this many characters by the phone, before sending. */
    public static final int MAX_TEXT_CHARS = 240;

    /** Titles are truncated to this many characters by the phone, before sending. */
    public static final int MAX_TITLE_CHARS = 80;

    /**
     * Keys are truncated to this many characters by the phone, before sending.
     *
     * StatusBarNotification.getKey() is "userId|package|id|tag|uid" and the tag
     * component is app-controlled and unbounded, so without a cap a single
     * misbehaving app can push a snapshot past MAX_FRAME_BYTES. 96 characters
     * comfortably covers a real package name plus a sane tag, which is all the
     * uniqueness the queue needs - the key is only ever compared for equality
     * between consecutive snapshots, never interpreted.
     */
    public static final int MAX_KEY_CHARS = 96;

    /**
     * App labels are truncated to this many characters by the phone.
     *
     * Rendered uppercase at 12dp with letter spacing on a 320x180dp prism, so
     * anything past roughly two dozen characters is ellipsised on screen
     * regardless. Same unbounded-input concern as MAX_KEY_CHARS: the label
     * comes from the app's own manifest.
     */
    public static final int MAX_APP_LABEL_CHARS = 24;

    /** SDP service record name advertised by the Glass server socket. */
    public static final String SERVICE_NAME = "GlassNotify";

    /** Fixed for the life of the project. Regenerating it breaks installed builds. */
    public static final UUID SERVICE_UUID =
            UUID.fromString("7d9313f0-110b-4d84-8daa-10389eba6b55");

    private Protocol() {
    }
}
