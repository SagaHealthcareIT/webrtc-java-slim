// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Saga IT, LLC

package dev.kastle.webrtc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tier selection and the structural pass. Runs on every platform: no dlopen happens here, the loader
 * is injected.
 */
class NativeResolveTest {

	@TempDir
	Path tmp;

	private final AtomicInteger loads = new AtomicInteger();

	private String classifier;

	@BeforeEach
	void setUp() {
		NativeTestSupport.assumeNoOperatorOverride();

		NativeLibrary.resetForTests();
		NativeLibrary.loaderForTests(path -> loads.incrementAndGet());

		classifier = Platform.current().classifier().orElse("linux-x86_64");
	}

	@AfterEach
	void tearDown() {
		NativeLibrary.resetForTests();
	}

	@Test
	void unconfiguredIsNotConfigured() {
		assertEquals(NativeFault.NOT_CONFIGURED, preflightFault());

		NativeUnavailableException e = assertThrows(NativeUnavailableException.class,
				NativeLibrary::ensureLoaded);

		assertEquals(NativeFault.NOT_CONFIGURED, e.result().fault().orElseThrow());
		assertEquals(0, loads.get());
	}

	@Test
	void aPreflightBeforeConfigureIsNotMemoised() throws IOException {
		// A caller that beats the host's configure() is a caller-ordering bug, not a host condition:
		// every other fault says "restart the engine" and means it, this one has nothing to restart
		// for. Memoising it would let one early call permanently disable WebRTC on a healthy install,
		// with configure() unable to undo it.
		assertEquals(NativeFault.NOT_CONFIGURED, preflightFault());

		assertTrue(NativeLibrary.status().isEmpty(), "NOT_CONFIGURED must not be memoised");

		assertTrue(recoveredAfterConfigure(), "a late configure() still resolves and loads");
	}

	@Test
	void anEnsureLoadedBeforeConfigureIsNotMemoised() throws IOException {
		NativeUnavailableException e = assertThrows(NativeUnavailableException.class,
				NativeLibrary::ensureLoaded);

		assertEquals(NativeFault.NOT_CONFIGURED, e.result().fault().orElseThrow());
		assertTrue(NativeLibrary.status().isEmpty(), "NOT_CONFIGURED must not be memoised");

		assertTrue(recoveredAfterConfigure(), "a late configure() still resolves and loads");
	}

	@Test
	void overrideWinsOverInstalled() throws IOException {
		Path override = NativeTestSupport.writeHeaderFixture(tmp.resolve("override/lib.bin"), classifier);
		Path installed = NativeTestSupport.writeHeaderFixture(tmp.resolve("plugin/lib.bin"), classifier);

		NativeLibrary.configure(FakeNativeSource.empty().override(override).installed(installed));

		LoadResult result = NativeLibrary.ensureLoaded();

		assertEquals(LoadResult.Tier.OVERRIDE, result.tier().orElseThrow());
		assertEquals(override, result.path().orElseThrow());
		assertEquals(1, loads.get());
	}

	@Test
	void theOperatorOverrideIsHonouredWithoutHostSupport() throws IOException {
		// The escape hatch every remediation string names has to work in every host, not only in the
		// hosts that remembered to read the property in their own NativeSource.
		Path override = NativeTestSupport.writeHeaderFixture(tmp.resolve("override/lib.bin"), classifier);
		Path installed = NativeTestSupport.writeHeaderFixture(tmp.resolve("plugin/lib.bin"), classifier);

		System.setProperty(NativeLibrary.OVERRIDE_PROPERTY, override.toString());

		try {
			NativeLibrary.configure(FakeNativeSource.empty().installed(installed));

			LoadResult result = NativeLibrary.ensureLoaded();

			assertEquals(LoadResult.Tier.OVERRIDE, result.tier().orElseThrow());
			assertEquals(override, result.path().orElseThrow());
		}
		finally {
			System.clearProperty(NativeLibrary.OVERRIDE_PROPERTY);
		}
	}

	@Test
	void aHostThatReadsTheOverrideItselfStillWins() throws IOException {
		Path hostOverride = NativeTestSupport.writeHeaderFixture(tmp.resolve("host/lib.bin"), classifier);

		System.setProperty(NativeLibrary.OVERRIDE_PROPERTY, tmp.resolve("absent.bin").toString());

		try {
			NativeLibrary.configure(FakeNativeSource.empty().override(hostOverride));

			assertEquals(hostOverride, NativeLibrary.ensureLoaded().path().orElseThrow());
		}
		finally {
			System.clearProperty(NativeLibrary.OVERRIDE_PROPERTY);
		}
	}

	@Test
	void thePropertyBeatsTheEnvironmentAndBlanksAreNotAnOverride() {
		assertEquals(Optional.of(Path.of("/opt/from-property.so")),
				NativeLibrary.overrideOf("/opt/from-property.so", "/opt/from-env.so"));
		assertEquals(Optional.of(Path.of("/opt/from-env.so")),
				NativeLibrary.overrideOf("   ", " /opt/from-env.so "));
		assertEquals(Optional.empty(), NativeLibrary.overrideOf(null, null));
		assertEquals(Optional.empty(), NativeLibrary.overrideOf("", "  "));
	}

