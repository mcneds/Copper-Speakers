# Per-song prepared joining validation

Validated locally on 28 October 2026. No live deployment or live-server restart was performed.

The release is `copper-speakers-0.2.1+mineandcraft.3.jar`, SHA-256 `7c0784bc6dcf14b0387a7e522191170431fa1555b7d9edfd2a51b1cf51116f7b`. It includes the existing socket-face and standalone-jukebox patches. Existing README installation edits and unrelated wiki changes were preserved.

| Check | Result |
| --- | --- |
| `gradlew build prepareRuns` | Passed: 48 required Minecraft GameTests and 15 JUnit tests |
| Preparation lifecycle tests | Background single-song work, stale completions, spacing changes, source replacement, failures/retries, disable, deletion leases, restart recovery, partial-output cleanup, invalid settings |
| Countdown test | Loading refresh, one-second countdown changes, immediate correction to a rescheduled deadline |
| Native JOrbis verification | All 13 Cat entry points passed exact decoded-frame and waveform checks at default 15-second spacing |
| Artifact audit | Passed: 22 built-in buffered aliases, no private audio or production test classes |
| Bedrock extension | Compiled against local Geyser-Fabric 2.12.1; protocol, coordinated-audio and runtime-event tests passed with an empty embedded event table |
| Two separate Java clients and packaged mod | `build/validation/live-joining-03/result.json`: complete |

The two-client run starts a disposable loopback server without a prepared catalogue. While the first client plays ordinary Cat audio, the controller prepares that sound in the background. The client retains its original native source, buffer, and start time. The next insertion uses prepared audio; a later connection receives the appropriate suffix. Both clients observe speaker attachment, and existing sources continue through disabling. A returning listener receives no new admission while disabled. Deletion remains pending while the original listener plays and completes after ejection. The main pack's SHA-256 remains unchanged throughout these operations.

The late client's observed action bar includes `Loading music…`, countdown values from 17 seconds down to 1 second (including `Joining music in 12s…`), and `C418 - cat` at playback start. The packaged run uses diagnostic client JVM settings for functionality checks; its alignment observations are not a performance benchmark or an Internet synchronization guarantee.

Earlier attempts remain recorded under `build/validation/live-joining-01` and `live-joining-02`. Their final assertions failed because the harness expected an uppercase Cat title and read UTF-8 reports with Windows' default code page, respectively. Those harness issues were corrected. During review, joining-pack delivery was moved from login configuration to the play connection so loading progress is visible to late players; the final run explicitly checks that behavior.

The Bedrock checks exercise event decoding, live mapping replacement and failure recovery, sound handles, playback offsets, and the existing coordination tests. Physical Bedrock client listening/timing with this new release has not been verified. The original Bedrock audio must already be published in the ordinary music pack. See the [wiki bridge contract](wiki-music-bridge.md) for activation, deletion ownership, and protocol limits.
