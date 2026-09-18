// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Saga IT, LLC

package dev.kastle.webrtc.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The Linux mount table, parsed by us.
 *
 * <p>This is the only structural trigger for {@code NOEXEC_OR_SELINUX}: a longest-prefix match of the
 * file's own path against {@code /proc/self/mounts}, whose option words are kernel tokens and are
 * never localized. Pure given a list of lines, so it unit-tests without a real {@code /proc}.
 */
public final class MountTable {

	/** One line of the mount table: where it is mounted and the options it carries. */
	public static final class Mount {

		private final String mountPoint;
		private final Set<String> options;

		Mount(String mountPoint, Set<String> options) {
			this.mountPoint = mountPoint;
			this.options = Collections.unmodifiableSet(options);
		}

		public String mountPoint() {
			return mountPoint;
		}

		public Set<String> options() {
			return options;
		}

		@Override
		public String toString() {
			return mountPoint + " " + options;
		}
	}

	private MountTable() {
	}

	/**
	 * Parses {@code /proc/self/mounts} lines. Malformed lines are skipped; the fourth field is the
	 * comma-separated option list.
	 */
	public static List<Mount> parse(List<String> lines) {
		List<Mount> mounts = new ArrayList<>();

		if (lines == null) {
			return mounts;
		}

		for (String line : lines) {
			if (line == null || line.isBlank() || line.startsWith("#")) {
				continue;
			}

			String[] fields = line.trim().split("\\s+");

			if (fields.length < 4) {
				continue;
			}

			Set<String> options = new LinkedHashSet<>();

			for (String option : fields[3].split(",")) {
				if (!option.isEmpty()) {
					options.add(option);
				}
			}

			mounts.add(new Mount(unescape(fields[1]), options));
		}

		return mounts;
	}

	/** Reads and parses a mount table file. An unreadable table is an empty list, never an exception. */
	public static List<Mount> read(Path mountsFile) {
		try {
			return parse(Files.readAllLines(mountsFile, StandardCharsets.UTF_8));
		}
		catch (IOException | RuntimeException e) {
			return new ArrayList<>();
		}
	}

	/** The mount whose mount point is the longest prefix of {@code file}. */
	public static Optional<Mount> longestPrefixMatch(List<Mount> mounts, Path file) {
		if (mounts == null || file == null) {
			return Optional.empty();
		}

		Path target = file.toAbsolutePath().normalize();
		Mount best = null;
		int bestLength = -1;

		for (Mount mount : mounts) {
			Path mountPoint;

			try {
				mountPoint = Path.of(mount.mountPoint()).toAbsolutePath().normalize();
			}
			catch (RuntimeException e) {
				continue;
			}

			if (!target.startsWith(mountPoint)) {
				continue;
			}

			int length = mountPoint.getNameCount();

			if ("/".equals(mountPoint.toString())) {
				length = 0;
			}

			// >= and not >: a later line in the table shadows an earlier one on the same mount point.
			if (length >= bestLength) {
				best = mount;
				bestLength = length;
			}
		}

		return Optional.ofNullable(best);
	}

	/** The options of the mount that holds {@code file}; empty when nothing matches. */
	public static Set<String> optionsFor(List<Mount> mounts, Path file) {
		return longestPrefixMatch(mounts, file)
				.map(Mount::options)
				.orElse(Collections.emptySet());
	}

	/** True when the filesystem holding {@code file} is mounted {@code noexec}. */
	public static boolean isNoexec(List<Mount> mounts, Path file) {
		return optionsFor(mounts, file).contains("noexec");
	}

	/**
	 * Mount points are escaped in octal for space (\040), tab (\011), newline (\012) and
	 * backslash (\134).
	 */
	static String unescape(String field) {
		if (field.indexOf('\\') < 0) {
			return field;
		}

		StringBuilder out = new StringBuilder(field.length());

		for (int i = 0; i < field.length(); i++) {
			char c = field.charAt(i);

			if (c == '\\' && i + 3 < field.length()) {
				String octal = field.substring(i + 1, i + 4);

				try {
					out.append((char) Integer.parseInt(octal, 8));
					i += 3;
					continue;
				}
				catch (NumberFormatException e) {
					// Not an escape after all; fall through and keep the backslash.
				}
			}

			out.append(c);
		}

		return out.toString();
	}
}
