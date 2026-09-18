// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Saga IT, LLC

package dev.kastle.webrtc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.kastle.webrtc.internal.BinaryHeader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Which container format a library file is, read from its own magic bytes.
 *
 * <p>The fixtures are the first 512 bytes of the six natives CI publishes, checked in under
 * {@code src/test/resources/headers/}. They cover every format on every runner: a magic-byte check
 * needs no loader, no matching CPU and no dlopen.
 */
class BinaryHeaderTest {

	@TempDir
	Path tmp;

	// Every classifier we ship, read from a real 512-byte head of the real binary. The arch column
	// is what keeps a wrong-architecture install from being reported as an application-control
	// problem on Windows, where the two are otherwise indistinguishable.
	@ParameterizedTest
	@CsvSource({
			"linux-x86_64,    ELF,   x86_64",
			"linux-aarch64,   ELF,   aarch64",
			"windows-x86_64,  PE,    x86_64",
			"windows-aarch64, PE,    aarch64",
			"macos-x86_64,    MACHO, x86_64",
			"macos-aarch64,   MACHO, aarch64"
	})
	void theSixShippedNativesParse(String classifier, String format, String arch) {
		BinaryHeader header = BinaryHeader.parse(NativeTestSupport.headerFixture(classifier));

		assertEquals(BinaryHeader.Format.valueOf(format), header.format(), classifier);
		assertEquals(arch, header.arch(), classifier);
	}

	@Test
	void aDllOnLinuxIsTheWrongFormat() throws IOException {
		Path dll = NativeTestSupport.writeHeaderFixture(tmp.resolve("lib.bin"), "windows-x86_64");

		assertEquals(NativeFault.WRONG_FORMAT,
				NativeLibrary.classify("boom", dll, Platform.of("Linux", "amd64", tmp)));
	}

	@Test
	void anythingElseIsUnrecognised() throws IOException {
		Path text = tmp.resolve("not-a-library.txt");

		Files.writeString(text, "this is not a shared library", StandardCharsets.UTF_8);

		assertEquals(BinaryHeader.Format.UNKNOWN, BinaryHeader.read(text).format());
	}

	@Test
	void anEmptyFileDoesNotThrow() throws IOException {
		Path empty = Files.createFile(tmp.resolve("empty.bin"));

		assertEquals(BinaryHeader.Format.UNKNOWN, BinaryHeader.read(empty).format());
	}
}
