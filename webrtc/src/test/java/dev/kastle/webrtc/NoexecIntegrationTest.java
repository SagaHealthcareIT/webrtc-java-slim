// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Saga IT, LLC

package dev.kastle.webrtc;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real thing: the actual shipped {@code .so}, on an actual {@code noexec} mount.
 *
 * <p>This is the only test that proves the {@code NOEXEC_OR_SELINUX} path end to end, and the shape
 * of the customer failure that started this work. It needs the native on the test classpath and
 * unprivileged user namespaces ({@code unshare -Urm}), and self-skips when either is missing.
 *
 * <p>Neither skip is about {@code -PprebuiltJniPath}: with that property the build puts the jar it
 * names on the test runtime classpath, without it the freshly built {@code :webrtc-jni} project goes
 * there instead ({@code webrtc/build.gradle.kts}), and CI always passes it
 * ({@code .github/actions/test/action.yml}). The classpath skip therefore fires only when the
 * property points at a file that does not exist; the skip that really fires on a runner is the
 * user-namespace one.
 */
@EnabledOnOs(OS.LINUX)
class NoexecIntegrationTest {

	@TempDir
	Path tmp;

	@Test
	void aNoexecMountIsClassifiedAndItsRemediationNamesFindmnt() throws Exception {
		assumeTrue(NativeTestSupport.nativeOnClasspath(),
				"the prebuilt native is not on the test classpath");
		assumeTrue(unshareAvailable(), "unprivileged user namespaces are not available here");

		Path library = extractNative();
		Path mountPoint = Files.createDirectories(tmp.resolve("noexec"));

		List<String> output = runProbeUnderNoexec(mountPoint, library);

		assumeTrue(output.stream().noneMatch(line -> line.startsWith("SKIP=")),
				"could not build a noexec tmpfs: " + output);

		assertTrue(output.contains("FAULT=" + NativeFault.NOEXEC_OR_SELINUX),
				() -> "expected a NOEXEC_OR_SELINUX classification, got " + output);
		assertTrue(output.stream().anyMatch(line -> line.startsWith("REMEDIATION=")
				&& line.contains("findmnt -T ")),
				() -> "the remediation must name findmnt, got " + output);
	}

	private List<String> runProbeUnderNoexec(Path mountPoint, Path library) throws Exception {
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		String classpath = System.getProperty("java.class.path");
		String target = mountPoint.resolve("libwebrtc-java.so").toString();

		String script = "mount -t tmpfs -o noexec,size=64m tmpfs '" + mountPoint + "'"
				+ " || { echo SKIP=mount; exit 0; }; "
				+ "cp '" + library + "' '" + target + "' || { echo SKIP=copy; exit 0; }; "
				+ "exec '" + java + "' -cp '" + classpath + "' "
				+ NoexecProbe.class.getName() + " '" + target + "'";

		ProcessBuilder builder = new ProcessBuilder("unshare", "-Urm", "/bin/sh", "-c", script);

		builder.redirectErrorStream(false);

		Process process = builder.start();
		List<String> lines;

		try (InputStream in = process.getInputStream()) {
			lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
		}

		assumeTrue(process.waitFor(2, TimeUnit.MINUTES), "the probe did not finish");

		return lines;
	}

	private Path extractNative() throws IOException {
		Path library = tmp.resolve("source/libwebrtc-java.so");

		Files.createDirectories(library.getParent());

		try (InputStream in = NativeTestSupport.class.getClassLoader()
				.getResourceAsStream(NativeTestSupport.resourceName())) {
			Files.copy(in, library);
		}

		return library;
	}

	private static boolean unshareAvailable() {
		try {
			Process process = new ProcessBuilder("unshare", "-Urm", "/bin/true")
					.redirectErrorStream(true)
					.start();

			if (!process.waitFor(30, TimeUnit.SECONDS)) {
				process.destroyForcibly();

				return false;
			}

			return process.exitValue() == 0;
		}
		catch (IOException | InterruptedException e) {
			return false;
		}
	}
}