	@Test
	void installedFileIsLoadedInPlace() throws IOException {
		Path installed = NativeTestSupport.writeHeaderFixture(tmp.resolve("plugin/native/lib.bin"), classifier);

		NativeLibrary.configure(FakeNativeSource.empty().installed(installed));

		LoadResult result = NativeLibrary.ensureLoaded();

		assertEquals(LoadResult.Tier.INSTALLED, result.tier().orElseThrow());
		assertEquals(installed, result.path().orElseThrow());
		assertTrue(result.sha256().orElseThrow().matches("[0-9a-f]{64}"));
	}

	@Test
	void jarResourcePlusStagingDirStages() throws IOException {
		Path staging = tmp.resolve("staging");

		NativeLibrary.configure(FakeNativeSource.empty()
				.resource(NativeTestSupport.headerFixture(classifier))
				.staging(staging));

		LoadResult result = NativeLibrary.ensureLoaded();

		assertEquals(LoadResult.Tier.STAGED, result.tier().orElseThrow());
		assertEquals(staging, result.path().orElseThrow().getParent());
		assertEquals(1, loads.get());
	}

	@Test
	void aJarResourceWithoutAStagingDirIsNotATier() {
		NativeLibrary.configure(FakeNativeSource.empty()
				.resource(NativeTestSupport.headerFixture(classifier)));

		assertEquals(NativeFault.PLATFORM_NOT_SHIPPED, preflightFault());
	}

	@Test
	void nothingConfiguredAtAllIsPlatformNotShipped() {
		// The plugin's source returns empty for installedFile() exactly when the MANIFEST has no
		// entry for this os-arch, and it has no tier 3 at all.
		NativeLibrary.configure(FakeNativeSource.empty());

		assertEquals(NativeFault.PLATFORM_NOT_SHIPPED, preflightFault());
	}

	@Test
	void missingInstalledFile() {
		Path missing = tmp.resolve("plugin/native/absent.bin");

		NativeLibrary.configure(FakeNativeSource.empty().installed(missing));

		LoadResult result = NativeLibrary.preflight().orElseThrow();

		assertEquals(NativeFault.MISSING_FILE, result.fault().orElseThrow());
		assertEquals(List.of(missing.toString()), result.triedPaths());
		assertTrue(result.remediation().contains("Reinstall the Gateway Connector plugin"));
		assertEquals(0, loads.get());
	}

	@Test
	void shaMismatchIsACorruptInstall() throws IOException {
		Path installed = NativeTestSupport.writeHeaderFixture(tmp.resolve("plugin/native/lib.bin"), classifier);

		NativeLibrary.configure(FakeNativeSource.empty()
				.installed(installed)
				.installedSha("0".repeat(64)));

		LoadResult result = NativeLibrary.preflight().orElseThrow();

		assertEquals(NativeFault.CORRUPT_INSTALL, result.fault().orElseThrow());
		assertTrue(result.fault().orElseThrow().isTerminal());
		assertTrue(result.message().contains("expected 000000000000"), result.message());
		assertTrue(result.remediation().contains("Reinstall the plugin"), result.remediation());
		assertEquals(0, loads.get());
	}

	@Test
	void unsupportedArchIsPlatformNotShipped() throws IOException {
		NativeLibrary.platformForTests(Platform.of("Linux", "sparcv9", tmp));
		NativeLibrary.configure(FakeNativeSource.empty()
				.installed(NativeTestSupport.writeHeaderFixture(tmp.resolve("lib.bin"), classifier)));

		LoadResult result = NativeLibrary.preflight().orElseThrow();

		assertEquals(NativeFault.PLATFORM_NOT_SHIPPED, result.fault().orElseThrow());
		assertTrue(result.remediation().contains("linux-x86_64"), result.remediation());
		assertTrue(result.remediation().contains("Linux/sparcv9"), result.remediation());
	}

	@Test
	void muslIsPlatformNotShipped() throws IOException {
		Files.createDirectories(tmp.resolve("lib"));
		Files.createFile(tmp.resolve("lib/ld-musl-x86_64.so.1"));

		NativeLibrary.platformForTests(Platform.of("Linux", "amd64", tmp));
		NativeLibrary.configure(FakeNativeSource.empty()
				.installed(NativeTestSupport.writeHeaderFixture(tmp.resolve("lib.bin"), "linux-x86_64")));

		LoadResult result = NativeLibrary.preflight().orElseThrow();

		assertEquals(NativeFault.PLATFORM_NOT_SHIPPED, result.fault().orElseThrow());
		assertTrue(result.remediation().contains("Ubuntu-based image"), result.remediation());
	}

