// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Saga IT, LLC

package dev.kastle.webrtc.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The tier-3 write protocol: extract a native library from a jar resource into a host-owned,
 * writable, exec-capable directory, and load it from there.
 *
 * <p>Only one shipped host has a tier 3 — the shaded standalone gateway app. The Mirth plugin never
 * reaches this class: it loads its installed file in place.
 *
 * <p>The staged filename is <strong>content-keyed</strong>
 * ({@code <libname>-<ver>-<os>-<arch>-<sha12>.<ext>}), so a byte change is a different filename and an
 * existing file is never opened for write. That is the whole point: truncating a mapped {@code .so}
 * kills the JVM with {@code SIGBUS}, which is exactly the shape of the
 * {@code Files.copy(..., REPLACE_EXISTING)} this class replaces.
 */
public final class Staging {

	/** How a staged file is handed to the JVM. Injected so tests never need a real dlopen. */
	public interface Loader {
		void load(String absolutePath);
	}

	/** A staged file that has been loaded. */
	public static final class Staged {

		private final Path path;
		private final String sha256;
		private final boolean reused;

		Staged(Path path, String sha256, boolean reused) {
			this.path = path;
			this.sha256 = sha256;
			this.reused = reused;
		}

		public Path path() {
			return path;
		}

		public String sha256() {
			return sha256;
		}

		/** True when the content-keyed file was already on disk and was not rewritten. */
		public boolean reused() {
			return reused;
		}
	}

	/**
	 * The staged file could not be loaded. The staged file has already been deleted, so a noexec
	 * volume never accumulates an unusable 8 MB artefact.
	 */
	public static final class StagedLoadException extends Exception {

		private static final long serialVersionUID = 1L;

		private final transient Path path;

		StagedLoadException(Path path, Throwable cause) {
			super(cause);

			this.path = path;
		}

		/** The path the load was attempted from. The file no longer exists. */
		public Path path() {
			return path;
		}
	}

	private static final Logger LOG = Logger.getLogger(Staging.class.getName());

	private static final String LOCK_FILE = ".native.lock";

	private Staging() {
	}

	/**
	 * Stages {@code resource} into {@code stagingDir} under a content-keyed name and loads it.
	 *
	 * @param stagingDir host-owned directory; created when absent.
	 * @param baseName   {@code <libname>-<ver>-<os>-<arch>}, without the sha or the extension.
	 * @param extension  the library extension including the dot, e.g. {@code .so}.
	 * @param resource   the jar resource; called at most twice (hash, then copy).
	 * @param loader     what performs the load.
	 */
	public static Staged stage(Path stagingDir, String baseName, String extension,
			Supplier<InputStream> resource, Loader loader) throws IOException, StagedLoadException {
		Files.createDirectories(stagingDir);

		// Cross-process, inside the caller's JVM-wide lock: two engines sharing one staging dir must
		// not race on the same content-keyed name.
		try (FileChannel lockChannel = FileChannel.open(stagingDir.resolve(LOCK_FILE),
				StandardOpenOption.CREATE, StandardOpenOption.WRITE);
				FileLock lock = lockChannel.lock()) {
			return stageLocked(stagingDir, baseName, extension, resource, loader);
		}
	}

	private static Staged stageLocked(Path stagingDir, String baseName, String extension,
			Supplier<InputStream> resource, Loader loader) throws IOException, StagedLoadException {
		String sha256 = hash(resource);
		Path target = stagingDir.resolve(baseName + "-" + Sha256.short12(sha256) + extension);
		boolean reused = false;

		if (Files.exists(target)) {
			if (sha256.equals(Sha256.of(target))) {
				reused = true;
			}
			else {
				// Cannot happen by construction: the name IS the content hash.
				LOG.log(Level.WARNING, "staged webrtc native {0} does not match its own content key;"
						+ " staging under a fresh name", target);

				target = stagingDir.resolve(baseName + "-" + Sha256.short12(sha256) + "-"
						+ System.nanoTime() + extension);
			}
		}

		if (!reused) {
			write(target, resource);
		}

		try {
			loader.load(target.toAbsolutePath().toString());
		}
		catch (Throwable t) {
			deleteQuietly(target);

			throw new StagedLoadException(target, t);
		}

		return new Staged(target, sha256, reused);
	}

	private static void write(Path target, Supplier<InputStream> resource) throws IOException {
		Path tmp = target.resolveSibling(target.getFileName() + "." + ProcessHandle.current().pid()
				+ "." + System.nanoTime() + ".tmp");

		try {
			try (InputStream in = open(resource);
					FileChannel out = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW,
							StandardOpenOption.WRITE)) {
				copy(in, Channels.newOutputStream(out));

				out.force(true);
			}

			// ATOMIC_MOVE, never REPLACE_EXISTING: the target name may already be mapped by another
			// process, and replacing a mapped file is the SIGBUS this protocol exists to avoid.
			Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (IOException | RuntimeException e) {
			deleteQuietly(tmp);

			throw e;
		}
	}

	private static String hash(Supplier<InputStream> resource) throws IOException {
		try (InputStream in = open(resource)) {
			return Sha256.of(in);
		}
	}

	private static InputStream open(Supplier<InputStream> resource) throws IOException {
		InputStream in = resource.get();

		if (in == null) {
			throw new IOException("native library resource is not on the classpath");
		}

		return in;
	}

	private static void copy(InputStream in, OutputStream out) throws IOException {
		byte[] buffer = new byte[64 * 1024];
		int read;

		while ((read = in.read(buffer)) != -1) {
			out.write(buffer, 0, read);
		}

		out.flush();
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		}
		catch (IOException | RuntimeException e) {
			// A mapped file cannot be deleted on Windows; nothing further to do here.
			LOG.log(Level.FINE, "could not delete staged webrtc native " + path, e);
		}
	}
}
