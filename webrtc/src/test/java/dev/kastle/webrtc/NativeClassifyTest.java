// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Saga IT, LLC

package dev.kastle.webrtc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Classification of a failed load: structural first, message second, and only on locale-stable
 * tokens. Every glibc and Win32 string is localized — the three variants below were reproduced live
 * — so a classification that keys on a translated sentence is a classification that fails on the
 * hosts most likely to hit it.
 *
 * <p>The walk order is the declaration order of {@link NativeFault}, and two rows depend on it:
 * {@code HOST_LOADER_CONFLICT} and {@code LIBC_TOO_OLD} are decided before the two catch-alls.
 */
class NativeClassifyTest {

	/** Verified live on a de_DE host. */
	private static final String GERMAN = "Fehler beim Mappen des Shared Objects";

	/** Verified live on a fr_FR host. */
	private static final String FRENCH =
			"\u00e9chec d'adressage (mapping) du segment de l'objet partag\u00e9";

	/** Verified live on a ja_JP host. */
	private static final String JAPANESE =
			"\u5171\u6709\u30aa\u30d6\u30b8\u30a7\u30af\u30c8\u306e\u30bb\u30b0\u30e1\u30f3\u30c8"
			+ "\u3092\u30de\u30c3\u30d7\u3059\u308b\u306e\u306b\u5931\u6557\u3057\u307e\u3057\u305f";

	@TempDir
	Path root;

	private Path execDir;
	private Path noexecDir;

	@BeforeEach
	void setUp() throws IOException {
		NativeTestSupport.assumeNoOperatorOverride();

		NativeLibrary.resetForTests();

		execDir = Files.createDirectories(root.resolve("opt/exec"));
		noexecDir = Files.createDirectories(root.resolve("opt/noexec"));

		Files.createDirectories(root.resolve("proc/self"));
		Files.writeString(root.resolve("proc/self/mounts"),
				"/dev/vda1 / ext4 rw,relatime 0 0\n"
				+ "tmpfs " + execDir + " tmpfs rw,nosuid,nodev 0 0\n"
				+ "tmpfs " + noexecDir + " tmpfs rw,noexec,nosuid,nodev 0 0\n",
				StandardCharsets.UTF_8);
	}

	@AfterEach
	void tearDown() {
		NativeLibrary.resetForTests();
	}

	@Test
	void unsupportedArchIsPlatformNotShipped() throws IOException {
		assertEquals(NativeFault.PLATFORM_NOT_SHIPPED,
				NativeLibrary.classify("boom", exec("linux-x86_64"), linux("sparcv9")));
	}

	@Test
	void muslIsPlatformNotShipped() throws IOException {
		Files.createDirectories(root.resolve("lib"));
		Files.createFile(root.resolve("lib/ld-musl-x86_64.so.1"));

		assertEquals(NativeFault.PLATFORM_NOT_SHIPPED,
				NativeLibrary.classify("boom", exec("linux-x86_64"), linux("amd64")));
	}

	@Test
	void anAbsentFileIsMissing() {
		assertEquals(NativeFault.MISSING_FILE,
				NativeLibrary.classify("boom", execDir.resolve("absent.bin"), linux("amd64")));
	}

	@Test
	void aForeignElfIsAnArchMismatchWhenTheLoaderSaysSo() throws IOException {
		// There is no structural arch check: an aarch64 .so on x86 is an ELF like any other, and the
		// only signal that it is the wrong one is hotspot's suffix.
		String message = "/opt/lib.so: wrong ELF class: ELFCLASS64"
				+ " (Possible cause: can't load AARCH64 .so on a AMD 64 platform)";

		assertEquals(NativeFault.ARCH_MISMATCH,
				NativeLibrary.classify(message, exec("linux-aarch64"), linux("amd64")));
	}

	@Test
	void hotspotsPossibleCauseSuffixIsAnArchMismatch() throws IOException {
		String message = "/opt/lib.so: cannot open shared object file: No such file or directory"
				+ " (Possible cause: can't load AARCH64 .so on a AMD 64 platform)";

		assertEquals(NativeFault.ARCH_MISMATCH,
				NativeLibrary.classify(message, exec("linux-x86_64"), linux("amd64")));
	}

	@Test
	void aDllOnLinuxIsTheWrongFormat() throws IOException {
		assertEquals(NativeFault.WRONG_FORMAT,
				NativeLibrary.classify("boom", exec("windows-x86_64"), linux("amd64")));
	}

