// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Saga IT, LLC

package dev.kastle.webrtc.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Which container format a shared-library file is, and which of the architectures we ship it was
 * built for, read from the file's own header rather than from its name or from a localized JVM
 * message.
 *
 * <p>Deliberately narrow. We ship x86_64 and aarch64 only, so anything else reads as
 * {@code "other"} — the point is to tell an operator "this file is not for this machine", not to
 * be a general object-file reader.
 *
 * <p>The architecture matters because without it a wrong-architecture library is indistinguishable
 * from a library the operating system refused to run, and those have opposite remediations: on
 * Windows the failure would otherwise land in the application-control catch-all and tell the
 * operator to write AppLocker rules for a file that would never have run anyway.
 *
 * <p>Pure: {@link #parse(byte[])} does no I/O. {@link #read(Path)} reads at most
 * {@link #HEADER_BYTES} bytes. ELF and Mach-O carry the machine in their first 20 bytes; PE keeps
 * it behind the offset at 0x3c, which is 0x108 in the libraries we ship.
 */
public final class BinaryHeader {

	/** Container format. */
	public enum Format {
		ELF, PE, MACHO, UNKNOWN
	}

	/** Bytes read from the head of a file. Enough to follow a PE's e_lfanew in any real binary. */
	public static final int HEADER_BYTES = 1024;

	private static final int ELF_MACHINE_X86_64 = 0x3e;
	private static final int ELF_MACHINE_AARCH64 = 0xb7;

	private static final int PE_MACHINE_AMD64 = 0x8664;
	private static final int PE_MACHINE_ARM64 = 0xaa64;

	private static final int MACHO_CPU_X86_64 = 0x01000007;
	private static final int MACHO_CPU_ARM64 = 0x0100000c;

	private final Format format;

	private final String arch;

	private BinaryHeader(Format format) {
		this(format, "");
	}

	private BinaryHeader(Format format, String arch) {
		this.format = format;
		this.arch = arch;
	}

	/** Reads the head of {@code file}. Never throws on a short or empty file. */
	public static BinaryHeader read(Path file) throws IOException {
		byte[] head = new byte[HEADER_BYTES];
		int total = 0;

		try (InputStream in = Files.newInputStream(file)) {
			int read;

			while (total < head.length && (read = in.read(head, total, head.length - total)) != -1) {
				total += read;
			}
		}

		byte[] exact = new byte[total];

		System.arraycopy(head, 0, exact, 0, total);

		return parse(exact);
	}

	/** Classifies the head of a file. A {@code null}, short or unrecognised head is {@link Format#UNKNOWN}. */
	public static BinaryHeader parse(byte[] head) {
		if (head == null || head.length < 8) {
			return new BinaryHeader(Format.UNKNOWN);
		}
		if ((head[0] & 0xff) == 0x7f && head[1] == 'E' && head[2] == 'L' && head[3] == 'F') {
			return new BinaryHeader(Format.ELF, elfArch(head));
		}
		if (head[0] == 'M' && head[1] == 'Z') {
			return new BinaryHeader(Format.PE, peArch(head));
		}
		if (isMachO(head)) {
			return new BinaryHeader(Format.MACHO, machOArch(head));
		}

		return new BinaryHeader(Format.UNKNOWN);
	}

	public Format format() {
		return format;
	}

	/**
	 * One of the architecture names {@code Platform} uses ({@code x86_64}, {@code aarch64}),
	 * {@code "other"} for a machine type we do not ship, or empty when it could not be read.
	 */
	public String arch() {
		return arch;
	}

	private static String elfArch(byte[] head) {
		// e_machine: 2 bytes at offset 18; EI_DATA at offset 5 gives the endianness (1 = little).
		if (head.length < 20) {
			return "";
		}

		boolean little = (head[5] & 0xff) != 2;
		int machine = little ? le16(head, 18) : be16(head, 18);

		return name(machine, ELF_MACHINE_X86_64, ELF_MACHINE_AARCH64);
	}

	private static String peArch(byte[] head) {
		// e_lfanew at 0x3c points at "PE\0\0"; IMAGE_FILE_HEADER.Machine is the 2 bytes after it.
		if (head.length < 0x40) {
			return "";
		}

		int pe = le32(head, 0x3c);

		if (pe < 0 || pe + 6 > head.length || head[pe] != 'P' || head[pe + 1] != 'E') {
			return "";
		}

		return name(le16(head, pe + 4), PE_MACHINE_AMD64, PE_MACHINE_ARM64);
	}

	private static String machOArch(byte[] head) {
		// cputype: 4 bytes at offset 4, in the same byte order as the magic. A universal binary
		// holds several slices, so its arch is not a single value — report it as unread.
		if (head.length < 8) {
			return "";
		}

		int magic = be32(head, 0);

		if (magic == 0xcafebabe || magic == 0xbebafeca) {
			return "";
		}

		boolean little = magic == 0xcefaedfe || magic == 0xcffaedfe;
		int cpu = little ? le32(head, 4) : be32(head, 4);

		return name(cpu, MACHO_CPU_X86_64, MACHO_CPU_ARM64);
	}

	private static String name(int machine, int x8664, int arm64) {
		if (machine == x8664) {
			return "x86_64";
		}
		if (machine == arm64) {
			return "aarch64";
		}

		return "other";
	}

	private static int le16(byte[] b, int off) {
		return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
	}

	private static int be16(byte[] b, int off) {
		return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
	}

	private static int le32(byte[] b, int off) {
		return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
				| ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
	}

	private static int be32(byte[] b, int off) {
		return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
				| ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
	}

	private static boolean isMachO(byte[] head) {
		int magic = ((head[0] & 0xff) << 24) | ((head[1] & 0xff) << 16)
				| ((head[2] & 0xff) << 8) | (head[3] & 0xff);

		return magic == 0xfeedface || magic == 0xfeedfacf         // big-endian thin
				|| magic == 0xcefaedfe || magic == 0xcffaedfe     // little-endian thin
				|| magic == 0xcafebabe || magic == 0xbebafeca;    // universal ("fat")
	}

	@Override
	public String toString() {
		return "BinaryHeader[" + format + (arch.isEmpty() ? "" : " " + arch) + "]";
	}
}
