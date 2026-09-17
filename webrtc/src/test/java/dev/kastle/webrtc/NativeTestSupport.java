// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Saga IT, LLC

package dev.kastle.webrtc;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.Assumptions;

/**
 * The test host's {@link NativeSource}.
 *
 * <p>There is no default source in the library — an unconfigured {@code NativeLibrary} fails with
 * {@code NOT_CONFIGURED} — so every test that needs the real native configures one explicitly. This
 * one is a tier-3 host: the native arrives as a classpath resource from the
 * {@code webrtc-java-<platform>} jar and is staged into {@code build/test-natives}, never into
 * {@code java.io.tmpdir}.
 */
public final class NativeTestSupport {

	/** Where the test host stages its native. Deliberately inside the build directory. */
	public static final Path STAGING_DIR = Path.of("build", "test-natives");

	private NativeTestSupport() {
	}

	/** The platform-specific resource name, e.g. {@code libwebrtc-java-linux-x86_64.so}. */
	public static String resourceName() {
		Platform platform = Platform.current();

		return System.mapLibraryName(NativeLibrary.LIBRARY_NAME + "-"
				+ platform.classifier().orElse("unknown"));
	}

	/**
	 * Aborts the calling test when this machine sets the tier-1 operator override.
	 *
	 * <p>{@link NativeLibrary} honours {@code -Dopenshare.webrtc.native} / {@code OPENSHARE_WEBRTC_NATIVE}
	 * for every host, so a developer who has that variable exported would otherwise see the resolver
	 * tests resolve to their file instead of the one under test. The property form is cleared by the
	 * tests that set it; the environment form cannot be unset from inside a JVM.
	 */
	public static void assumeNoOperatorOverride() {
		Assumptions.assumeTrue(System.getenv(NativeLibrary.OVERRIDE_ENVIRONMENT) == null,
				NativeLibrary.OVERRIDE_ENVIRONMENT + " is set on this machine");
	}

	/** True when the prebuilt native is on the test classpath. */
	public static boolean nativeOnClasspath() {
		try (InputStream in = open()) {
			return in != null;
		}
		catch (IOException e) {
			return false;
		}
	}

	/** A tier-3 source reading the native off the test classpath. */
	public static NativeSource classpathSource() {
		return new NativeSource() {

			@Override
			public Optional<Path> overridePath() {
				return Optional.empty();
			}

			@Override
			public Optional<Path> installedFile() {
				return Optional.empty();
			}

			@Override
			public Optional<String> installedSha256() {
				return Optional.empty();
			}

			@Override
			public Optional<Supplier<InputStream>> jarResource() {
				return Optional.of(NativeTestSupport::open);
			}

			@Override
			public Optional<Path> stagingDir() {
				return Optional.of(STAGING_DIR.toAbsolutePath());
			}

			@Override
			public String version() {
				return "test";
			}
		};
	}

	/**
	 * Configures the test source and loads the native. Tests share one JVM, so this first clears
	 * whatever a resolver test left memoised; a repeat load of an already-loaded path is a no-op in
	 * the JVM and never re-runs {@code JNI_OnLoad}.
	 */
	public static void configureAndLoad() {
		NativeLibrary.resetForTests();
		NativeLibrary.configure(classpathSource());
		NativeLibrary.ensureLoaded();
	}

	/** The real native, staged on disk. Only valid after {@link #configureAndLoad()}. */
	public static Path loadedNative() {
		return NativeLibrary.status()
				.flatMap(LoadResult::path)
				.orElseThrow(() -> new IllegalStateException("native not loaded"));
	}

	/** The checked-in 512-byte head of one of the six shipped natives. */
	public static byte[] headerFixture(String classifier) {
		try (InputStream in = NativeTestSupport.class.getResourceAsStream("/headers/" + classifier + ".head")) {
			if (in == null) {
				throw new IllegalArgumentException("no header fixture for " + classifier);
			}

			return in.readAllBytes();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Writes a header fixture to a real file, so structural checks have something to read. */
	public static Path writeHeaderFixture(Path target, String classifier) throws IOException {
		Files.createDirectories(target.getParent());
		Files.write(target, headerFixture(classifier));

		return target;
	}

	private static InputStream open() {
		return NativeTestSupport.class.getClassLoader().getResourceAsStream(resourceName());
	}
}
