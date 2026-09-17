// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Saga IT, LLC

package dev.kastle.webrtc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * libc detection, against an injected filesystem root.
 *
 * <p>musl is the one libc that makes a Linux host unshipped, so a false MUSL reading is expensive:
 * every WebRTC use on that host dies with {@code PLATFORM_NOT_SHIPPED} and a remediation that tells
 * the operator to move to a platform they are already on. The glibc loader therefore decides, and
 * the musl probe only speaks when no glibc loader exists.
 */
class PlatformTest {

	@TempDir
	Path root;

	@Test
	void aMuslOnlyRootIsMusl() throws IOException {
		musl();

		assertEquals(Platform.Libc.MUSL, linux().libc());
		assertFalse(linux().isShipped(), "musl is the one libc that makes a Linux host unshipped");
	}

	@Test
	void aLoaderInLib64IsGlibc() throws IOException {
		glibcLib64();

		assertEquals(Platform.Libc.GLIBC, linux().libc());
		assertTrue(linux().isShipped());
	}

	@Test
	void aMultiarchLoaderIsGlibc() throws IOException {
		glibcMultiarch();

		assertEquals(Platform.Libc.GLIBC, linux().libc());
		assertTrue(linux().isShipped());
	}

	@Test
	void aGlibcRootThatAlsoHasAMuslLoaderIsGlibc() throws IOException {
		// The `musl` package on Debian/Ubuntu installs /lib/ld-musl-x86_64.so.1 next to the glibc
		// loader. The presence of a musl loader is not evidence of a musl host.
		glibcLib64();
		musl();

		assertEquals(Platform.Libc.GLIBC, linux().libc());
		assertTrue(linux().isShipped());
	}

	@Test
	void aMultiarchGlibcRootThatAlsoHasAMuslLoaderIsGlibc() throws IOException {
		glibcMultiarch();
		musl();

		assertEquals(Platform.Libc.GLIBC, linux().libc());
		assertTrue(linux().isShipped());
	}

	@Test
	void aRootWithNeitherLoaderIsUnknownAndStillShipped() {
		// Unknown is not musl: an unreadable or unusual root must not cost a supported host its
		// native library.
		assertEquals(Platform.Libc.UNKNOWN, linux().libc());
		assertTrue(linux().isShipped());
	}

	@Test
	void aNonLinuxHostHasNoLibc() throws IOException {
		musl();

		assertEquals(Platform.Libc.UNKNOWN, Platform.of("Windows 11", "amd64", root).libc());
		assertEquals(Platform.Libc.UNKNOWN, Platform.of("Mac OS X", "aarch64", root).libc());
	}

	@Test
	void anUnmappedArchIsNotShippedWhateverTheLibc() throws IOException {
		glibcLib64();

		assertFalse(Platform.of("Linux", "sparcv9", root).isShipped());
	}

	private Platform linux() {
		return Platform.of("Linux", "amd64", root);
	}

	private void musl() throws IOException {
		Files.createDirectories(root.resolve("lib"));
		Files.createFile(root.resolve("lib/ld-musl-x86_64.so.1"));
	}

	private void glibcLib64() throws IOException {
		Files.createDirectories(root.resolve("lib64"));
		Files.createFile(root.resolve("lib64/ld-linux-x86-64.so.2"));
	}

	private void glibcMultiarch() throws IOException {
		Files.createDirectories(root.resolve("lib/x86_64-linux-gnu"));
		Files.createFile(root.resolve("lib/x86_64-linux-gnu/ld-linux-x86-64.so.2"));
	}
}
