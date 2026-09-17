// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Saga IT, LLC

package dev.kastle.webrtc;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

/** A {@link NativeSource} whose four tiers are set one at a time, for the resolver tests. */
final class FakeNativeSource implements NativeSource {

	private Path overridePath;
	private Path installedFile;
	private String installedSha256;
	private Supplier<InputStream> jarResource;
	private Path stagingDir;
	private String version = "1.3.0-test";

	static FakeNativeSource empty() {
		return new FakeNativeSource();
	}

	FakeNativeSource override(Path path) {
		this.overridePath = path;

		return this;
	}

	FakeNativeSource installed(Path path) {
		this.installedFile = path;

		return this;
	}

	FakeNativeSource installedSha(String sha256) {
		this.installedSha256 = sha256;

		return this;
	}

	FakeNativeSource resource(byte[] bytes) {
		this.jarResource = () -> new ByteArrayInputStream(bytes);

		return this;
	}

	FakeNativeSource resource(Supplier<InputStream> supplier) {
		this.jarResource = supplier;

		return this;
	}

	FakeNativeSource staging(Path dir) {
		this.stagingDir = dir;

		return this;
	}

	FakeNativeSource version(String version) {
		this.version = version;

		return this;
	}

	@Override
	public Optional<Path> overridePath() {
		return Optional.ofNullable(overridePath);
	}

	@Override
	public Optional<Path> installedFile() {
		return Optional.ofNullable(installedFile);
	}

	@Override
	public Optional<String> installedSha256() {
		return Optional.ofNullable(installedSha256);
	}

	@Override
	public Optional<Supplier<InputStream>> jarResource() {
		return Optional.ofNullable(jarResource);
	}

	@Override
	public Optional<Path> stagingDir() {
		return Optional.ofNullable(stagingDir);
	}

	@Override
	public String version() {
		return version;
	}
}
