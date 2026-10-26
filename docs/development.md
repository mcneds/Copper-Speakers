# Development

Use a Java 25 JDK. The wrapper pins Gradle 9.6.1, Loom 1.16.3, Fabric Loader 0.19.5, Fabric API 0.163.0+26.3 and Polymer 0.18.4+26.3. Polymer is an external dependency, not bundled in the release jar.

```powershell
./gradlew.bat build prepareRuns
./tools/install-runtime.ps1
```

`build` runs JUnit graph tests and Fabric server GameTests. `prepareRuns` creates argument files for the scripted launches. The optional runtime installer downloads a pinned Azul Java runtime and checks its SHA-256; compilation still uses the installed JDK. Local worlds, caches, downloaded runtimes and launch logs are ignored by Git.

## Running locally

```powershell
python tools/run.py server --name server-01
python tools/run.py client --name client-01
# Start this with the dedicated local server running:
python tools/run.py vanilla --name vanilla-01
# Exercise the release jar instead of development classes:
python tools/run.py server --packaged --name packaged-server-01
python tools/rcon.py stop
```

Attempt names must be new. The development server binds to `127.0.0.1:28901`, with local RCON on port `28902`; its offline authentication is only for that isolated fixture. Each attempt records its launch arguments, logs and outcome under `build/validation/`.

Open the project as a Gradle project in IntelliJ. Shared `.run/` configurations launch the client, server and tests. Run `prepareRuns` after code or dependency changes. These configurations have no implicit build step: stop development Minecraft processes before recompiling. Scripted launches maintain PID markers to prevent active classes and resources from being overwritten.

Optional `-PperformanceMods` adds the pinned Sodium and Lithium dependencies to client launches. They are not production requirements.

## Client verification

```powershell
./gradlew.bat prepareRuns
python tools/run.py smoke --name buffered-01
python tools/run.py custom --name custom-buffered-01 --diagnostic
python tools/run.py generic --name custom-streamed-01 --diagnostic
python tools/run.py late --name late-01
python tools/run.py distance --name distance-01 --diagnostic
python tools/run.py wire --name wire-01 --diagnostic
python tools/run.py plate --name plate-01 --diagnostic
python tools/run.py notes --name notes-01 --diagnostic
```

The smoke test observes actual insertion packets and the unchanged client audio engine. It checks buffered sound selection, the jukebox, speakers and four reserved voices sharing one decoded buffer, natural completion and explicit stops. Its clock-paired OpenAL observations include a deliberate 50 ms control.

The generic custom-disc test uses a streamed song in an unrelated namespace, with a sound event named differently from the song and no buffered alias. It checks original and spare sources, late speaker attachment, disconnection, reconnection and stopping. Streaming queue offsets are not whole-track clocks, so this test makes no synchronization measurement claim.

The late-join test checks native source continuity and shared buffers. Distance, wire, plate and note tests cover loaded-network range and client visuals. All observation helpers and synthetic audio live in `src/gametest` and are excluded from the distributable.

`--diagnostic` limits JIT compilation and processor count to work around a documented native JVM startup problem on the development machine. Use diagnostic runs for functionality only; they are rejected for the `smoke` and `sections` timing modes. In `tails` mode, diagnostic runs omit all timing observations and report only continuity assertions. Never count failed or incomplete launches as passing tests.

## Audio benchmarks

The experimental [late-listener section probe](benchmarks/late-join-feasibility.md) measures boundary timing. The [prepared-tail probe](benchmarks/late-join-tails.md) investigates continuous cropped tracks for arriving listeners. Neither is a production late-joining implementation.

```powershell
./gradlew.bat build prepareRuns
python tools/benchmark.py --name my-benchmark --rounds 10 --sources 3 8 16 --sample-ticks 40
python -m pip install matplotlib==3.10.9
python tools/plot-benchmarks.py --suite build/validation/my-benchmark/suite.json
# Regenerate the figures from the committed data instead:
python tools/plot-benchmarks.py
```

The suite runs each configuration sequentially with a 2 GiB maximum heap and normal JIT settings. Cold starts explicitly clear the decoded audio cache; each is followed by a cached replay. The observer records start spread, a second observation after 40 client ticks, native PCM buffer sizes and the wall time until every voice is observed playing. The suite records every launch, including failed attempts, and fingerprints the build used. Use a new name for each suite. See [Benchmark results](benchmarks/README.md) for measurement definitions and limits.

## Resources and artifacts

Polymer generates the combined pack from registered mod assets. `/polymer generate` rebuilds it after asset changes. The mod supplies models, textures, note effects and buffered sound definitions, but no music files.

```powershell
python tools/generate-assets.py --minecraft-jar <26.3-client-jar>
./tools/prepare-speaker-texture.ps1
python tools/generate-icon.py --minecraft-jar <26.3-client-jar>
python tools/check-artifacts.py build/libs/copper-speakers-0.1.9.jar build/run/gameTest/polymer/resource_pack.zip
```

The icon generator requires Pillow. Models reference Minecraft's existing textures; the speaker front is an original hand-authored pixel texture. The artifact audit verifies buffered metadata, expected assets, and exclusion of music and test classes from production.

## Visual and lifecycle details

Wall plates use Polymer virtual item frames owned by one saved server entity in the host block. Both faces persist and unload together. Entity lifecycle events maintain a host-position index; block changes validate nearby plates before audio reconciliation.

Dry bare wire uses a pressed weighted pressure plate carrier; dry insulated wire uses flat tripwire. These preserve low client targeting areas without solid collision. Waterlogged wire uses kelp to retain fluid rendering and has a taller client outline. Model geometry cannot replace the vanilla carrier's targeting shape.

Wire risers, plate leads and sideways music notes use vanilla display entities. Editor overlays such as Axiom's Show Display Entities can expose their origin markers; those markers are not cable geometry. Music-note displays reference the vanilla note sprite and are independent of the client's particle-density setting.

See [Audio compatibility](audio.md) for session behavior, optional custom-song buffering and client limits, and [Benchmark results](benchmarks/README.md) for published measurements. Local worlds, screenshots, deployment receipts and historical logs stay in ignored development directories.
