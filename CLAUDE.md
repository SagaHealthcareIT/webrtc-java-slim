# CLAUDE.md — WebRTC Java Slim

Java wrapper for the WebRTC Native API — **data channels only** (no audio/video). Fork of devopvoid's webrtc-java, stripped down for P2P data exchange.

Published to Maven Central as `com.saga-it.webrtc:webrtc-java` (the Java module name remains `dev.kastle.webrtc`, retained from the upstream fork).

## Tech Stack

- **Language**: Java + C/C++ (JNI bindings)
- **Build**: Gradle (Kotlin DSL) — use `./gradlew` wrapper
- **Native build**: Requires `clang` and `cmake` on PATH
- **WebRTC version**: Configured via `webrtc.branch` in `webrtc-jni/gradle.properties` (currently `branch-heads/7922` = Chromium **M151**)

## Upstream / fork status

This fork tracks Kas-tle/webrtc-java (the `dev.kastle` slim fork of devopvoid/webrtc-java). As of the M149 bump we are **ahead of both upstreams** — Kas-tle and devopvoid were still on M140-era WebRTC and had not ported the JNI bindings to the M149 API. The binding changes for M149 live in `webrtc-jni/src/main/cpp` (notably the `IceCandidate`/candidate-removal API rework). When bumping `webrtc.branch` again, expect to re-port the bindings against upstream WebRTC API changes; check whether Kas-tle/devopvoid have caught up first.

**Milestone branches go end-of-life.** Check `curl -s 'https://chromiumdash.appspot.com/fetch_milestones?only_branched=true'` for `schedule_active` before assuming an unmoved `webrtc.branch` head is healthy — a frozen head usually means the branch stopped receiving security merge-backs, not that there was nothing to fix. The security payload of a bump is mostly the *transitive* BoringSSL revision that the branch's `DEPS` pins, which no dependency scanner reports (BoringSSL issues no CVEs).

The M149 → M151 bump (2026-07-25) was cheap on the binding side: `api/data_channel_interface.h` was byte-identical across M149/M150/M151, both required patches applied unmodified, and every `rtc_*` gn arg we pass survived. The two API breaks in range — `LOG_ERROR` → `RTC_LOG_ERROR` and `SSLAdapter::SetMode` removal — touch symbols this JNI never used. The one suspected hazard — `rtc_base/logging.h` dropping its `std::ostringstream` fallback `MakeVal` overload — turned out to be a non-issue: that fallback only ever served arbitrary *class* types, and both `RTC_LOG` call sites log types with surviving dedicated overloads (`JNI_Logging.cpp` logs a `std::string` → `kStdString`; `src/api/RTCStats.cpp` logs `stats.type()`, still `const char*` → `kCharP`). Validated by a full cold linux-x86_64 build off the M151 pin plus the whole test suite green.

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

**Built + published in CI** (the classifiers `../gateway` consumes): linux-x86_64, linux-aarch64, windows-x86_64, windows-aarch64, macos-x86_64, macos-aarch64. windows-aarch64 was dropped in `717362a` as "unused" and **restored 2026-07-07** (the OpenShare Engine ships Windows ARM installers; cross-compiled on the x64 runner, tested natively on windows-11-arm) — it first ships in the release AFTER 1.2.1, so consumers must not declare that classifier at 1.2.1.

Toolchain files for **linux-aarch32** remain in the tree, but it is **not** built/published in CI: M149's WebRTC sources require LLVM's bundled libc++ (C++20), but LLVM libc++abi doesn't provide the GNU ARM-EHABI symbol `__cxa_type_match` that 32-bit ARM exception handling needs, and the Debian-bullseye sysroot's GCC-10 libstdc++ is too old for M149 — a catch-22. Unused by the gateway.

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
