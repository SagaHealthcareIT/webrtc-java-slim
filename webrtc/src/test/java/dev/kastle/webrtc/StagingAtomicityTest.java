// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Saga IT, LLC

package dev.kastle.webrtc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kastle.webrtc.internal.Sha256;
import dev.kastle.webrtc.internal.Staging;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The tier-3 write protocol.
 *
 * <p>The rule that matters is that an existing staged file is never opened for write: truncating a
 * mapped {@code .so} kills the JVM with {@code SIGBUS} in the dynamic loader, which is exactly what
 * the {@code Files.copy(..., REPLACE_EXISTING)} this protocol replaces did. The filename is the
 * content hash, so "same name" and "same bytes" are the same statement.
 */
class StagingAtomicityTest {

	private static final byte[] BYTES = "a native library, for the purposes of this test"
			.getBytes(java.nio.charset.StandardCharsets.UTF_8);

	@TempDir
	Path staging;

	@Test
	void stagesUnderAContentKeyedNameAndLeavesNoTemporaryFile() throws Exception {
		AtomicInteger loads = new AtomicInteger();

		Staging.Staged staged = Staging.stage(staging, "webrtc-java-1.3.0-linux-x86_64", ".so",
				() -> new ByteArrayInputStream(BYTES), path -> loads.incrementAndGet());

		String sha = Sha256.of(new ByteArrayInputStream(BYTES));

		assertEquals("webrtc-java-1.3.0-linux-x86_64-" + Sha256.short12(sha) + ".so",
				staged.path().getFileName().toString());
		assertEquals(sha, staged.sha256());
		assertFalse(staged.reused());
		assertEquals(1, loads.get());
		assertArrayEquals(BYTES, Files.readAllBytes(staged.path()));
		assertEquals(List.of(), temporaries());
	}

	@Test
	void aSecondCallWithTheSameBytesReusesWithoutWriting() throws Exception {
		Staging.Staged first = Staging.stage(staging, "webrtc-java-1.3.0-linux-x86_64", ".so",
				() -> new ByteArrayInputStream(BYTES), path -> {
				});

		// A read-only target proves the point structurally: a write of any kind would fail here.
		Path target = first.path();

		assertTrue(target.toFile().setWritable(false));

		try {
			FileTime before = Files.getLastModifiedTime(target);

			Staging.Staged second = Staging.stage(staging, "webrtc-java-1.3.0-linux-x86_64", ".so",
					() -> new ByteArrayInputStream(BYTES), path -> {
					});

			assertEquals(target, second.path());
			assertTrue(second.reused());
			assertEquals(before, Files.getLastModifiedTime(target));
			assertEquals(List.of(), temporaries());
		}
		finally {
			// A read-only file defeats @TempDir cleanup on Windows.
			target.toFile().setWritable(true);
		}
	}

	@Test
	void aFailedLoadDeletesTheStagedFile() {
		Staging.StagedLoadException failure = assertThrows(Staging.StagedLoadException.class,
				() -> Staging.stage(staging, "webrtc-java-1.3.0-linux-x86_64", ".so",
						() -> new ByteArrayInputStream(BYTES), path -> {
							throw new UnsatisfiedLinkError("failed to map segment from shared object");
						}));

		assertFalse(Files.exists(failure.path()),
				"a noexec volume must not accumulate an unusable artefact");
		assertEquals(List.of(), temporaries());
	}

	@Test
	void aMissingResourceIsAnIoErrorNotANullPointer() {
		IOException e = assertThrows(IOException.class,
				() -> Staging.stage(staging, "webrtc-java-1.3.0-linux-x86_64", ".so",
						() -> (InputStream) null, path -> {
						}));

		assertTrue(e.getMessage().contains("classpath"), e.getMessage());
	}

	@Test
	void theStagingDirectoryIsCreatedWhenAbsent() throws Exception {
		Path nested = staging.resolve("native/deeper");

		Staging.Staged staged = Staging.stage(nested, "webrtc-java-1.3.0-linux-x86_64", ".so",
				() -> new ByteArrayInputStream(BYTES), path -> {
				});

		assertEquals(nested, staged.path().getParent());
	}

	private List<String> temporaries() {
		try (Stream<Path> files = Files.list(staging)) {
			return files.map(p -> p.getFileName().toString())
					.filter(name -> name.endsWith(".tmp"))
					.sorted()
					.toList();
		}
		catch (IOException e) {
			throw new AssertionError(e);
		}
	}
}
