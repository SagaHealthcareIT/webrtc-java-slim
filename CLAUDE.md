# CLAUDE.md — WebRTC Java Slim

Java wrapper for the WebRTC Native API — **data channels only** (no audio/video). Fork of devopvoid's webrtc-java, stripped down for P2P data exchange.

Published to Maven Central as `com.saga-it.webrtc:webrtc-java` (the Java module name remains `dev.kastle.webrtc`, retained from the upstream fork).

## Tech Stack

- **Language**: Java + C/C++ (JNI bindings)
- **Build**: Gradle (Kotlin DSL) — use `./gradlew` wrapper
- **Native build**: Requires `clang` and `cmake` on PATH
- **WebRTC version**: Configured via `webrtc.branch` in `webrtc-jni/gradle.properties` (currently `branch-heads/8127` = Chromium **M158**)

## Upstream / fork status

This fork tracks Kas-tle/webrtc-java (the `dev.kastle` slim fork of devopvoid/webrtc-java). As of the M149 bump we are **ahead of both upstreams** — Kas-tle and devopvoid were still on M140-era WebRTC and had not ported the JNI bindings to the M149 API. The binding changes for M149 live in `webrtc-jni/src/main/cpp` (notably the `IceCandidate`/candidate-removal API rework). When bumping `webrtc.branch` again, expect to re-port the bindings against upstream WebRTC API changes; check whether Kas-tle/devopvoid have caught up first.

**Milestone branches go end-of-life.** Check `curl -s 'https://chromiumdash.appspot.com/fetch_milestones?only_branched=true'` for `schedule_active` before assuming an unmoved `webrtc.branch` head is healthy — a frozen head usually means the branch stopped receiving security merge-backs, not that there was nothing to fix. The security payload of a bump is mostly the *transitive* BoringSSL revision that the branch's `DEPS` pins, which no dependency scanner reports (BoringSSL issues no CVEs).

The M149 → M151 bump (2026-07-25) was cheap on the binding side: `api/data_channel_interface.h` was byte-identical across M149/M150/M151, both required patches applied unmodified, and every `rtc_*` gn arg we pass survived. The two API breaks in range — `LOG_ERROR` → `RTC_LOG_ERROR` and `SSLAdapter::SetMode` removal — touch symbols this JNI never used. The one suspected hazard — `rtc_base/logging.h` dropping its `std::ostringstream` fallback `MakeVal` overload — turned out to be a non-issue: that fallback only ever served arbitrary *class* types, and both `RTC_LOG` call sites log types with surviving dedicated overloads (`JNI_Logging.cpp` logs a `std::string` → `kStdString`; `src/api/RTCStats.cpp` logs `stats.type()`, still `const char*` → `kCharP`). Validated by a full cold linux-x86_64 build off the M151 pin plus the whole test suite green.

The M151 → M158 bump (2026-11-03) needed two binding changes and one patch refresh. M151's branch head never moved after its 2026-06-29 cut, so it never got a security merge-back; the BoringSSL pin moves `be08ef39` (06-27) → `af41e135` (10-21). Upstreams at the time: devopvoid on M155 (`branch-heads/8059`), Kas-tle unchanged since 2026-03.
- **Worker thread removed** (`WEBRTC_REMOVED_WORKER_THREAD`): the network thread is also the worker thread, and `PeerConnectionFactoryDependencies` has no `worker_thread`. `JNI_PeerConnectionFactory.cpp` no longer creates one and `PeerConnectionFactory.java` lost `workerThreadHandle`.
- **`CopyOnWriteBuffer(const T*, size_t)` removed**: `JNI_RTCDataChannel.cpp` uses the `std::span<const uint8_t>` constructor, which still copies (the byte-array path releases the array with `JNI_ABORT` right after, so it must).
- **`use_system_ssl_root_certs.patch`**: one context line changed (`openssl::SSLInfoCallback`); all three copies refreshed, CRLF kept.
- **Left as a warning:** `LogMessage::LogThreads` is deprecated in favour of the one-shot `InitializeLogging(LoggingConfig)`. Porting it means redesigning the Java `Logging` toggles; nothing in gateway, the plugin or the launcher calls `Logging.logThreads`. Expect removal in a later milestone.
- `libwebrtc.a` now compiles dav1d even with `rtc_include_dav1d_in_internal_decoder_factory=false`, but nothing references it, so it is not linked (the linux-x86_64 `.so` is 8.2 MB vs 7.9 MB at 1.3.0).
- **CI toolchain** (the first six-platform CI run failed everywhere; a local Fedora build hid all three):
  - **Linux:** `.github/actions/prepare-linux` pins the Chromium clang the JNI compiles with. It must match the pinned WebRTC's `tools/clang/scripts/update.py` (`CLANG_REVISION`-`CLANG_SUB_REVISION`), because the JNI compiles against that checkout's bundled libc++. M158's libc++ headers rejected the old `20-init` pin; it is now `llvmorg-24-init-14453-ge063be79-1`. Re-pin (and re-hash) on every bump.
  - **All platforms, `libyuv_jpeg_follows_disable_jpeg.patch`:** M158's `common_video/BUILD.gn` defines `HAVE_LIBYUV_JPEG` on every non-iOS target, so `ConvertToI420` references `MJPGToI420` even though we build libyuv with `libyuv_disable_jpeg=true`. The patch ties the define to that arg. Linux never linked the dangling reference; macOS's `-ObjC` pulled `webrtc_libyuv.o` in and the link failed.
  - **Windows SDK:** M158 hard-codes Windows SDK 10.0.28000 in `build/vs_toolchain.py` and `build/toolchain/win/setup_toolchain.py` (no gn override; `build/config/win/BUILD.gn` also moved to `NTDDI_WIN11_BR`, an SDK-28000 constant), and no GitHub Windows image ships it. `.github/actions/prepare-windows` installs SDK 10.0.28000.2270 (the build M158's `vs_toolchain.py` names) from a pinned `download.microsoft.com` URL, checking SHA-256 and the Microsoft Authenticode signature. A patch pinning back to 10.0.26100 was tried and does not work: windows-x86_64 built, but on windows-aarch64 the 26100 UCRT `wchar.h` includes clang's `arm_neon.h` in C linkage and M158's clang rejects its FP8 builtins there. Drop the install step once the runner images ship 10.0.28000.

Validated by a cold linux-x86_64 build off the M158 pin (3635 ninja steps) with all 87 tests green. `RTCDataChannelTests.binaryDirectBufferWindow` is new with this bump: before it, nothing in the suite sent through the direct-buffer path, which gateway's `DataChannelStream` uses for bulk tunnel data. It checks the position/limit window byte for byte and that the bytes are copied at send time (it fails if the native side ignores `position`).

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
