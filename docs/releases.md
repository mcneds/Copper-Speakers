# Building and maintaining releases

`./gradlew releaseBundle` is the common local and CI entry point. It requires JDK 25, Python 3.11+ and `python -m pip install -r compat/bedrock/requirements.txt`. Use `gradlew.bat` on Windows and `-Ppython=/path/to/python` for a virtual environment.

The build runs Minecraft GameTests and JUnit tests, audits the Java mod/pack, downloads pinned Bedrock build inputs, compiles and tests CopperBedrock, converts the visual pack, rebuilds the patched display extension, includes its corresponding source and licenses, and audits the complete bundle. Neither an installed server nor prepared song audio is needed. Only the server-side mod and the separate Geyser compatibility files are distributed; Fabric API, Polymer, Geyser and Minecraft itself are not bundled.

## Outputs

`build/release/dist/` contains the Java mod JAR, its sources JAR, a combined `copper-speakers-<version>-bedrock-fabric.zip`, the patched display extension's source ZIP, compatibility metadata and `SHA256SUMS`. The combined ZIP has the server-relative directory layout described in [Bedrock installation](../compat/bedrock/INSTALL.md).

Archive entries use fixed timestamps/order. `compat/bedrock/dependencies.lock.json` pins external URLs and SHA-256s; changed bytes fail rather than silently updating a dependency. Some upstream URLs use a mutable release label, but the digest remains authoritative. Update that lock deliberately when the supported Geyser or display source changes. Preserve upstream notices and the display fork's corresponding source in every distribution.

The standard visual bundle uses a baseline generated Polymer pack. Servers with different allocations use `-PbedrockJavaPack=/path/to/resource_pack.zip` to build matching mappings/indices. A content fingerprint in `compatibility.json` identifies those inputs. The builder accepts an empty output directory only; choose another with `-PreleaseOutput=build/release-next` instead of overwriting an artifact.

## CI and release tags

[Build and package](../.github/workflows/build.yml) runs on pull requests, pushes to `main`, manual dispatch, and `v*` tags. Successful builds upload all release outputs as the `copper-speakers-release` artifact; test reports are uploaded even on failure. Build jobs have read-only repository access. Pinned action revisions and artifact dependency checks make updates explicit.

To prepare a release:

1. Set `mod_version` in `gradle.properties` to the public semantic version. The main mod, audio extension, both packs and bundle names derive from it.
2. Update dependency pins and compatibility documentation if needed. Rebuild and perform in-game checks on the target Java/Bedrock versions.
3. Commit the change and tag it `v<mod_version>`, for example `v0.3.0`. Push the tag.
4. CI rejects a tag/version mismatch, builds everything, and attaches the tested files to a **draft GitHub release**. Review its compatibility notes and checksums, then publish it manually.

Main-branch builds and manual dispatch do not create releases or deploy anything. There is no server credential, panel integration, automatic installation or restart step. The workflow does not publish to Modrinth or other package services.

## Component development

For explicit local inputs, `./gradlew writeReleaseInputs` exports the Minecraft build classpath, compiler path and artifact paths under `build/release-inputs/`. The lower-level Python entry point is:

```sh
python tools/build-release.py --inputs build/release-inputs/inputs.json --output build/custom-release
```

Run `./gradlew build` first so the mod and baseline pack have been tested/generated. Lower-level audio/display scripts accept dependency files directly and never search a server directory. Python tests run with `python -m unittest discover -s compat/bedrock -p 'test_*.py'`. The display source ZIP contains its own `copper-build/BUILDING.md` with exact rebuild inputs.
