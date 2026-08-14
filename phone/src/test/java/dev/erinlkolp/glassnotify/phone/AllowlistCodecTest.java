package dev.erinlkolp.glassnotify.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import dev.erinlkolp.glassnotify.wire.Tier;

public class AllowlistCodecTest {

    @Test
    public void roundTripsRules() {
        Map<String, Tier> rules = new HashMap<String, Tier>();
        rules.put("org.thoughtcrime.securesms", Tier.INTERRUPT);
        rules.put("com.slack", Tier.QUEUE);

        Map<String, Tier> decoded = AllowlistStore.decode(AllowlistStore.encode(rules));

        assertEquals(2, decoded.size());
        assertEquals(Tier.INTERRUPT, decoded.get("org.thoughtcrime.securesms"));
        assertEquals(Tier.QUEUE, decoded.get("com.slack"));
    }

    @Test
    public void roundTripsAnEmptyMap() {
        assertTrue(AllowlistStore.decode(AllowlistStore.encode(new HashMap<String, Tier>())).isEmpty());
    }

    @Test
    public void skipsMalformedEntriesRatherThanThrowing() {
        // A hand-edited or half-migrated preference must not crash the listener
        // service on boot.
        Set<String> raw = new HashSet<String>();
        raw.add("org.thoughtcrime.securesms|1");
        raw.add("garbage-with-no-separator");
        raw.add("com.example|999");
        raw.add("|1");

        Map<String, Tier> decoded = AllowlistStore.decode(raw);

        assertEquals(1, decoded.size());
        assertEquals(Tier.INTERRUPT, decoded.get("org.thoughtcrime.securesms"));
    }

    @Test
    public void toleratesNullFromSharedPreferences() {
        assertTrue(AllowlistStore.decode(null).isEmpty());
    }

    @Test
    public void packageNamesContainingTheSeparatorDoNotCorruptTheSet() {
        // Package names cannot contain '|', but proving the split is anchored
        // to the last separator costs nothing and documents the assumption.
        Map<String, Tier> rules = new HashMap<String, Tier>();
        rules.put("com.example.app", Tier.QUEUE);

        assertEquals(Tier.QUEUE,
                AllowlistStore.decode(AllowlistStore.encode(rules)).get("com.example.app"));
    }

    @Test
    public void roundTripsTheChirpTier() {
        Map<String, Tier> rules = new HashMap<String, Tier>();
        rules.put("com.discord", Tier.INTERRUPT_CHIRP);

        Map<String, Tier> decoded = AllowlistStore.decode(AllowlistStore.encode(rules));

        assertEquals(1, decoded.size());
        assertEquals(Tier.INTERRUPT_CHIRP, decoded.get("com.discord"));
    }

    @Test
    public void rulesSavedBeforeTheChirpTierExistedStillDecode() {
        // Hand-written in the on-disk format, as an older build would have left
        // it: rules saved against protocol version 1 must survive the upgrade.
        Set<String> legacy = new HashSet<String>();
        legacy.add("org.thoughtcrime.securesms|1");
        legacy.add("com.slack|2");

        Map<String, Tier> decoded = AllowlistStore.decode(legacy);

        assertEquals(2, decoded.size());
        assertEquals(Tier.INTERRUPT, decoded.get("org.thoughtcrime.securesms"));
        assertEquals(Tier.QUEUE, decoded.get("com.slack"));
    }
}
