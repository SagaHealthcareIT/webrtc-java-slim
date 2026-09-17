// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Saga IT, LLC

package dev.kastle.webrtc.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What a shared-library file actually is, read from its own bytes rather than from its name or from
 * a localized JVM message.
 *
 * <p>Pure: {@link #parse(byte[])} does no I/O. {@link #read(Path)} reads at most
 * {@link #HEADER_BYTES} bytes from the head of the file — enough to reach a PE
 * {@code IMAGE_FILE_HEADER.Machine} through {@code e_lfanew} (0x108 in the natives we ship).
 */
public final class BinaryHeader {

	/** Container format. */
	public enum Format {
		ELF, PE, MACHO, UNKNOWN
	}

	/** Target architecture, as the container declares it. */
	public enum Arch {
		X86_64, AARCH64, AARCH32, OTHER
	}

	/** Bytes read from the head of a file. A PE's {@code e_lfanew} is a 32-bit file offset. */
	public static final int HEADER_BYTES = 4096;

	private static final int ELF_MACHINE_386 = 0x03;
	private static final int ELF_MACHINE_ARM = 0x28;
	private static final int ELF_MACHINE_X86_64 = 0x3e;
	private static final int ELF_MACHINE_AARCH64 = 0xb7;

	private static final int PE_MACHINE_I386 = 0x014c;
	private static final int PE_MACHINE_ARM = 0x01c0;
	private static final int PE_MACHINE_ARMNT = 0x01c4;
	private static final int PE_MACHINE_AMD64 = 0x8664;
	private static final int PE_MACHINE_ARM64 = 0xaa64;

	private static final int MACHO_CPU_X86_64 = 0x01000007;
	private static final int MACHO_CPU_ARM64 = 0x0100000c;
	private static final int MACHO_CPU_ARM = 0x0000000c;

	private final Format format;
	private final Arch arch;
	private final int elfClass;

	private BinaryHeader(Format format, Arch arch, int elfClass) {
		this.format = format;
		this.arch = arch;
		this.elfClass = elfClass;
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
			return new BinaryHeader(Format.UNKNOWN, Arch.OTHER, 0);
		}
		if (isElf(head)) {
			return parseElf(head);
		}
		if (head[0] == 'M' && head[1] == 'Z') {
			return parsePe(head);
		}
		if (isMachO(head)) {
			return parseMachO(head);
		}

		return new BinaryHeader(Format.UNKNOWN, Arch.OTHER, 0);
	}

	public Format format() {
		return format;
	}

	public Arch arch() {
		return arch;
	}

	/** ELF {@code e_ident[EI_CLASS]}: 1 = 32-bit, 2 = 64-bit. Zero for a non-ELF file. */
	public int elfClass() {
		return elfClass;
	}

	/** The mapped {@code os.arch} name this file targets, e.g. {@code x86_64}; empty for {@link Arch#OTHER}. */
	public String archName() {
		switch (arch) {
			case X86_64:
				return "x86_64";
			case AARCH64:
				return "aarch64";
			case AARCH32:
				return "aarch32";
			default:
				return "";
		}
	}

	private static boolean isElf(byte[] head) {
		return (head[0] & 0xff) == 0x7f && head[1] == 'E' && head[2] == 'L' && head[3] == 'F';
	}

	private static boolean isMachO(byte[] head) {
		int magic = beInt(head, 0);

		return magic == 0xfeedface || magic == 0xfeedfacf         // big-endian thin
				|| magic == 0xcefaedfe || magic == 0xcffaedfe     // little-endian thin
				|| magic == 0xcafebabe || magic == 0xbebafeca;    // universal ("fat")
	}

	private static BinaryHeader parseElf(byte[] head) {
		int elfClass = head.length > 4 ? head[4] & 0xff : 0;

		if (head.length < 20) {
			return new BinaryHeader(Format.ELF, Arch.OTHER, elfClass);
		}

		boolean littleEndian = (head[5] & 0xff) != 2;
		int machine = littleEndian ? leShort(head, 18) : beShort(head, 18);

		Arch arch;

		switch (machine) {
			case ELF_MACHINE_X86_64:
				arch = Arch.X86_64;
				break;
			case ELF_MACHINE_AARCH64:
				arch = Arch.AARCH64;
				break;
			case ELF_MACHINE_ARM:
				arch = Arch.AARCH32;
				break;
			case ELF_MACHINE_386:
			default:
				arch = Arch.OTHER;
				break;
		}

		return new BinaryHeader(Format.ELF, arch, elfClass);
	}

	private static BinaryHeader parsePe(byte[] head) {
		if (head.length < 0x40) {
			return new BinaryHeader(Format.PE, Arch.OTHER, 0);
		}

		int lfanew = leInt(head, 0x3c);

		if (lfanew < 0 || lfanew + 6 > head.length) {
			// A DOS stub we cannot follow: the format is still unambiguous.
			return new BinaryHeader(Format.PE, Arch.OTHER, 0);
		}
		if (head[lfanew] != 'P' || head[lfanew + 1] != 'E'
				|| head[lfanew + 2] != 0 || head[lfanew + 3] != 0) {
			return new BinaryHeader(Format.PE, Arch.OTHER, 0);
		}

		int machine = leShort(head, lfanew + 4);

		Arch arch;

		switch (machine) {
			case PE_MACHINE_AMD64:
				arch = Arch.X86_64;
				break;
			case PE_MACHINE_ARM64:
				arch = Arch.AARCH64;
				break;
			case PE_MACHINE_ARM:
			case PE_MACHINE_ARMNT:
				arch = Arch.AARCH32;
				break;
			case PE_MACHINE_I386:
			default:
				arch = Arch.OTHER;
				break;
		}

		return new BinaryHeader(Format.PE, arch, 0);
	}

	private static BinaryHeader parseMachO(byte[] head) {
		int magic = beInt(head, 0);

		if (magic == 0xcafebabe || magic == 0xbebafeca) {
			// Universal binary: the per-slice cputype lives past the fat header; we only need to
			// know it is a Mach-O container.
			return new BinaryHeader(Format.MACHO, Arch.OTHER, 0);
		}
		if (head.length < 8) {
			return new BinaryHeader(Format.MACHO, Arch.OTHER, 0);
		}

		boolean littleEndian = magic == 0xcefaedfe || magic == 0xcffaedfe;
		int cpuType = littleEndian ? leInt(head, 4) : beInt(head, 4);

		Arch arch;

		switch (cpuType) {
			case MACHO_CPU_X86_64:
				arch = Arch.X86_64;
				break;
			case MACHO_CPU_ARM64:
				arch = Arch.AARCH64;
				break;
			case MACHO_CPU_ARM:
				arch = Arch.AARCH32;
				break;
			default:
				arch = Arch.OTHER;
				break;
		}

		return new BinaryHeader(Format.MACHO, arch, 0);
	}

	private static int leShort(byte[] b, int offset) {
		return (b[offset] & 0xff) | ((b[offset + 1] & 0xff) << 8);
	}

	private static int beShort(byte[] b, int offset) {
		return ((b[offset] & 0xff) << 8) | (b[offset + 1] & 0xff);
	}

	private static int leInt(byte[] b, int offset) {
		return (b[offset] & 0xff) | ((b[offset + 1] & 0xff) << 8)
				| ((b[offset + 2] & 0xff) << 16) | ((b[offset + 3] & 0xff) << 24);
	}

	private static int beInt(byte[] b, int offset) {
		return ((b[offset] & 0xff) << 24) | ((b[offset + 1] & 0xff) << 16)
				| ((b[offset + 2] & 0xff) << 8) | (b[offset + 3] & 0xff);
	}

	@Override
	public String toString() {
		return "BinaryHeader[" + format + " " + arch + (elfClass != 0 ? " class=" + elfClass : "") + "]";
	}
}
