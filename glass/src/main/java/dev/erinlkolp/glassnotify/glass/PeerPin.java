package dev.erinlkolp.glassnotify.glass;

import android.content.SharedPreferences;

/**
 * Trust-on-first-use pinning for the phone's Bluetooth address.
 *
 * The server socket would otherwise accept a connection from anything in range
 * that knows the service UUID, which means a stranger could push text into the
 * wearer's field of view. The first device to connect is remembered; anything
 * else is refused.
 *
 * Spec section 11.1 requires a reset path, because Glass's own address is
 * regenerated on a /data wipe and a replacement phone has a different MAC.
 * `adb shell pm clear dev.erinlkolp.glassnotify.glass` clears this.
 */
public final class PeerPin {

    private static final String KEY_ADDRESS = "pinned_peer_address";

    private final SharedPreferences prefs;

    public PeerPin(SharedPreferences prefs) {
        if (prefs == null) {
            throw new NullPointerException("prefs");
        }
        this.prefs = prefs;
    }

    /** Null until something has connected. */
    public String pinnedAddress() {
        return prefs.getString(KEY_ADDRESS, null);
    }

    /** True if nothing is pinned yet, or the address matches what is. */
    public boolean isAllowed(String address) {
        if (address == null) {
            return false;
        }
        String pinned = pinnedAddress();
        return pinned == null || pinned.equalsIgnoreCase(address);
    }

    /** Records the address if none is pinned. Does nothing otherwise. */
    public void pinIfUnset(String address) {
        if (address == null) {
            throw new NullPointerException("address");
        }
        if (pinnedAddress() == null) {
            prefs.edit().putString(KEY_ADDRESS, address).commit();
        }
    }

    public void clear() {
        prefs.edit().remove(KEY_ADDRESS).commit();
    }
}