	@Test
	void aClassloaderConflictBeatsTheWindowsCatchAll() throws IOException {
		String message = "Native Library C:\\OpenShare\\lib.dll already loaded in another classloader";

		assertEquals(NativeFault.HOST_LOADER_CONFLICT,
				NativeLibrary.classify(message, exec("windows-x86_64"), windows()));
	}

	@Test
	void aClassloaderConflictBeatsTheLinuxCatchAll() throws IOException {
		String message = "Native Library /opt/lib.so is being loaded in another classloader";

		assertEquals(NativeFault.HOST_LOADER_CONFLICT,
				NativeLibrary.classify(message, noexec("linux-x86_64"), linux("amd64")));
	}

	@Test
	void glibcBeatsTheNoexecMount() throws IOException {
		String message = "/opt/lib.so: version `GLIBC_2.32' not found (required by /opt/lib.so)";

		// The file really is on a noexec mount here: the precedence is what decides, not the mount.
		assertEquals(NativeFault.LIBC_TOO_OLD,
				NativeLibrary.classify(message, noexec("linux-x86_64"), linux("amd64")));
	}

	@Test
	void localizedMountFailuresLandOnNoexecThroughTheMountTable() throws IOException {
		Path file = noexec("linux-x86_64");

		for (String message : List.of(GERMAN, FRENCH, JAPANESE)) {
			assertEquals(NativeFault.NOEXEC_OR_SELINUX,
					NativeLibrary.classify(message, file, linux("amd64")), message);
		}
	}

	@Test
	void noexecRemediationNamesFindmnt() throws IOException {
		NativeLibrary.platformForTests(linux("amd64"));
		NativeLibrary.loaderForTests(path -> {
			throw new UnsatisfiedLinkError(GERMAN);
		});
		NativeLibrary.configure(FakeNativeSource.empty().installed(noexec("linux-x86_64")));

		LoadResult result = failedLoad();

		assertEquals(NativeFault.NOEXEC_OR_SELINUX, result.fault().orElseThrow());
		assertTrue(result.remediation().contains("findmnt -T " + noexecDir), result.remediation());
		assertTrue(result.remediation().contains("-Dopenshare.webrtc.native="), result.remediation());
		assertEquals(GERMAN, result.rawJvmMessage().orElseThrow());
	}

	@Test
	void aTokenPastTheTruncationLimitStillClassifies() throws IOException {
		// The raw text is truncated to 512 chars for storage, never for branching: glibc puts the
		// library path first, so on a deep install path the one locale-stable token in the message
		// lands past the limit. Classifying the truncated string would silently degrade this to the
		// Linux catch-all, with remediation about mounts instead of about glibc.
		String message = "/opt/" + "a".repeat(600) + "/lib.so: version `GLIBC_2.32' not found";

		NativeLibrary.platformForTests(linux("amd64"));
		NativeLibrary.loaderForTests(path -> {
			throw new UnsatisfiedLinkError(message);
		});
		NativeLibrary.configure(FakeNativeSource.empty().installed(exec("linux-x86_64")));

		LoadResult result = failedLoad();

		assertEquals(NativeFault.LIBC_TOO_OLD, result.fault().orElseThrow());
		assertEquals(512, result.rawJvmMessage().orElseThrow().length(), "still truncated for storage");
	}

	@Test
	void aTokenPastTheTruncationLimitStillClassifiesAStagedLoad() throws IOException {
		String message = "/opt/" + "a".repeat(600) + "/lib.so: version `GLIBC_2.32' not found";

		NativeLibrary.platformForTests(linux("amd64"));
		NativeLibrary.loaderForTests(path -> {
			throw new UnsatisfiedLinkError(message);
		});
		NativeLibrary.configure(FakeNativeSource.empty()
				.resource(NativeTestSupport.headerFixture("linux-x86_64"))
				.staging(execDir.resolve("staging")));

		LoadResult result = failedLoad();

		assertEquals(NativeFault.LIBC_TOO_OLD, result.fault().orElseThrow());
		assertEquals(512, result.rawJvmMessage().orElseThrow().length(), "still truncated for storage");
	}

