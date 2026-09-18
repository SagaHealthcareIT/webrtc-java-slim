// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Saga IT, LLC

package dev.kastle.webrtc;

/**
 * Why the WebRTC native library could not be used on this host.
 *
 * <p>The declaration order of these constants <em>is</em> the classification
 * precedence: {@link NativeLibrary#classify} walks them top to bottom and takes
 * the first match. That ordering is load-bearing for {@link #HOST_LOADER_CONFLICT}
 * and {@link #LIBC_TOO_OLD}, which must be evaluated before the two catch-alls
 * ({@link #NOEXEC_OR_SELINUX} on Linux, {@link #WINDOWS_APP_CONTROL} on Windows) —
 * those catch-alls are defined by what they exclude and would otherwise swallow a
 * case whose remediation is completely different.
 *
 * <p>"This engine does not need WebRTC at all" is deliberately <em>not</em> a
 * constant here: that is host knowledge this library does not have. The library
 * always classifies; the host decides whether to raise.
 */
public enum NativeFault {

	/**
	 * No {@link NativeLibrary#configure(NativeSource)} before the first load. Unreachable in a
	 * shipped host, and the one fault that is never memoised: it describes the caller's ordering, not
	 * the machine, so a {@code configure()} that arrives afterwards still resolves and loads.
	 */
	NOT_CONFIGURED,

	/**
	 * The host has no library for this os/arch, this JVM's {@code os.arch} is one we do not
	 * map, or the C library is musl.
	 */
	PLATFORM_NOT_SHIPPED,

	/** The host named a file for this platform and it is absent or unreadable. */
	MISSING_FILE,

	/**
	 * The file's sha256 does not match the digest the host expects. <strong>Terminal</strong>:
	 * no further tier is attempted.
	 */
	CORRUPT_INSTALL,

	/**
	 * Our own ELF {@code e_machine} / PE {@code Machine} parse disagrees with {@code os.arch},
	 * or hotspot appended its {@code (Possible cause:} suffix.
	 */
	ARCH_MISMATCH,

	/** Our own magic check (0x7f ELF, {@code MZ}, Mach-O) disagrees with the OS. */
	WRONG_FORMAT,

	/**
	 * The library is already held by another classloader in this JVM. Evaluated before the two
	 * catch-alls: this text is emitted by the JVM on every platform and its remediation is
	 * unrelated to mounts or application control.
	 */
	HOST_LOADER_CONFLICT,

	/** The loader reported a missing {@code GLIBC_2.x} version symbol. Evaluated before {@link #NOEXEC_OR_SELINUX}. */
	LIBC_TOO_OLD,

	/**
	 * Linux; the load failed and the longest-prefix mount for the file in {@code /proc/self/mounts}
	 * carries {@code noexec}. That mount-table match is the only structural trigger;
	 * {@code /sys/fs/selinux/enforce} is read only to decide whether to append the
	 * {@code ausearch} sentence to the remediation.
	 */
	NOEXEC_OR_SELINUX,

	/**
	 * Windows; the file exists, is readable, its PE machine matches, a dependent-module failure and
	 * a classloader conflict have been ruled out, and the load still failed.
	 */
	WINDOWS_APP_CONTROL,

	/** Anything else. The raw JVM message and the paths tried travel with the result. */
	UNKNOWN;

	/** True when no further tier may be attempted after this fault. */
	public boolean isTerminal() {
		return this == CORRUPT_INSTALL;
	}
}
