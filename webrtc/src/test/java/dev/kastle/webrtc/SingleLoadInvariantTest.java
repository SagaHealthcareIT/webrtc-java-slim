// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Saga IT, LLC

package dev.kastle.webrtc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The library is loaded once per JVM, whatever the callers do.
 *
 * <p>Two canonical paths holding identical bytes are two {@code dlopen}s and two {@code JNI_OnLoad}
 * runs — verified live with {@code -Xlog:library=info} — which is a second WebRTC context, a second
 * set of threads, and a class-registration race. The memoised result is what prevents it.
 */
class SingleLoadInvariantTest {

	@TempDir
	Path tmp;

	private final AtomicInteger loads = new AtomicInteger();

	private final AtomicReference<String> lastPath = new AtomicReference<>();

	private String classifier;

	@BeforeEach
	void setUp() {
		NativeTestSupport.assumeNoOperatorOverride();

		NativeLibrary.resetForTests();
		NativeLibrary.loaderForTests(path -> {
			loads.incrementAndGet();
			lastPath.set(path);
		});

		classifier = Platform.current().classifier().orElse("linux-x86_64");
	}

	@AfterEach
	void tearDown() {
		NativeLibrary.resetForTests();
	}

	@Test
	void twoPathsWithIdenticalBytesLoadOnce() throws IOException {
		Path first = NativeTestSupport.writeHeaderFixture(tmp.resolve("a/lib.bin"), classifier);
		Path second = NativeTestSupport.writeHeaderFixture(tmp.resolve("b/lib.bin"), classifier);

		NativeLibrary.configure(FakeNativeSource.empty().installed(first));
		NativeLibrary.ensureLoaded();

		NativeLibrary.configure(FakeNativeSource.empty().installed(second));
		LoadResult result = NativeLibrary.ensureLoaded();

		assertEquals(1, loads.get());
		assertEquals(first, result.path().orElseThrow());
		assertEquals(first.toString(), lastPath.get());
	}

	@Test
	void repeatedCallsReturnTheSameResult() throws IOException {
		NativeLibrary.configure(FakeNativeSource.empty()
				.installed(NativeTestSupport.writeHeaderFixture(tmp.resolve("lib.bin"), classifier)));

		LoadResult first = NativeLibrary.ensureLoaded();

		for (int i = 0; i < 5; i++) {
			assertEquals(first, NativeLibrary.ensureLoaded());
		}

		assertEquals(1, loads.get());
	}

	@Test
	void stagingTwiceStagesAndLoadsOnce() {
		Path staging = tmp.resolve("staging");

		NativeLibrary.configure(FakeNativeSource.empty()
				.resource(NativeTestSupport.headerFixture(classifier))
				.staging(staging));

		Path staged = NativeLibrary.ensureLoaded().path().orElseThrow();

		NativeLibrary.ensureLoaded();

		assertEquals(1, loads.get());
		assertTrue(staged.startsWith(staging));
	}

	@Test
	void aPreflightFollowedByALoadStillLoadsOnce() throws IOException {
		NativeLibrary.configure(FakeNativeSource.empty()
				.installed(NativeTestSupport.writeHeaderFixture(tmp.resolve("lib.bin"), classifier)));

		assertTrue(NativeLibrary.preflight().isEmpty(), "a clean preflight reports nothing");

		NativeLibrary.ensureLoaded();
		NativeLibrary.preflight();

		assertEquals(1, loads.get());
	}
}
