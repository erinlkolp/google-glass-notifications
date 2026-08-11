## Task 1: The wire message

**Files:**
- Create: `wire/src/main/java/dev/erinlkolp/glassnotify/wire/GlassState.java`
- Create: `wire/src/main/java/dev/erinlkolp/glassnotify/wire/GlassStateCodec.java`
- Modify: `wire/src/main/java/dev/erinlkolp/glassnotify/wire/MessageType.java`
- Test: `wire/src/test/java/dev/erinlkolp/glassnotify/wire/GlassStateTest.java`
- Test: `wire/src/test/java/dev/erinlkolp/glassnotify/wire/GlassStateCodecTest.java`

**Interfaces:**
- Consumes: `Protocol`, `ProtocolException` (existing).
- Produces:
  - `MessageType.GLASS_STATE` — `public static final int`, value `4`.
  - `new GlassState(int batteryLevel, boolean onPower)` — throws `IllegalArgumentException` if level is outside 0–100. Public final fields `batteryLevel` and `onPower`. Implements `equals`/`hashCode`.
  - `GlassStateCodec.encode(GlassState) -> byte[]` (throws `IOException`)
  - `GlassStateCodec.decode(byte[]) -> GlassState` (throws `IOException`)

- [ ] **Step 1: Write the failing tests**

`wire/src/test/java/dev/erinlkolp/glassnotify/wire/GlassStateTest.java`:

```java
package dev.erinlkolp.glassnotify.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class GlassStateTest {

    @Test
    public void keepsWhatItIsGiven() {
        GlassState state = new GlassState(72, true);
        assertEquals(72, state.batteryLevel);
        assertTrue(state.onPower);
    }

    @Test
    public void acceptsBothBounds() {
        assertEquals(0, new GlassState(0, false).batteryLevel);
        assertEquals(100, new GlassState(100, true).batteryLevel);
    }

    @Test
    public void rejectsNegativeLevel() {
        try {
            new GlassState(-1, false);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // The constructor is the only guard; a bad level must not travel.
        }
    }

    @Test
    public void rejectsLevelOverOneHundred() {
        try {
            new GlassState(101, false);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void equalsComparesBothFields() {
        assertEquals(new GlassState(100, true), new GlassState(100, true));
        assertNotEquals(new GlassState(100, true), new GlassState(100, false));
        assertNotEquals(new GlassState(99, true), new GlassState(100, true));
    }

    @Test
    public void equalStatesShareAHashCode() {
        assertEquals(new GlassState(55, true).hashCode(), new GlassState(55, true).hashCode());
    }

    @Test
    public void isNotEqualToOtherTypes() {
        assertFalse(new GlassState(50, false).equals("50"));
    }
}
```

`wire/src/test/java/dev/erinlkolp/glassnotify/wire/GlassStateCodecTest.java`:

```java
package dev.erinlkolp.glassnotify.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class GlassStateCodecTest {

    @Test
    public void roundTripsWhilePlugged() throws Exception {
        GlassState original = new GlassState(100, true);
        assertEquals(original, GlassStateCodec.decode(GlassStateCodec.encode(original)));
    }

    @Test
    public void roundTripsWhileUnplugged() throws Exception {
        GlassState original = new GlassState(37, false);
        assertEquals(original, GlassStateCodec.decode(GlassStateCodec.encode(original)));
    }

    @Test
    public void roundTripsBothBounds() throws Exception {
        assertEquals(new GlassState(0, false),
                GlassStateCodec.decode(GlassStateCodec.encode(new GlassState(0, false))));
        assertEquals(new GlassState(100, true),
                GlassStateCodec.decode(GlassStateCodec.encode(new GlassState(100, true))));
    }

    @Test
    public void bodyIsTwoBytes() throws Exception {
        // Small enough that the write is effectively atomic at the RFCOMM
        // layer, which matters for a message sent from a thread that must
        // never stall. Spec section 5.2.
        assertEquals(2, GlassStateCodec.encode(new GlassState(100, true)).length);
    }

    @Test
    public void rejectsAnOutOfRangeLevelOnTheWire() {
        // A corrupt or hostile frame must raise ProtocolException - an
        // IOException the reader already handles - not IllegalArgumentException
        // escaping from the GlassState constructor.
        try {
            GlassStateCodec.decode(new byte[] {(byte) 200, 1});
            fail("expected ProtocolException");
        } catch (java.io.IOException expected) {
            assertEquals(ProtocolException.class, expected.getClass());
        }
    }

    @Test
    public void rejectsATruncatedBody() {
        try {
            GlassStateCodec.decode(new byte[] {50});
            fail("expected an IOException");
        } catch (java.io.IOException expected) {
        }
    }
}
```

