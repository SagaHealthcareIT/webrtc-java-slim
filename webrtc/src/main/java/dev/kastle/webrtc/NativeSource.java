// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Saga IT, LLC

package dev.kastle.webrtc;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Where this host keeps its native library. Registered once with
 * {@link NativeLibrary#configure(NativeSource)} before any WebRTC use.
 *
 * <p>Tier order is override -&gt; installed -&gt; staged. A host that ships the
 * library as a loose file next to itself (the Mirth plugin) implements
 * {@link #installedFile()} and returns empty for the staging pair. A host whose
 * library is only a jar resource (the shaded gateway app) implements
 * {@link #jarResource()} + {@link #stagingDir()}. Returning empty for all four
 * is a configuration error, not a licence to use java.io.tmpdir.
 */
public interface NativeSource {

	/**
	 * Tier 1: operator override, absolute path to the library file.
	 *
	 * <p>A host need not implement this. Returning empty still honours
	 * {@code -Dopenshare.webrtc.native} and {@code OPENSHARE_WEBRTC_NATIVE}, which
	 * {@link NativeLibrary#overrideFromEnvironment()} reads for every host; implement it only to
	 * point at a path this host resolves some other way, in which case what it returns wins.
	 */
	Optional<Path> overridePath();

	/** Tier 2: library shipped on disk next to the host. Loaded IN PLACE. */
	Optional<Path> installedFile();

	/** Expected sha256 (lowercase hex) of {@link #installedFile()}, when the host knows it. */
	Optional<String> installedSha256();

	/** Tier 3 bytes. Present only for hosts whose native is a jar resource. */
	Optional<Supplier<InputStream>> jarResource();

	/** Tier 3 destination: a host-owned, writable, exec-capable directory. */
	Optional<Path> stagingDir();

	/** For the log line and the staged filename, e.g. "1.3.0". */
	String version();
}
