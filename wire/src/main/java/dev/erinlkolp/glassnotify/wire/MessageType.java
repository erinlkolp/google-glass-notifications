package dev.erinlkolp.glassnotify.wire;

/**
 * Frame type codes.
 *
 * Direction is part of a type's meaning. Types 1-3 are phone to Glass; type 4
 * is the only message that travels the other way. A receiver that sees a type
 * it does not expect on its side of the link should ignore it, which is what
 * the default branches in both link services already do.
 */
public final class MessageType {

    /** Phone to Glass. */
    public static final int HELLO = 1;

    /** Phone to Glass. */
    public static final int SNAPSHOT = 2;

    /** Phone to Glass. */
    public static final int PING = 3;

    /**
     * Glass to phone. Unsolicited battery state; see the charge-alert design,
     * section 5. This is the entire reverse channel - keep it that way.
     */
    public static final int GLASS_STATE = 4;

    private MessageType() {
    }
}