- [ ] **Step 2: Run the tests and verify they fail**

Run: `./gradlew :wire:test --tests '*GlassState*'`
Expected: FAIL — compilation error, `GlassState` and `GlassStateCodec` do not exist.

- [ ] **Step 3: Add the message type**

Replace the body of `wire/src/main/java/dev/erinlkolp/glassnotify/wire/MessageType.java`:

```java
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
```

- [ ] **Step 4: Write `GlassState`**

Create `wire/src/main/java/dev/erinlkolp/glassnotify/wire/GlassState.java`:

```java
package dev.erinlkolp.glassnotify.wire;

/**
 * Glass's own battery state, reported to the phone.
 *
 * The only Glass to phone message in the protocol. Deliberately carries state
 * rather than an event: the phone decides what is worth alerting about, which
 * is what lets a full charge that happened while the link was down still be
 * noticed when it comes back - and lets one that has since been unplugged
 * correctly pass unremarked.
 */
public final class GlassState {

    /** Percentage, 0-100, already normalised from the raw level/scale pair. */
    public final int batteryLevel;

    /** True when Glass is plugged into any power source. */
    public final boolean onPower;

    public GlassState(int batteryLevel, boolean onPower) {
        if (batteryLevel < 0 || batteryLevel > 100) {
            throw new IllegalArgumentException(
                    "batteryLevel " + batteryLevel + " is outside 0..100");
        }
        this.batteryLevel = batteryLevel;
        this.onPower = onPower;
    }

    /**
     * Value equality, and load-bearing: the Glass-side watcher uses it to
     * decide whether a battery broadcast is worth sending at all.
     * ACTION_BATTERY_CHANGED also fires on temperature and voltage changes, so
     * without this the link would carry a frame every few seconds.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof GlassState)) {
            return false;
        }
        GlassState other = (GlassState) o;
        return batteryLevel == other.batteryLevel && onPower == other.onPower;
    }

    @Override
    public int hashCode() {
        return 31 * batteryLevel + (onPower ? 1 : 0);
    }

    @Override
    public String toString() {
        return "GlassState{" + batteryLevel + "%" + (onPower ? " on power" : "") + "}";
    }
}
```

- [ ] **Step 5: Write `GlassStateCodec`**

Create `wire/src/main/java/dev/erinlkolp/glassnotify/wire/GlassStateCodec.java`:

```java
package dev.erinlkolp.glassnotify.wire;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Encodes and decodes the GLASS_STATE frame body.
 *
 * <pre>
 * uint8   batteryLevel   0-100
 * uint8   onPower        0 or 1
 * </pre>
 */
public final class GlassStateCodec {

    private GlassStateCodec() {
    }

    public static byte[] encode(GlassState state) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(state.batteryLevel);
        out.writeBoolean(state.onPower);
        out.flush();
        return bytes.toByteArray();
    }

    public static GlassState decode(byte[] body) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
        int level = in.readUnsignedByte();
        boolean onPower = in.readBoolean();

        // readUnsignedByte cannot go negative, so only the ceiling needs a
        // check. Raised as ProtocolException rather than letting the
        // GlassState constructor throw IllegalArgumentException: the reader
        // treats IOException as "this session is over" and would let an
        // unchecked exception kill its thread with no handler. Same reasoning
        // as the unknown-tier guard in SnapshotCodec.
        if (level > 100) {
            throw new ProtocolException("battery level " + level + " is outside 0..100");
        }
        return new GlassState(level, onPower);
    }
}
```

- [ ] **Step 6: Run the tests and verify they pass**

Run: `./gradlew :wire:test --tests '*GlassState*'`
Expected: PASS, 13 tests.

- [ ] **Step 7: Run the full suite to confirm nothing regressed**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. `wire` is now 58 tests; `glass` 32 and `phone` 25 are unchanged.

- [ ] **Step 8: Commit**

```bash
git add wire/src/main/java/dev/erinlkolp/glassnotify/wire/GlassState.java \
        wire/src/main/java/dev/erinlkolp/glassnotify/wire/GlassStateCodec.java \
        wire/src/main/java/dev/erinlkolp/glassnotify/wire/MessageType.java \
        wire/src/test/java/dev/erinlkolp/glassnotify/wire/GlassStateTest.java \
        wire/src/test/java/dev/erinlkolp/glassnotify/wire/GlassStateCodecTest.java
git commit -m "feat(wire): add the GLASS_STATE message

The first Glass -> phone message in the protocol. Carries battery level
and whether Glass is on power, in a two-byte body.

Protocol.VERSION deliberately does not move. Unknown frame types are
already ignored on both sides, so an old build paired with a new one goes
quiet rather than refusing the link - which is what a version bump would
do instead.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

