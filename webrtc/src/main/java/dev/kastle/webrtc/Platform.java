// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Saga IT, LLC

package dev.kastle.webrtc;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The os/arch/libc triple this JVM runs on, as the native library names it.
 *
 * <p>The {@code os.name} and {@code os.arch} mapping tables are lifted from the deleted
 * {@code internal.NativeLoader}: {@code macos|linux|windows} x {@code x86_64|aarch32|aarch64}.
 * They no longer throw on an unmapped value — an unmapped value is a
 * {@link NativeFault#PLATFORM_NOT_SHIPPED} classification, not a crash.
 *
 * <p>Every filesystem probe goes through {@link #fsRoot()} so the libc detection can be unit-tested
 * against an injected root.
 */
public final class Platform {

	/** The C library this host uses. Only {@link #MUSL} makes a host unshipped. */
	public enum Libc {
		GLIBC, MUSL, UNKNOWN
	}

	/** The six classifiers CI builds and publishes. */
	public static final List<String> SHIPPED_CLASSIFIERS = List.of(
			"linux-x86_64", "linux-aarch64",
			"windows-x86_64", "windows-aarch64",
			"macos-x86_64", "macos-aarch64");

	private final String rawOsName;
	private final String rawOsArch;
	private final Path fsRoot;

	private Libc libc;

	private Platform(String rawOsName, String rawOsArch, Path fsRoot) {
		this.rawOsName = rawOsName == null ? "" : rawOsName;
		this.rawOsArch = rawOsArch == null ? "" : rawOsArch;
		this.fsRoot = fsRoot;
	}

	/** This JVM's platform. Reads the system properties live; holds no static cache. */
	public static Platform current() {
		return new Platform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""),
				Paths.get("/"));
	}

	/** Test seam: an arbitrary platform, with filesystem probes rooted at {@code fsRoot}. */
	static Platform of(String osName, String osArch, Path fsRoot) {
		return new Platform(osName, osArch, fsRoot);
	}

	public String rawOsName() {
		return rawOsName;
	}

	public String rawOsArch() {
		return rawOsArch;
	}

	/** Root every filesystem probe resolves against; {@code /} outside tests. */
	public Path fsRoot() {
		return fsRoot;
	}

	/** {@code macos}, {@code linux} or {@code windows}; empty when the OS is one we do not map. */
	public Optional<String> osFamily() {
		String osName = rawOsName.toLowerCase(Locale.ROOT);

		if (osName.startsWith("mac os")) {
			return Optional.of("macos");
		}
		if (osName.startsWith("linux")) {
			return Optional.of("linux");
		}
		if (osName.startsWith("windows")) {
			return Optional.of("windows");
		}

		return Optional.empty();
	}

	/** {@code x86_64}, {@code aarch32} or {@code aarch64}; empty when the arch is one we do not map. */
	public Optional<String> osArch() {
		switch (rawOsArch.toLowerCase(Locale.ROOT)) {
			case "x86_64":
			case "x86-64":
			case "amd64":
				return Optional.of("x86_64");
			case "aarch32":
			case "arm":
				return Optional.of("aarch32");
			case "aarch64":
			case "arm64":
				return Optional.of("aarch64");
			default:
				return Optional.empty();
		}
	}

	/** {@code <os>-<arch>}, e.g. {@code linux-x86_64}; empty when either half is unmapped. */
	public Optional<String> classifier() {
		Optional<String> family = osFamily();
		Optional<String> arch = osArch();

		if (family.isEmpty() || arch.isEmpty()) {
			return Optional.empty();
		}

		return Optional.of(family.get() + "-" + arch.get());
	}

	/**
	 * The classifier when we can name it, otherwise the raw property values — for log lines and
	 * operator-facing text, which must say something useful even on a platform we do not map.
	 */
	public String describe() {
		return classifier().orElseGet(() -> rawOsName + "/" + rawOsArch);
	}

	public boolean isLinux() {
		return "linux".equals(osFamily().orElse(null));
	}

	public boolean isWindows() {
		return "windows".equals(osFamily().orElse(null));
	}

	public boolean isMacOs() {
		return "macos".equals(osFamily().orElse(null));
	}

	/**
	 * The C library, detected from the dynamic loaders present under {@link #fsRoot()}: a host is
	 * glibc when {@code /lib/ld-linux-*}, {@code /lib64/ld-linux-*} or
	 * {@code /lib/<triple>-linux-gnu*}{@code /ld-linux-*} exists, and musl only when none of those
	 * do <em>and</em> {@code /lib/ld-musl-*} does. A musl loader is not on its own evidence of a musl
	 * host: Debian and Ubuntu ship one in the {@code musl} package. Neither loader present is
	 * {@link Libc#UNKNOWN}, which is shipped; only {@link Libc#MUSL} is not. Non-Linux hosts are
	 * {@link Libc#UNKNOWN}. Computed once per instance.
	 */
	public synchronized Libc libc() {
		if (libc == null) {
			libc = detectLibc();
		}

		return libc;
	}

	/** True when CI publishes a native for this platform and the libc can run it. */
	public boolean isShipped() {
		Optional<String> classifier = classifier();

		if (classifier.isEmpty() || !SHIPPED_CLASSIFIERS.contains(classifier.get())) {
			return false;
		}

		return !(isLinux() && libc() == Libc.MUSL);
	}

	/** The shared-library extension for this platform, including the dot. */
	public String libraryExtension() {
		if (isWindows()) {
			return ".dll";
		}
		if (isMacOs()) {
			return ".dylib";
		}

		return ".so";
	}

	private Libc detectLibc() {
		if (!isLinux()) {
			return Libc.UNKNOWN;
		}

		// The glibc loader decides, and is probed first. A musl loader on its own is a musl host; a
		// musl loader beside a glibc one is a glibc host with the `musl` package installed, which
		// Debian and Ubuntu both ship. Reading MUSL there would cost that host every WebRTC feature
		// and hand its operator a remediation naming their own platform as a supported one.
		if (hasGlibcLoader()) {
			return Libc.GLIBC;
		}
		if (globExists("lib", "ld-musl-*")) {
			return Libc.MUSL;
		}

		return Libc.UNKNOWN;
	}

	private boolean hasGlibcLoader() {
		if (globExists("lib", "ld-linux-*") || globExists("lib64", "ld-linux-*")) {
			return true;
		}

		// Multiarch layouts (Debian/Ubuntu) keep the loader one level deeper.
		try (DirectoryStream<Path> dirs = Files.newDirectoryStream(fsRoot.resolve("lib"), "*-linux-gnu*")) {
			for (Path dir : dirs) {
				try (DirectoryStream<Path> loaders = Files.newDirectoryStream(dir, "ld-linux-*")) {
					if (loaders.iterator().hasNext()) {
						return true;
					}
				}
			}
		}
		catch (IOException | RuntimeException e) {
			return false;
		}

		return false;
	}

	private boolean globExists(String dir, String glob) {
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(fsRoot.resolve(dir), glob)) {
			return stream.iterator().hasNext();
		}
		catch (IOException | RuntimeException e) {
			return false;
		}
	}

	@Override
	public String toString() {
		return "Platform[" + describe() + " libc=" + libc() + "]";
	}
}
