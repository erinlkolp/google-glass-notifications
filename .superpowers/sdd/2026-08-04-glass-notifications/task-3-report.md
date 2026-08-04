# Task 3: Message Bodies - Report

## Summary

Implemented two message body codecs for the wire protocol and three guard tests, completing the `wire` module. All implementations follow the exact specifications from the brief, with proper field ordering for wire format compatibility and comprehensive error handling.

## What Was Implemented

### Codec Implementations

1. **HelloCodec.java** - Encodes/decodes HELLO frame bodies
   - Uses DataInputStream/DataOutputStream for binary serialization
   - Encodes: deviceName (UTF-8), deviceAddress (UTF-8)
   - Handles non-ASCII characters (emoji, umlauts) via writeUTF/readUTF
   - Rejects truncated input via IOException from readUTF

2. **SnapshotCodec.java** - Encodes/decodes SNAPSHOT frame bodies
   - Encodes: snapshotId (int64), itemCount (int16), then for each item: key, appLabel, title, text, postedAt, tierCode
   - Validates item count before allocating (guards against negative counts and overflow)
   - Rejects unknown tier codes by checking Tier.fromCode() result, throwing ProtocolException if null
   - Asserts worst-case snapshot (MAX_ITEMS items with MAX_TEXT_CHARS text each) fits in one frame (< 8KB)
   - Encodes tier as a single byte via writeByte(item.tier.code)

### Test Implementations

1. **HelloCodecTest.java** - 3 tests
   - `roundTrips()`: Verifies basic encode/decode cycle preserves deviceName and deviceAddress
   - `handlesNonAsciiNames()`: Verifies UTF-8 handling with emoji (✨) and umlauts (über)
   - `rejectsTruncatedInput()`: Verifies IOException thrown when input is incomplete

2. **SnapshotCodecTest.java** - 8 tests
   - `roundTripsAPopulatedSnapshot()`: Verifies encode/decode of snapshot with 2 items of different tiers
   - `roundTripsAnEmptySnapshot()`: Verifies empty snapshot round-trips correctly
   - `preservesOrder()`: Verifies order of all MAX_ITEMS items is preserved (20 items)
   - `aFullSnapshotFitsComfortablyInOneFrame()`: Worst-case assertion that max snapshot < 8KB and < MAX_FRAME_BYTES
   - `rejectsAnItemCountOverTheCap()`: Verifies ProtocolException when count > MAX_ITEMS
   - `rejectsANegativeItemCount()`: Verifies ProtocolException for negative count
   - `rejectsAnUnknownTierCode()`: Verifies ProtocolException when tier code is unknown (77)
   - `refusesToEncodeMoreThanTheCap()`: Verifies encoder throws when given > MAX_ITEMS

3. **NoAndroidImportsTest.java** - 1 test
   - `noSourceFileImportsAndroid()`: Scans all .java source files for android.* or androidx.* imports
   - Executes with module directory as working directory (reads src/main/java as relative path)
   - Ensures wire module can be tested on host JVM without Android dependencies

## TDD Evidence

### RED: Tests fail because codecs don't exist

Command executed:
```bash
./gradlew :wire:test
```

Output (abbreviated):
```
> Task :wire:compileTestJava FAILED

/wire/src/test/java/.../HelloCodecTest.java:14: error: cannot find symbol
    Hello decoded = HelloCodec.decode(HelloCodec.encode(new Hello("V30", ...)));
                    ^
  symbol:   variable HelloCodec

/wire/src/test/java/.../SnapshotCodecTest.java:28: error: cannot find symbol
    Snapshot decoded = SnapshotCodec.decode(SnapshotCodec.encode(original));
                       ^
  symbol:   variable SnapshotCodec

16 errors (HelloCodec referenced 6 times, SnapshotCodec referenced 10 times)
```

Why this failure was expected: HelloCodec and SnapshotCodec classes did not yet exist, so test compilation failed. This is the correct RED state for TDD.

### GREEN: All tests pass after implementation

Command executed:
```bash
./gradlew clean :wire:test
```

Output:
```
> Task :wire:test

BUILD SUCCESSFUL in 1s
4 actionable tasks: 4 executed
```

Test Results Summary:
- HelloCodecTest: 3 tests, 0 failures, 0 errors
- SnapshotCodecTest: 8 tests, 0 failures, 0 errors
- NoAndroidImportsTest: 1 test, 0 failures, 0 errors
- Previous tests (Task 1-2): 24 tests, 0 failures, 0 errors
- **Total: 36 tests, 0 failures, 0 errors** (matches brief expectation)

