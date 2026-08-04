package dev.erinlkolp.glassnotify.phone;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import dev.erinlkolp.glassnotify.wire.Tier;

/**
 * Persists which packages are shown, and at what tier.
 *
 * Stored as a SharedPreferences string set of "packageName|tierCode" so the
 * encoding is inspectable and testable without an Android runtime.
 */
public final class AllowlistStore {

    private static final String KEY_RULES = "allowlist_rules";
    private static final char SEPARATOR = '|';

    private final SharedPreferences prefs;

    public AllowlistStore(SharedPreferences prefs) {
        if (prefs == null) {
            throw new NullPointerException("prefs");
        }
        this.prefs = prefs;
    }

    public Map<String, Tier> rules() {
        return decode(prefs.getStringSet(KEY_RULES, null));
    }

    public void put(String packageName, Tier tier) {
        Map<String, Tier> rules = rules();
        rules.put(packageName, tier);
        save(rules);
    }

    public void remove(String packageName) {
        Map<String, Tier> rules = rules();
        rules.remove(packageName);
        save(rules);
    }

    private void save(Map<String, Tier> rules) {
        prefs.edit().putStringSet(KEY_RULES, encode(rules)).apply();
    }

    static Set<String> encode(Map<String, Tier> rules) {
        Set<String> encoded = new HashSet<String>();
        for (Map.Entry<String, Tier> entry : rules.entrySet()) {
            encoded.add(entry.getKey() + SEPARATOR + entry.getValue().code);
        }
        return encoded;
    }

    static Map<String, Tier> decode(Set<String> raw) {
        Map<String, Tier> rules = new HashMap<String, Tier>();
        if (raw == null) {
            return rules;
        }
        for (String entry : raw) {
            int split = entry.lastIndexOf(SEPARATOR);
            if (split <= 0 || split == entry.length() - 1) {
                // No separator, or nothing on one side of it. Skip rather than
                // throw: a corrupt preference must not stop the service booting.
                continue;
            }
            String packageName = entry.substring(0, split);
            Tier tier;
            try {
                tier = Tier.fromCode(Integer.parseInt(entry.substring(split + 1)));
            } catch (NumberFormatException e) {
                continue;
            }
            if (tier != null) {
                rules.put(packageName, tier);
            }
        }
        return rules;
    }
}
