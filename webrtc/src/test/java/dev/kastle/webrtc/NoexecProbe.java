// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Saga IT, LLC

package dev.kastle.webrtc;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Loads one native library file and prints the classification, one line, on stdout.
 *
 * <p>Run as a child JVM by {@link NoexecIntegrationTest} inside a mount namespace whose tmpfs is
 * mounted {@code noexec}. A namespace cannot be imposed on the JVM that creates it, so the probe has
 * to be a separate process.
 */
public final class NoexecProbe {

	private NoexecProbe() {
	}

	public static void main(String[] args) {
		Path library = Path.of(args[0]).toAbsolutePath();

		NativeLibrary.configure(new NativeSource() {

			@Override
			public Optional<Path> overridePath() {
				return Optional.empty();
			}

			@Override
			public Optional<Path> installedFile() {
				return Optional.of(library);
			}

			@Override
			public Optional<String> installedSha256() {
				return Optional.empty();
			}

			@Override
			public Optional<java.util.function.Supplier<java.io.InputStream>> jarResource() {
				return Optional.empty();
			}

			@Override
			public Optional<Path> stagingDir() {
				return Optional.empty();
			}

			@Override
			public String version() {
				return "probe";
			}
		});

		try {
			NativeLibrary.ensureLoaded();

			System.out.println("FAULT=NONE");
		}
		catch (NativeUnavailableException e) {
			System.out.println("FAULT=" + e.result().fault().orElseThrow());
			System.out.println("REMEDIATION=" + e.result().remediation());
		}

		// The library may hold non-daemon threads; the probe has said what it came to say.
		Runtime.getRuntime().halt(0);
	}
}
