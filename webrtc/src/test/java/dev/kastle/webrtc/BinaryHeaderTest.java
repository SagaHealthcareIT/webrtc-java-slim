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
 * What a library file is, read from its own bytes.
 *
 * <p>The fixtures are the first 512 bytes of the six natives CI publishes, checked in under
 * {@code src/test/resources/headers/}. They are the only aarch64 coverage that runs on every runner:
 * a header parse needs no loader, no matching CPU and no dlopen.
 */
class BinaryHeaderTest {

	@TempDir
	Path tmp;

	@ParameterizedTest
	@CsvSource({
			"linux-x86_64,    ELF,   X86_64",
			"linux-aarch64,   ELF,   AARCH64",
			"windows-x86_64,  PE,    X86_64",
			"windows-aarch64, PE,    AARCH64",
			"macos-x86_64,    MACHO, X86_64",
			"macos-aarch64,   MACHO, AARCH64"
	})
	void theSixShippedNativesParse(String classifier, String format, String arch) {
		BinaryHeader header = BinaryHeader.parse(NativeTestSupport.headerFixture(classifier));

		assertEquals(BinaryHeader.Format.valueOf(format), header.format(), classifier);
		assertEquals(BinaryHeader.Arch.valueOf(arch), header.arch(), classifier);
	}

	@Test
	void everyShippedElfIs64Bit() {
		assertEquals(2, BinaryHeader.parse(NativeTestSupport.headerFixture("linux-x86_64")).elfClass());
		assertEquals(2, BinaryHeader.parse(NativeTestSupport.headerFixture("linux-aarch64")).elfClass());
	}

	@Test
	void aDllOnLinuxIsTheWrongFormat() throws IOException {
		Path dll = NativeTestSupport.writeHeaderFixture(tmp.resolve("lib.bin"), "windows-x86_64");

		assertEquals(NativeFault.WRONG_FORMAT,
				NativeLibrary.classify("boom", dll, Platform.of("Linux", "amd64", tmp)));
	}

	@Test
	void anAarch64ElfOnX86IsAnArchMismatch() throws IOException {
		Path so = NativeTestSupport.writeHeaderFixture(tmp.resolve("lib.bin"), "linux-aarch64");

		assertEquals(NativeFault.ARCH_MISMATCH,
				NativeLibrary.classify("boom", so, Platform.of("Linux", "amd64", tmp)));
	}

	@Test
	void anythingElseIsUnrecognised() throws IOException {
		Path text = tmp.resolve("not-a-library.txt");

		Files.writeString(text, "this is not a shared library", StandardCharsets.UTF_8);

		BinaryHeader header = BinaryHeader.read(text);

		assertEquals(BinaryHeader.Format.UNKNOWN, header.format());
		assertEquals(BinaryHeader.Arch.OTHER, header.arch());
	}

	@Test
	void anEmptyFileDoesNotThrow() throws IOException {
		Path empty = Files.createFile(tmp.resolve("empty.bin"));

		assertEquals(BinaryHeader.Format.UNKNOWN, BinaryHeader.read(empty).format());
	}
}