	@Test
	void anEscapedMountPointStillMatches() throws IOException {
		// The kernel escapes a space in a mount point as \040; without unescaping, the longest-prefix
		// match silently fails and a noexec mount reads as UNKNOWN.
		Path dir = Files.createDirectories(root.resolve("opt/my dir"));
		Path file = NativeTestSupport.writeHeaderFixture(dir.resolve("lib.bin"), "linux-x86_64");

		Files.writeString(root.resolve("proc/self/mounts"),
				"/dev/vda1 / ext4 rw,relatime 0 0\n"
				+ "tmpfs " + dir.toString().replace(" ", "\\040") + " tmpfs rw,noexec 0 0\n",
				StandardCharsets.UTF_8);

		assertEquals(NativeFault.NOEXEC_OR_SELINUX,
				NativeLibrary.classify(GERMAN, file, linux("amd64")));
	}

	@Test
	void aLaterMountLineShadowsAnEarlierOneOnTheSamePoint() throws IOException {
		Path file = noexec("linux-x86_64");

		// Remounted exec after the fact: the effective mount is the last line for that point.
		Files.writeString(root.resolve("proc/self/mounts"),
				"/dev/vda1 / ext4 rw,relatime 0 0\n"
				+ "tmpfs " + noexecDir + " tmpfs rw,noexec 0 0\n"
				+ "tmpfs " + noexecDir + " tmpfs rw 0 0\n",
				StandardCharsets.UTF_8);

		assertEquals(NativeFault.UNKNOWN, NativeLibrary.classify(GERMAN, file, linux("amd64")));
	}

	@Test
	void enforcingSelinuxOnAnExecMountIsNotEvidenceOfAnything() throws IOException {
		Files.createDirectories(root.resolve("sys/fs/selinux"));
		Files.writeString(root.resolve("sys/fs/selinux/enforce"), "1", StandardCharsets.UTF_8);

		assertEquals(NativeFault.UNKNOWN,
				NativeLibrary.classify(GERMAN, exec("linux-x86_64"), linux("amd64")));
	}

	@Test
	void windowsBlocksWithNoNamedModule() throws IOException {
		assertEquals(NativeFault.WINDOWS_APP_CONTROL,
				NativeLibrary.classify("Access is denied", exec("windows-x86_64"), windows()));
	}

	@Test
	void aMissingDependentDllIsNotApplicationControl() throws IOException {
		String message = "Can't find dependent libraries";

		assertEquals(NativeFault.UNKNOWN,
				NativeLibrary.classify(message, exec("windows-x86_64"), windows()));
	}

	@Test
	void aMessageNamingAnotherModuleIsNotApplicationControl() throws IOException {
		Path file = exec("windows-x86_64");
		String message = "Error loading " + file.getFileName() + ": VCRUNTIME140.dll is missing";

		assertEquals(NativeFault.UNKNOWN, NativeLibrary.classify(message, file, windows()));
	}

	@Test
	void anythingElseIsUnknown() throws IOException {
		assertEquals(NativeFault.UNKNOWN,
				NativeLibrary.classify("something nobody has seen", exec("linux-x86_64"), linux("amd64")));
	}

	@Test
	void notConfiguredAndCorruptInstallComeFromTheResolver() throws IOException {
		// The two structural faults classify() cannot reach: one has no source at all, the other
		// needs the host's expected digest. Both are still NativeFault constants, so they are pinned
		// here beside the other nine.
		assertEquals(NativeFault.NOT_CONFIGURED,
				NativeLibrary.preflight().orElseThrow().fault().orElseThrow());

		NativeLibrary.resetForTests();
		NativeLibrary.platformForTests(linux("amd64"));
		NativeLibrary.configure(FakeNativeSource.empty()
				.installed(exec("linux-x86_64"))
				.installedSha("f".repeat(64)));

		assertEquals(NativeFault.CORRUPT_INSTALL,
				NativeLibrary.preflight().orElseThrow().fault().orElseThrow());
	}

	private LoadResult failedLoad() {
		try {
			NativeLibrary.ensureLoaded();
		}
		catch (NativeUnavailableException e) {
			return e.result();
		}

		throw new AssertionError("expected the load to fail");
	}

	private Platform linux(String arch) {
		return Platform.of("Linux", arch, root);
	}

	private Platform windows() {
		return Platform.of("Windows 11", "amd64", root);
	}

	private Path exec(String classifier) throws IOException {
		return NativeTestSupport.writeHeaderFixture(execDir.resolve(classifier + ".bin"), classifier);
	}

	private Path noexec(String classifier) throws IOException {
		return NativeTestSupport.writeHeaderFixture(noexecDir.resolve(classifier + ".bin"), classifier);
	}
}
