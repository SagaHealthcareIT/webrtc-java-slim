// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Saga IT, LLC

package dev.kastle.webrtc;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of resolving and loading the WebRTC native library: either the library the JVM
 * now holds, or a classified, operator-facing description of why it does not.
 *
 * <p>Immutable, and memoised for the life of the classloader by {@link NativeLibrary}.
 */
public final class LoadResult {

	/** Which resolution tier produced this result. */
	public enum Tier {
		/** Operator override: {@code -Dopenshare.webrtc.native=<abs file>}. */
		OVERRIDE,
		/** A loose file shipped next to the host, loaded in place. */
		INSTALLED,
		/** Extracted from a jar resource into a host-owned directory. */
		STAGED
	}

	private final boolean loaded;
	private final Tier tier;
	private final Path path;
	private final String version;
	private final String sha256;
	private final NativeFault fault;
	private final String message;
	private final String remediation;
	private final List<String> triedPaths;
	private final String rawJvmMessage;

	private LoadResult(boolean loaded, Tier tier, Path path, String version, String sha256,
			NativeFault fault, String message, String remediation, List<String> triedPaths,
			String rawJvmMessage) {
		this.loaded = loaded;
		this.tier = tier;
		this.path = path;
		this.version = version == null ? "" : version;
		this.sha256 = sha256;
		this.fault = fault;
		this.message = message == null ? "" : message;
		this.remediation = remediation == null ? "" : remediation;
		this.triedPaths = triedPaths == null
				? Collections.emptyList()
				: List.copyOf(triedPaths);
		this.rawJvmMessage = rawJvmMessage;
	}

	/** The library is loaded in this JVM. */
	public static LoadResult loaded(Tier tier, Path path, String version, String sha256) {
		Objects.requireNonNull(tier, "tier");
		Objects.requireNonNull(path, "path");

		return new LoadResult(true, tier, path, version, sha256, null,
				"WebRTC native library loaded from " + path,
				"", List.of(path.toString()), null);
	}

	/** The library is not available, for the given classified reason. */
	public static LoadResult failed(NativeFault fault, String message, String remediation,
			List<String> triedPaths, String rawJvmMessage) {
		Objects.requireNonNull(fault, "fault");

		return new LoadResult(false, null, null, "", null, fault, message, remediation,
				triedPaths, rawJvmMessage);
	}

	public boolean isLoaded() {
		return loaded;
	}

	public Optional<Tier> tier() {
		return Optional.ofNullable(tier);
	}

	public Optional<Path> path() {
		return Optional.ofNullable(path);
	}

	/** The host's library version, or the empty string when the load never got far enough to know it. */
	public String version() {
		return version;
	}

	public Optional<String> sha256() {
		return Optional.ofNullable(sha256);
	}

	public Optional<NativeFault> fault() {
		return Optional.ofNullable(fault);
	}

	/** Operator-facing, single line, no stack trace. */
	public String message() {
		return message;
	}

	public String remediation() {
		return remediation;
	}

	/** Absolute paths tried, in tier order; never null. */
	public List<String> triedPaths() {
		return triedPaths;
	}

	/** Raw (possibly localized) JVM text; data, never a branch key. */
	public Optional<String> rawJvmMessage() {
		return Optional.ofNullable(rawJvmMessage);
	}

	@Override
	public String toString() {
		return loaded
				? "LoadResult[loaded tier=" + tier + " path=" + path + " version=" + version + "]"
				: "LoadResult[" + fault + ": " + message + "]";
	}
}