	@Test
	void aGlibcHostThatAlsoHasAMuslLoaderIsStillShipped() throws IOException {
		// Debian and Ubuntu ship /lib/ld-musl-x86_64.so.1 in the `musl` package. Detecting musl from
		// that file alone would tell such an operator to "use an Ubuntu-based image" while listing
		// their own platform as one we ship.
		Files.createDirectories(tmp.resolve("lib"));
		Files.createDirectories(tmp.resolve("lib64"));
		Files.createFile(tmp.resolve("lib/ld-musl-x86_64.so.1"));
		Files.createFile(tmp.resolve("lib64/ld-linux-x86-64.so.2"));

		NativeLibrary.platformForTests(Platform.of("Linux", "amd64", tmp));
		NativeLibrary.configure(FakeNativeSource.empty()
				.installed(NativeTestSupport.writeHeaderFixture(tmp.resolve("lib.bin"), "linux-x86_64")));

		assertTrue(NativeLibrary.preflight().isEmpty(), "a glibc host is shipped");
	}

	@Test
	void theResolvedLineIsPinnedAndNamesAFileOnDisk() throws IOException {
		Path installed = NativeTestSupport.writeHeaderFixture(tmp.resolve("plugin/native/lib.bin"), classifier);

		NativeLibrary.configure(FakeNativeSource.empty().installed(installed));

		List<String> lines = capture(NativeLibrary::preflight);

		assertEquals(1, lines.size(), lines::toString);
		assertTrue(lines.get(0).startsWith("webrtc native resolved tier=INSTALLED path=" + installed
				+ " version=1.3.0-test sha256="), lines.get(0));
		assertTrue(lines.get(0).contains(" platform=" + classifier), lines.get(0));
	}

	@Test
	void aStagedResolutionHasNoResolvedLine() {
		// Tier 3 writes and loads in one locked step: there is no file to name until the load has
		// happened, so a staged host's only pinned line is "loaded". Anything that greps a tier-3
		// host's log for "resolved" is greping for a line that cannot exist.
		NativeLibrary.configure(FakeNativeSource.empty()
				.resource(NativeTestSupport.headerFixture(classifier))
				.staging(tmp.resolve("staging")));

		assertEquals(List.of(), capture(NativeLibrary::preflight));

		List<String> lines = capture(NativeLibrary::ensureLoaded);

		assertEquals(1, lines.size(), lines::toString);
		assertTrue(lines.get(0).startsWith("webrtc native loaded   tier=STAGED path="), lines.get(0));
	}

	@Test
	void preflightIsSilentWhenNothingIsWrongAndDoesNotLoad() throws IOException {
		Path installed = NativeTestSupport.writeHeaderFixture(tmp.resolve("plugin/native/lib.bin"), classifier);

		NativeLibrary.configure(FakeNativeSource.empty().installed(installed));

		assertTrue(NativeLibrary.preflight().isEmpty());
		assertEquals(0, loads.get());
		assertTrue(NativeLibrary.status().isEmpty());
	}

	@Test
	void aMemoisedFailureIsAJvmLifetimeState() throws IOException {
		NativeLibrary.configure(FakeNativeSource.empty().installed(tmp.resolve("absent.bin")));

		LoadResult first = NativeLibrary.preflight().orElseThrow();

		// A later configure() of a perfectly good source does not resurrect the library: the
		// operator-facing remediation says "restart the engine", and that has to be true.
		NativeLibrary.configure(FakeNativeSource.empty()
				.installed(NativeTestSupport.writeHeaderFixture(tmp.resolve("lib.bin"), classifier)));

		assertSame(first, NativeLibrary.status().orElseThrow());
		assertFalse(NativeLibrary.status().orElseThrow().isLoaded());
		assertEquals(0, loads.get());
	}

	private NativeFault preflightFault() {
		return NativeLibrary.preflight().orElseThrow().fault().orElseThrow();
	}

	/** Configures a healthy source after the fact and reports whether the library came back. */
	private boolean recoveredAfterConfigure() throws IOException {
		NativeLibrary.configure(FakeNativeSource.empty()
				.installed(NativeTestSupport.writeHeaderFixture(tmp.resolve("plugin/lib.bin"), classifier)));

		return NativeLibrary.preflight().isEmpty()
				&& NativeLibrary.ensureLoaded().isLoaded()
				&& loads.get() == 1;
	}

	/** The INFO lines {@code action} emits, in order. The two pinned strings are matched verbatim. */
	private List<String> capture(Runnable action) {
		Logger logger = Logger.getLogger(NativeLibrary.class.getName());
		List<String> lines = new ArrayList<>();
		Handler handler = new Handler() {

			@Override
			public void publish(LogRecord record) {
				lines.add(record.getMessage());
			}

			@Override
			public void flush() {
			}

			@Override
			public void close() {
			}
		};

		Level previous = logger.getLevel();

		logger.setLevel(Level.INFO);
		logger.addHandler(handler);

		try {
			action.run();
		}
		finally {
			logger.removeHandler(handler);
			logger.setLevel(previous);
		}

		return lines;
	}
}
