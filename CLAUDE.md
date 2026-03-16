# CLAUDE.md — WebRTC Java Slim

Java wrapper for the WebRTC Native API — **data channels only** (no audio/video). Fork of devopvoid's webrtc-java, stripped down for P2P data exchange.

Published to Maven Central as `dev.kastle.webrtc:webrtc-java`.

## Tech Stack

- **Language**: Java + C/C++ (JNI bindings)
- **Build**: Gradle (Kotlin DSL) — use `./gradlew` wrapper
- **Native build**: Requires `clang` and `cmake` on PATH
- **WebRTC version**: Configured via `webrtc.branch` in `webrtc-jni/gradle.properties`

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
