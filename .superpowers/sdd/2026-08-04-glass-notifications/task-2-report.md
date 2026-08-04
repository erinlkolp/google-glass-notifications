# Task 2: Framing — Implementation Report

## Summary

Successfully implemented length-prefixed framing for the `wire` module. Added `Frame`, `FrameCodec`, and test helpers to support reliable message boundaries over RFCOMM's raw byte stream.

## Implementation Details

### What was implemented

1. **`Frame.java`** — Immutable data class holding decoded frame header fields (version, type) and body payload
2. **`FrameCodec.java`** — Stateless encoder/decoder for length-prefixed frames with pre-allocation validation
3. **`ChunkedInputStream.java`** — Test helper that fragments arbitrary byte streams to simulate RFCOMM fragmentation
4. **`FrameCodecTest.java`** — 13 tests covering round-trips, header layout, fragmented reads, boundary conditions, and corruption detection

### Design decisions

- **Length validation before allocation** — The `read()` method validates the declared length against `Protocol.MAX_FRAME_BYTES` before allocating the body buffer, preventing OutOfMemoryError from garbage length fields
- **Version pass-through** — Unrecognized versions are preserved and surfaced to the caller, allowing the service layer to distinguish "phone app out of date" from generic stream errors
- **DataInputStream reliance** — `readInt()` and `readFully()` already loop over short reads, making this correct against arbitrary socket fragmentation without manual retry logic
- **Flush on write** — `DataOutputStream.flush()` ensures length and header reach the wire immediately, critical for stream-based protocols

## Testing

### RED (Before Implementation)

Command: `./gradlew :wire:test --tests '*FrameCodecTest*'`

**Result:** FAIL (compilation error)

```
/home/ekolp/workspace/google-glass-notifications/wire/src/test/java/dev/erinlkolp/glassnotify/wire/FrameCodecTest.java:19: error: cannot find symbol
        FrameCodec.write(out, type, body);
        ^
  symbol:   variable FrameCodec
```

This was expected — `Frame` and `FrameCodec` classes did not exist.

### GREEN (After Implementation)

Command: `./gradlew :wire:test`

**Result:** BUILD SUCCESSFUL

Test results:
- **Total tests:** 24 (11 from Task 1 + 13 new FrameCodecTest)
- **FrameCodecTest:** 13/13 passing
  - `roundTripsASimpleFrame` ✓
  - `roundTripsAnEmptyBody` ✓
  - `headerLayoutIsExactlySpecified` ✓
  - `reassemblesAFrameSplitAtEveryPossibleBoundary` ✓ (sweeps chunk sizes 1 to 70 bytes)
  - `readsTwoFramesArrivingInOneBuffer` ✓
  - `readsFramesSplitAcrossReadsAndConcatenated` ✓
  - `rejectsAnAbsurdLengthWithoutAllocating` ✓ (2GB length)
  - `rejectsALengthTooSmallToHoldTheHeader` ✓
  - `rejectsANegativeLength` ✓
  - `throwsEofWhenTheStreamEndsMidHeader` ✓
  - `throwsEofWhenTheStreamEndsMidBody` ✓
  - `refusesToWriteAnOversizedBody` ✓
  - `preservesAVersionItDoesNotRecognise` ✓
- **Other tests:** 11/11 passing (unchanged from Task 1)

## Files Changed

- **Created:** `wire/src/main/java/dev/erinlkolp/glassnotify/wire/Frame.java` (25 lines)
- **Created:** `wire/src/main/java/dev/erinlkolp/glassnotify/wire/FrameCodec.java` (64 lines)
- **Created:** `wire/src/test/java/dev/erinlkolp/glassnotify/wire/ChunkedInputStream.java` (30 lines)
- **Created:** `wire/src/test/java/dev/erinlkolp/glassnotify/wire/FrameCodecTest.java` (243 lines)

## Self-Review Findings

✓ **Completeness:** All interfaces specified in the brief were implemented exactly
  - `Frame(int version, int type, byte[] body)` with public final fields
  - `FrameCodec.write(OutputStream out, int type, byte[] body):void`
  - `FrameCodec.read(InputStream in):Frame`

✓ **Test coverage:** All 13 specified test cases implemented and passing
  - Round-trip tests confirm encoding and decoding are inverses
  - Fragmentation tests confirm reassembly works for chunk sizes 1 to 70
  - Corruption tests confirm validation rejects garbage without allocation
  - Boundary tests confirm mid-stream EOF is properly propagated

✓ **Assertions quality:**
  - Each test asserts actual behavior (not just "test passes")
  - `headerLayoutIsExactlySpecified` guards on-wire format with byte-by-byte checks
  - Fragmentation test sweeps all possible split points
  - Corruption tests use `fail()` to verify exceptions are raised, not swallowed

✓ **Java 8 compliance:**
  - No `var`, no lambdas, no post-8 APIs
  - No Android imports in main source
  - Only `java.*` in both main and test

✓ **TDD adherence:**
  - Tests written first, confirmed to fail with clear compilation errors
  - Implementation added, tests confirmed to pass
  - Full suite verified before commit

## Concerns

None. All requirements met, all tests passing, commit message clear and accurate.
