# CLAUDE.md — WebRTC Java Slim

Java wrapper for the WebRTC Native API — **data channels only** (no audio/video). Fork of devopvoid's webrtc-java, stripped down for P2P data exchange.

Published to Maven Central as `dev.kastle.webrtc:webrtc-java`.

## Tech Stack

- **Language**: Java + C/C++ (JNI bindings)
- **Build**: Gradle (Kotlin DSL) — use `./gradlew` wrapper
- **Native build**: Requires `clang` and `cmake` on PATH
- **WebRTC version**: Configured via `webrtc.branch` in `webrtc-jni/gradle.properties` (currently `branch-heads/7827` = Chromium **M149**)

## Upstream / fork status

This fork tracks Kas-tle/webrtc-java (the `dev.kastle` slim fork of devopvoid/webrtc-java). As of the M149 bump we are **ahead of both upstreams** — Kas-tle and devopvoid were still on M140-era WebRTC and had not ported the JNI bindings to the M149 API. The binding changes for M149 live in `webrtc-jni/src/main/cpp` (notably the `IceCandidate`/candidate-removal API rework). When bumping `webrtc.branch` again, expect to re-port the bindings against upstream WebRTC API changes; check whether Kas-tle/devopvoid have caught up first.

## Project Structure

```
webrtc/          # Java library (public API)
webrtc-jni/      # JNI C++ bindings + CMake native build
  src/main/cpp/dependencies/webrtc/patches/   # WebRTC source patches
```

## Commands

```bash
./gradlew build                  # Build (first build takes a long time — clones WebRTC source ~30GB)
./gradlew test                   # Run tests
./gradlew publishToMavenLocal    # Install to ~/.m2 for local gateway builds
```

## Platforms

linux-x86_64, linux-aarch32, linux-aarch64, windows-x86_64, windows-aarch64, macos-x86_64, macos-aarch64

## Cross-Project Usage

- **Consumed by**: `../gateway/` (saga-tunnel-lib uses webrtc-java for NAT traversal)
- Before building gateway from source, run `./gradlew publishToMavenLocal` here first
- Native libraries are NOT bundled with the main JAR — consumers must include platform-specific classifier artifacts

## Build Notes

- Initial build clones WebRTC source (~30GB disk space, significant compile time)
- On Windows, may need `WEBRTC_CHECKOUT_FOLDER` set to a short path (file path length limits)
- Changing `webrtc.branch` likely requires updating patches in `webrtc-jni/src/main/cpp/dependencies/webrtc/patches/`
- Patches are **required**: a patch that fails to apply now fails the build hard (CMake `FATAL_ERROR`) rather than logging a warning and continuing. A failure here almost always means a patch needs refreshing for the current `webrtc.branch` (its hunk offsets drifted).
- The build short-circuits if a compiled `libwebrtc.a` already exists in the checkout. When validating a `webrtc.branch` change locally, delete `webrtc-jni/src/main/cpp/dependencies/webrtc/webrtc-source` (and the `webrtc-jni/build` dir) first, or it will silently reuse the previously-built WebRTC version.
