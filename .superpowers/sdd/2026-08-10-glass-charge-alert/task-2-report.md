# Task 2 Report: The phone's frame reader

## What was implemented

Transcribed the brief verbatim, three new files, no existing files modified:

- `phone/src/test/java/dev/erinlkolp/glassnotify/phone/ChunkedInputStream.java` — package-private test helper mirroring `wire`'s copy; delivers at most `maxChunk` bytes per `read()` call.
- `phone/src/test/java/dev/erinlkolp/glassnotify/phone/LinkReaderTest.java` — 8 tests covering: single state delivery, in-order multi-state delivery, one-byte-fragmented stream, unknown frame types skipped without desyncing the stream, unknown protocol version stops the reader without processing what follows, truncated stream returns quietly, empty stream returns quietly, corrupt state body (out-of-range battery level, decoded via `GlassStateCodec` raising `ProtocolException`) returns quietly.
- `phone/src/main/java/dev/erinlkolp/glassnotify/phone/LinkReader.java` — `public final class LinkReader implements Runnable`, with nested `public interface Listener { void onGlassState(GlassState state); }`. Constructor takes `InputStream in, Listener listener`, null-checks both. `run()` loops `FrameCodec.read(in)`, returns silently on version mismatch, dispatches `MessageType.GLASS_STATE` frames to the listener via `GlassStateCodec.decode`, ignores all other frame types, and catches `IOException` (superclass of `ProtocolException`) around the whole loop to return without throwing. No `android.*` imports, no logging, matches all global constraints (braced `if`s, Java 8, no lambdas/Optional).

No existing files were touched: `LinkClientService`, `Protocol.VERSION` (still 1), and Task 1's wire classes were left exactly as they were.

## Test commands run and actual output

### Step 3 — confirm the test fails before `LinkReader` exists

```
./gradlew :phone:testReleaseUnitTest --tests '*LinkReaderTest*'
```
Result: **FAILED** at `:phone:compileReleaseUnitTestJavaWithJavac` — `error: package LinkReader does not exist` (4 compile errors, all stemming from the missing `LinkReader` class), exactly the expected failure mode.

### Step 5 — confirm the test passes after implementing `LinkReader`

```
./gradlew :phone:testReleaseUnitTest --tests '*LinkReaderTest*'
```
Result: **BUILD SUCCESSFUL**. Verified via
`phone/build/test-results/testReleaseUnitTest/TEST-dev.erinlkolp.glassnotify.phone.LinkReaderTest.xml`:
`tests="8" skipped="0" failures="0" errors="0"` — all 8 tests passed, matching the brief's expected count exactly.

### Full regression suite

```
./gradlew test
```
Result: **BUILD SUCCESSFUL**.

Counted one variant only (per the brief's gotcha — summing both `testDebugUnitTest` and `testReleaseUnitTest` for `glass`/`phone` would double-count):

- `wire` (single variant): **58** tests
- `glass` (`testReleaseUnitTest`): **32** tests
- `phone` (`testReleaseUnitTest`): **33** tests

All three totals match the brief's expected post-task counts (wire 58, glass 32, phone 33) exactly. A search across all result XML files for `failures="[1-9]` or `errors="[1-9]` found none — zero failures anywhere in the suite.

## Commit

SHA: `9895425762c87c4bd93cc5beb84ec88a2bc30647`
Message:
```
feat(phone): read the reverse channel

LinkReader consumes Glass -> phone frames and hands GLASS_STATE to a
listener. Nothing is wired to it yet.

It is forbidden to touch anything but the input stream, and it is silent
by design: no android.util.Log, so the whole reader - including a stream
fragmented one byte at a time - is testable on the JVM.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```
Files in commit: exactly the three listed above (`git status` after commit shows no other tracked changes; only a pre-existing untracked `data/` directory remains, unrelated to this task).

## Surprises

- None functionally. It's worth noting explicitly for the record: `FrameCodec.read()` uses `DataInputStream.readInt()`/`readFully()`, both of which throw `EOFException` (an `IOException`) on a stream that ends early or is empty — this is exactly what makes `returnsQuietlyOnATruncatedStream` and `returnsQuietlyOnAnEmptyStream` pass without any special-casing in `LinkReader`; the existing `catch (IOException e)` covers it uniformly alongside `ProtocolException`.

## Deviations from the brief

None. Every file was transcribed verbatim, including the hand-assembled byte array in `stopsOnAnUnknownProtocolVersion` (`{0, 0, 0, 4, 99, (byte) MessageType.GLASS_STATE, 100, 1}`) and the corrupt-body byte array in `returnsQuietlyOnACorruptStateBody` (`{0, 0, 0, 4, 1, (byte) MessageType.GLASS_STATE, (byte) 200, 1}`). No renaming, reformatting, or additions were made. `LinkClientService` was not touched, per the constraint that wiring happens in Task 4.