Individual test results from XML:
- HelloCodecTest.roundTrips: PASS (0.0s)
- HelloCodecTest.handlesNonAsciiNames: PASS (0.0s)
- HelloCodecTest.rejectsTruncatedInput: PASS (0.001s)
- SnapshotCodecTest.roundTripsAPopulatedSnapshot: PASS (0.0s)
- SnapshotCodecTest.roundTripsAnEmptySnapshot: PASS (0.0s)
- SnapshotCodecTest.preservesOrder: PASS (0.001s)
- SnapshotCodecTest.aFullSnapshotFitsComfortablyInOneFrame: PASS (0.001s)
- SnapshotCodecTest.rejectsAnItemCountOverTheCap: PASS (0.0s)
- SnapshotCodecTest.rejectsANegativeItemCount: PASS (0.001s)
- SnapshotCodecTest.rejectsAnUnknownTierCode: PASS (0.0s)
- SnapshotCodecTest.refusesToEncodeMoreThanTheCap: PASS (0.0s)
- NoAndroidImportsTest.noSourceFileImportsAndroid: PASS (0.002s)

## Files Changed

Created (5 files, 350 insertions):
- `wire/src/main/java/dev/erinlkolp/glassnotify/wire/HelloCodec.java` (30 lines)
- `wire/src/main/java/dev/erinlkolp/glassnotify/wire/SnapshotCodec.java` (85 lines)
- `wire/src/test/java/dev/erinlkolp/glassnotify/wire/HelloCodecTest.java` (31 lines)
- `wire/src/test/java/dev/erinlkolp/glassnotify/wire/SnapshotCodecTest.java` (134 lines)
- `wire/src/test/java/dev/erinlkolp/glassnotify/wire/NoAndroidImportsTest.java` (60 lines)

Commit:
```
a49828298a8e47ce9dabd2bf1f7b2901497e9a8f
feat(wire): add HELLO and SNAPSHOT body codecs
```

## Self-Review Findings

### Correctness
✓ Field order in codecs matches brief exactly (required for wire format compatibility)
✓ HelloCodec uses writeUTF/readUTF which handles non-ASCII characters correctly
✓ SnapshotCodec validates item count bounds before allocating ArrayList
✓ SnapshotCodec checks Tier.fromCode() for null and throws ProtocolException
✓ All test assertions verify actual codec behavior (not just "smoke tests")
✓ Non-ASCII characters (über, ✨) preserved in test file as UTF-8

### Completeness
✓ HelloCodec.encode() and decode() both implemented
✓ SnapshotCodec.encode() and decode() both implemented
✓ HelloCodecTest has 3 tests (round trip, non-ASCII, truncation)
✓ SnapshotCodecTest has 8 tests (populated, empty, order, size, 3 error cases, encoder cap)
✓ NoAndroidImportsTest verifies zero android.* imports
✓ Brief commit message transcribed exactly

### Java 8 Compliance
✓ No `var` keyword (uses explicit types throughout)
✓ No post-8 APIs (only java.io.* used)
✓ No Kotlin
✓ No AndroidX imports
✓ No android.* imports anywhere

### Test Quality
✓ HelloCodecTest.roundTrips verifies both fields after decode
✓ HelloCodecTest.handlesNonAsciiNames explicitly tests emoji and umlaut
✓ HelloCodecTest.rejectsTruncatedInput constructs incomplete data to trigger error
✓ SnapshotCodecTest.roundTripsAPopulatedSnapshot verifies tier values preserved
✓ SnapshotCodecTest.preservesOrder tests all MAX_ITEMS items
✓ SnapshotCodecTest.aFullSnapshotFitsComfortablyInOneFrame uses maximal text length
✓ SnapshotCodecTest.rejectsAnItemCountOverTheCap tests boundary condition
✓ SnapshotCodecTest.rejectsANegativeItemCount tests negative boundary
✓ SnapshotCodecTest.rejectsAnUnknownTierCode uses tier code 77 (known invalid)
✓ SnapshotCodecTest.refusesToEncodeMoreThanTheCap verifies belt-and-braces check

### Wire Format Compliance
✓ HelloCodec: deviceName, deviceAddress (in correct order)
✓ SnapshotCodec: snapshotId (int64), itemCount (int16), items array
✓ Item format: key, appLabel, title, text, postedAt (int64), tierCode (uint8/byte)
✓ No variable-length or optional fields that could break protocol

## Concerns

None identified. All tests pass, all implementations match the brief exactly, field ordering is correct for wire format, and the module remains free of Android dependencies.

## Verification

The task is complete as specified in the brief:
- ✓ Written failing tests (RED)
- ✓ Verified tests fail (compilation errors)
- ✓ Implemented codecs exactly as specified
- ✓ Verified tests pass (36 total, all green)
- ✓ Committed with exact message from brief
- ✓ Self-reviewed for completeness and correctness
