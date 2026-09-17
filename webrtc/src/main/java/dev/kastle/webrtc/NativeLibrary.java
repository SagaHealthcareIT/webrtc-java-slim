// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Saga IT, LLC

package dev.kastle.webrtc;

import dev.kastle.webrtc.internal.BinaryHeader;
import dev.kastle.webrtc.internal.MountTable;
import dev.kastle.webrtc.internal.Sha256;
import dev.kastle.webrtc.internal.Staging;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * The single point at which this JVM resolves and loads the WebRTC native library.
 *
 * <p>The host tells us where its library is ({@link #configure(NativeSource)}); we resolve a tier,
 * verify the file structurally, load it once, and memoise the outcome — success or failure — for the
 * life of this classloader. There is no default source and no temp-directory fallback: an
 * unconfigured library fails with {@link NativeFault#NOT_CONFIGURED}.
 *
 * <p>Nothing here runs from a static initializer. A {@code <clinit>} that throws poisons its class
 * for the JVM lifetime, turning one recoverable, reportable fault into a permanent
 * {@code NoClassDefFoundError} on every later touch.
 *
 * <p>Two INFO lines are pinned. Consumers — the plugin e2e harness, the operator runbook — match
 * these strings verbatim rather than restate a regex:
 *
 * <pre>
 * webrtc native resolved tier=&lt;OVERRIDE|INSTALLED&gt; path=&lt;abs&gt; version=&lt;ver&gt; sha256=&lt;12&gt; platform=&lt;os&gt;-&lt;arch&gt;
 * webrtc native loaded   tier=&lt;OVERRIDE|INSTALLED|STAGED&gt; path=&lt;abs&gt; version=&lt;ver&gt; sha256=&lt;12&gt; platform=&lt;os&gt;-&lt;arch&gt;
 * </pre>
 *
 * <p>{@code resolved} is emitted once by {@link #preflight()}, and only for a tier that names a file
 * already on disk: <strong>there is no {@code resolved} line for {@code tier=STAGED}</strong>,
 * because a staged file does not exist until the load itself writes it. {@code loaded} is emitted
 * once by the {@code dlopen} in {@link #ensureLoaded()}, on the caller's thread, for all three
 * tiers. On a faulted host neither line is printed and the host reports the fault instead.
 */
public final class NativeLibrary {

	/** The library's base name, as CI publishes it. */
	public static final String LIBRARY_NAME = "webrtc-java";

	/**
	 * Tier 1 operator override: an absolute path to the library file. Read by
	 * {@link #overrideFromEnvironment()}, which every resolution consults.
	 */
	public static final String OVERRIDE_PROPERTY = "openshare.webrtc.native";

	/** Tier 1 operator override, environment form. Read by {@link #overrideFromEnvironment()}. */
	public static final String OVERRIDE_ENVIRONMENT = "OPENSHARE_WEBRTC_NATIVE";

	private static final Logger LOG = Logger.getLogger(NativeLibrary.class.getName());

	/** The raw JVM message and each tried path are recorded verbatim, truncated to this many chars. */
	private static final int MAX_TEXT = 512;

	// Emitted once by the structural preflight, for a resolution that names a file already on disk —
	// tier 1 and tier 2. A tier-3 host writes and loads its file in one locked step and has nothing
	// to report before that, so "resolved" is never printed with tier=STAGED.
	private static final String VERB_RESOLVED = "resolved";

	// "loaded" is padded so the two pinned lines align; both strings are matched verbatim by the
	// e2e harness and the operator runbook. This one is printed for all three tiers.
	private static final String VERB_LOADED = "loaded  ";

	private static final String SUPPORT = "Send the engine log line to support@saga-it.com.";

	private static final Object LOCK = new Object();      // NOT a future: JNI_OnLoad must never re-enter a join

	private static NativeSource source;                    // guarded by LOCK
	private static LoadResult result;                      // guarded by LOCK; first result wins, success or failure
	private static Resolution resolution;                  // guarded by LOCK; the memoised structural pass
	private static boolean resolvedLogged;                 // guarded by LOCK
	private static Platform platform = Platform.current(); // guarded by LOCK; test seam
	private static Staging.Loader loader = path -> System.load(path); // guarded by LOCK; test seam

	private NativeLibrary() {
	}

	/**
	 * Register the host's source. Must be called before the first ensureLoaded().
	 *
	 * <p>Calling it after a result has been memoised has no effect: a load outcome is a JVM-lifetime
	 * state and the operator-facing remediation says "restart the engine". {@link
	 * NativeFault#NOT_CONFIGURED} is the one exception, and it is not a load outcome — it says a
	 * caller ran ahead of this method, which is a bug in the host's ordering and not a condition of
	 * the machine. It is never memoised, so a correct {@code configure()} that arrives late still
	 * resolves and loads normally.
	 */
	public static void configure(NativeSource s) {
		Objects.requireNonNull(s, "source");

		synchronized (LOCK) {
			source = s;

			if (result == null) {
				resolution = null;
			}
		}
	}

	/**
	 * Structural checks only: resolve the tier-2/tier-1 path, verify the sha256 against the
	 * host's manifest value, and parse the file header for format + architecture. Performs NO
	 * dlopen, allocates no native memory, and can be called on an engine that will never use
	 * WebRTC. Returns a failed LoadResult (memoised) on a structural fault; returns
	 * Optional.empty() when nothing structural is wrong and the load has not been attempted.
	 *
	 * <p>NOT_CONFIGURED is the one result this does not memoise — see {@link #configure}.
	 */
	public static Optional<LoadResult> preflight() {
		synchronized (LOCK) {
			if (result != null) {
				return result.isLoaded() ? Optional.empty() : Optional.of(result);
			}
			if (source == null) {
				return Optional.of(notConfigured());
			}

			Resolution r = resolveMemoised();

			if (r.failure != null) {
				result = r.failure;

				return Optional.of(result);
			}
			// A staged resolution has no file yet — tier 3 writes and loads in one locked step, so
			// its only pinned line is "loaded". "resolved" is a tier-1/tier-2 line by construction.
			if (r.file != null && !resolvedLogged) {
				LOG.info(line(VERB_RESOLVED, r.tier, r.file, source.version(), r.sha256));

				resolvedLogged = true;
			}

			return Optional.empty();
		}
	}

	/**
	 * Load now, or return the memoised result. On failure the LoadResult is memoised and a
	 * NativeUnavailableException carrying it is thrown for the life of this classloader: a failed
	 * load is a JVM-lifetime state, and the operator-facing remediation says "restart the engine".
	 *
	 * <p>NOT_CONFIGURED is the one result this does not memoise — see {@link #configure}.
	 */
	public static LoadResult ensureLoaded() {
		LoadResult r;

		synchronized (LOCK) {
			if (result == null && source != null) {
				result = doLoad();
			}

			r = result == null ? notConfigured() : result;
		}

		if (!r.isLoaded()) {
			throw new NativeUnavailableException(r);
		}

		return r;
	}

	/** The memoised result, if configure()+preflight()/ensureLoaded() have run. Per-classloader. */
	public static Optional<LoadResult> status() {
		synchronized (LOCK) {
			return Optional.ofNullable(result);
		}
	}

	/**
	 * The tier-1 operator override this JVM was started with: {@code -Dopenshare.webrtc.native}, or
	 * failing that the {@code OPENSHARE_WEBRTC_NATIVE} environment variable.
	 *
	 * <p>{@link #resolve} consults this whenever the host's {@link NativeSource#overridePath()} is
	 * empty, so the escape hatch every remediation string names — and the one the plugin's
	 * no-tier-3 decision rests on — works in every host, without each host reimplementing it
	 * identically and one of them getting it wrong. A host that resolves the override itself still
	 * wins: what its {@code NativeSource} returns is used as-is.
	 */
	public static Optional<Path> overrideFromEnvironment() {
		return overrideOf(System.getProperty(OVERRIDE_PROPERTY), System.getenv(OVERRIDE_ENVIRONMENT));
	}

	// --- pure, package-visible seams for tests ---------------------------------------------------

	/** The property wins over the environment; a blank value is not an override. */
	static Optional<Path> overrideOf(String property, String environment) {
		String value = property == null || property.isBlank() ? environment : property;

		if (value == null || value.isBlank()) {
			return Optional.empty();
		}

		return Optional.of(Path.of(value.trim()));
	}

	/** Tier selection plus the structural checks; reads files, changes nothing. */
	static Resolution resolve(NativeSource s) {
		Platform p = platform;
		List<String> tried = new ArrayList<>();

		// Precedence is the NativeFault declaration order: a platform we do not ship for is decided
		// before anything about a particular file.
		if (!p.isShipped()) {
			return Resolution.failed(fault(NativeFault.PLATFORM_NOT_SHIPPED, new Context(null, p, tried)));
		}

		// The host may resolve the override itself; when it does not, tier 1 is still honoured here,
		// so the escape hatch our remediation strings name works in every host.
		Optional<Path> override = s.overridePath().or(NativeLibrary::overrideFromEnvironment);

		if (override.isPresent()) {
			// An override the operator set and we cannot use is a fault, never a silent fallthrough
			// to a tier they did not ask for.
			return checkFile(LoadResult.Tier.OVERRIDE, override.get(), Optional.empty(), p, tried);
		}

		Optional<Path> installed = s.installedFile();

		if (installed.isPresent()) {
			return checkFile(LoadResult.Tier.INSTALLED, installed.get(), s.installedSha256(), p, tried);
		}

		Optional<Supplier<InputStream>> resource = s.jarResource();
		Optional<Path> stagingDir = s.stagingDir();

		if (resource.isPresent() && stagingDir.isPresent()) {
			return Resolution.staged(resource.get(), stagingDir.get().toAbsolutePath().normalize(), tried);
		}

		return Resolution.failed(fault(NativeFault.PLATFORM_NOT_SHIPPED, new Context(null, p, tried)));
	}

	/**
	 * Classifies a load failure. Structural first (our own header parse, the mount table), message
	 * second, and only on locale-stable tokens — every glibc and Win32 string is localized, so the
	 * raw text is data, never a branch key.
	 *
	 * <p>The walk is the declaration order of {@link NativeFault}.
	 */
	static NativeFault classify(String jvmMessage, Path file, Platform p) {
		String message = jvmMessage == null ? "" : jvmMessage;

		if (!p.isShipped()) {
			return NativeFault.PLATFORM_NOT_SHIPPED;
		}
		if (file != null && !Files.isReadable(file)) {
			return NativeFault.MISSING_FILE;
		}

		BinaryHeader header = header(file);

		// Declared order (see NativeFault): architecture before format. A library built for
		// another machine and a library in another container format both fail to load, but their
		// remediations differ, and on Windows neither produces a distinguishable JVM message — so
		// read the file rather than the message wherever the file can be read.
		if (header != null) {
			String fileArch = header.arch();

			if (!fileArch.isEmpty() && !fileArch.equals(p.osArch().orElse(""))) {
				return NativeFault.ARCH_MISMATCH;
			}
			if (header.format() != expectedFormat(p)) {
				return NativeFault.WRONG_FORMAT;
			}
		}

		// hotspot appends this when the loader rejects the machine type; the suffix itself is not
		// localized even where the leading text is. Still needed for the case where the file could
		// not be read here but the loader got far enough to reject it.
		if (message.contains("(Possible cause:")) {
			return NativeFault.ARCH_MISMATCH;
		}
		if (message.contains("already loaded in another classloader")
				|| message.contains("is being loaded in another classloader")) {
			return NativeFault.HOST_LOADER_CONFLICT;
		}
		if (message.contains("GLIBC_2.")) {
			return NativeFault.LIBC_TOO_OLD;
		}
		if (p.isLinux() && file != null
				&& MountTable.isNoexec(MountTable.read(procMounts(p)), file)) {
			return NativeFault.NOEXEC_OR_SELINUX;
		}
		if (p.isWindows() && file != null && !namesAnotherModule(message, file)) {
			return NativeFault.WINDOWS_APP_CONTROL;
		}

		return NativeFault.UNKNOWN;
	}

	/** Clears source + result. */
	static void resetForTests() {
		synchronized (LOCK) {
			source = null;
			result = null;
			resolution = null;
			resolvedLogged = false;
			platform = Platform.current();
			loader = path -> System.load(path);
		}
	}

	/** Test seam: the platform every resolution and classification is evaluated against. */
	static void platformForTests(Platform p) {
		synchronized (LOCK) {
			platform = Objects.requireNonNull(p, "platform");
		}
	}

	/** Test seam: what performs the load, so no test needs a real dlopen. */
	static void loaderForTests(Staging.Loader l) {
		synchronized (LOCK) {
			loader = Objects.requireNonNull(l, "loader");
		}
	}

	// --- resolution -------------------------------------------------------------------------------

	/** The outcome of the structural pass: a file and tier to load, a staging job, or a failure. */
	static final class Resolution {

		final LoadResult.Tier tier;
		final Path file;
		final String sha256;
		final Supplier<InputStream> resource;
		final Path stagingDir;
		final List<String> tried;
		final LoadResult failure;

		private Resolution(LoadResult.Tier tier, Path file, String sha256, Supplier<InputStream> resource,
				Path stagingDir, List<String> tried, LoadResult failure) {
			this.tier = tier;
			this.file = file;
			this.sha256 = sha256;
			this.resource = resource;
			this.stagingDir = stagingDir;
			this.tried = tried == null ? List.of() : List.copyOf(tried);
			this.failure = failure;
		}

		static Resolution file(LoadResult.Tier tier, Path file, String sha256, List<String> tried) {
			return new Resolution(tier, file, sha256, null, null, tried, null);
		}

		static Resolution staged(Supplier<InputStream> resource, Path stagingDir, List<String> tried) {
			return new Resolution(LoadResult.Tier.STAGED, null, null, resource, stagingDir, tried, null);
		}

		static Resolution failed(LoadResult failure) {
			return new Resolution(null, null, null, null, null, failure.triedPaths(), failure);
		}
	}

	private static Resolution resolveMemoised() {
		if (resolution == null) {
			resolution = resolve(source);
		}

		return resolution;
	}

	private static Resolution checkFile(LoadResult.Tier tier, Path candidate, Optional<String> expectedSha,
			Platform p, List<String> tried) {
		Path file = candidate.toAbsolutePath().normalize();

		tried.add(file.toString());

		Context context = new Context(file, p, tried);

		if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
			return Resolution.failed(fault(NativeFault.MISSING_FILE, context));
		}

		String sha;
		BinaryHeader header;

		try {
			sha = Sha256.of(file);
			header = BinaryHeader.read(file);
		}
		catch (IOException | RuntimeException e) {
			context.raw = truncate(messageOf(e));

			return Resolution.failed(fault(NativeFault.MISSING_FILE, context));
		}

		context.actualSha = sha;
		context.header = header;

		if (expectedSha.isPresent() && !expectedSha.get().equalsIgnoreCase(sha)) {
			context.expectedSha = expectedSha.get();

			// Terminal: a file whose bytes are not the bytes we shipped is never loaded, and no
			// further tier is attempted.
			return Resolution.failed(fault(NativeFault.CORRUPT_INSTALL, context));
		}

		String fileArch = header.arch();

		if (!fileArch.isEmpty() && !fileArch.equals(p.osArch().orElse(""))) {
			return Resolution.failed(fault(NativeFault.ARCH_MISMATCH, context));
		}

		if (header.format() != expectedFormat(p)) {
			return Resolution.failed(fault(NativeFault.WRONG_FORMAT, context));
		}

		return Resolution.file(tier, file, sha, tried);
	}

	// --- loading ----------------------------------------------------------------------------------

	/** Called with the lock held and a non-null source; NOT_CONFIGURED is decided by the caller. */
	private static LoadResult doLoad() {
		NativeSource s = source;
		Platform p = platform;
		Resolution r = resolveMemoised();

		if (r.failure != null) {
			return r.failure;
		}
		if (r.tier == LoadResult.Tier.STAGED) {
			return loadStaged(s, p, r);
		}

		try {
			loader.load(r.file.toString());
		}
		catch (Throwable t) {
			// Classify on the whole message, truncate only for storage: the one locale-stable token
			// in a JVM message ("GLIBC_2.", "(Possible cause:", "already loaded in another
			// classloader") sits after the library path, which on a deep install tree is already
			// longer than MAX_TEXT. Classifying the truncated text drops it and degrades the fault
			// to the platform's catch-all, with remediation that is then simply wrong.
			String raw = messageOf(t);
			Context context = new Context(r.file, p, r.tried);

			context.raw = truncate(raw);

			return fault(classify(raw, r.file, p), context);
		}

		LOG.info(line(VERB_LOADED, r.tier, r.file, s.version(), r.sha256));

		return LoadResult.loaded(r.tier, r.file, s.version(), r.sha256);
	}

	private static LoadResult loadStaged(NativeSource s, Platform p, Resolution r) {
		String base = LIBRARY_NAME + "-" + s.version() + "-" + p.describe();
		List<String> tried = new ArrayList<>(r.tried);

		// Staging deletes the staged file on a failed load, so the classification — which reads the
		// file's own header and the mount it sits on — has to happen while it is still there.
		NativeFault[] classified = new NativeFault[1];

		Staging.Loader classifying = path -> {
			try {
				loader.load(path);
			}
			catch (Throwable t) {
				classified[0] = classify(messageOf(t), Path.of(path), p);

				throw t;
			}
		};

		try {
			Staging.Staged staged = Staging.stage(r.stagingDir, base, p.libraryExtension(),
					r.resource, classifying);

			LOG.info(line(VERB_LOADED, LoadResult.Tier.STAGED, staged.path(), s.version(), staged.sha256()));

			return LoadResult.loaded(LoadResult.Tier.STAGED, staged.path(), s.version(), staged.sha256());
		}
		catch (Staging.StagedLoadException e) {
			String raw = messageOf(e.getCause());

			tried.add(e.path().toString());

			Context context = new Context(e.path(), p, tried);

			context.raw = truncate(raw);

			return fault(classified[0] == null
					? classify(raw, e.path(), p)
					: classified[0], context);
		}
		catch (IOException | RuntimeException e) {
			tried.add(r.stagingDir.toString());

			Context context = new Context(r.stagingDir, p, tried);

			context.raw = truncate(messageOf(e));

			return LoadResult.failed(NativeFault.UNKNOWN,
					truncate("WebRTC native library could not be staged into " + r.stagingDir
							+ ": " + context.raw),
					"Point this host at a writable, exec-capable directory, or set -D"
							+ OVERRIDE_PROPERTY + "=<abs path to the library>. "
							+ "Restart after remediation. " + SUPPORT,
					context.tried, context.raw);
		}
	}

	// --- messages ---------------------------------------------------------------------------------

	/** Everything a message and a remediation may need. Mutable and short-lived, never published. */
	private static final class Context {

		final Path file;
		final Platform platform;
		final List<String> tried;

		String raw;
		String expectedSha;
		String actualSha;
		BinaryHeader header;

		Context(Path file, Platform platform, List<String> tried) {
			this.file = file;
			this.platform = platform;
			this.tried = truncateAll(tried);
		}

		String path() {
			return file == null ? "<unresolved>" : file.toString();
		}
	}

	private static LoadResult notConfigured() {
		return fault(NativeFault.NOT_CONFIGURED, new Context(null, platform, List.of()));
	}

	private static LoadResult fault(NativeFault f, Context c) {
		return LoadResult.failed(f, truncate(messageFor(f, c)), remediationFor(f, c), c.tried, c.raw);
	}

	private static String messageFor(NativeFault f, Context c) {
		switch (f) {
			case NOT_CONFIGURED:
				return "WebRTC native library was never configured: no NativeSource was registered "
						+ "before the first use.";
			case PLATFORM_NOT_SHIPPED:
				return "No WebRTC native library is shipped for " + c.platform.describe()
						+ " (libc " + c.platform.libc().name().toLowerCase(Locale.ROOT) + ").";
			case MISSING_FILE:
				return "WebRTC native library is missing or unreadable at " + c.path()
						+ (c.raw == null ? "" : " (" + c.raw + ")");
			case CORRUPT_INSTALL:
				return "WebRTC native library at " + c.path() + " does not match the manifest "
						+ "(expected " + Sha256.short12(c.expectedSha) + ", found "
						+ Sha256.short12(c.actualSha) + ").";
			case ARCH_MISMATCH:
				return "WebRTC native library at " + c.path() + " is built for " + fileArch(c)
						+ ", this engine runs " + c.platform.describe() + ".";
			case WRONG_FORMAT:
				return "WebRTC native library at " + c.path() + " is a " + fileFormat(c)
						+ " binary, this engine needs " + expectedFormat(c.platform) + ".";
			case HOST_LOADER_CONFLICT:
				return "WebRTC native library at " + c.path()
						+ " is already held by another classloader in this JVM.";
			case LIBC_TOO_OLD:
				return "WebRTC native library at " + c.path()
						+ " needs a newer glibc than this host provides.";
			case NOEXEC_OR_SELINUX:
				return "WebRTC native library at " + c.path() + " could not be executed by this host.";
			case WINDOWS_APP_CONTROL:
				return "WebRTC native library at " + c.path()
						+ " was blocked by Windows application control.";
			case UNKNOWN:
			default:
				return "WebRTC native library at " + c.path() + " could not be loaded: "
						+ (c.raw == null ? "no JVM message" : c.raw)
						+ "; tried " + String.join(", ", c.tried);
		}
	}

	private static String remediationFor(NativeFault f, Context c) {
		switch (f) {
			case NOT_CONFIGURED:
				return "internal error; report with the log line (should be unreachable in shipped hosts)";
			case PLATFORM_NOT_SHIPPED:
				return "OpenShare ships native libraries for "
						+ String.join(", ", Platform.SHIPPED_CLASSIFIERS) + " on glibc 2.18 or newer. "
						+ "This host is " + c.platform.describe() + "/"
						+ c.platform.libc().name().toLowerCase(Locale.ROOT)
						+ ". Use a supported platform (on Alpine, use an Ubuntu-based image).";
			case MISSING_FILE:
				return "Reinstall the Gateway Connector plugin; " + c.path()
						+ " is missing. Restart the engine after reinstalling.";
			case CORRUPT_INSTALL:
				return "The native library at " + c.path() + " does not match the plugin's manifest "
						+ "(expected " + Sha256.short12(c.expectedSha) + ", found "
						+ Sha256.short12(c.actualSha) + "). Reinstall the plugin and restart the engine.";
			case ARCH_MISMATCH:
			case WRONG_FORMAT:
				return "The installed native is for " + fileArch(c) + "; this engine runs "
						+ c.platform.describe() + ". Reinstall the plugin for this platform and restart.";
			case HOST_LOADER_CONFLICT:
				return "Restart the engine; the library is held by another classloader in this JVM.";
			case LIBC_TOO_OLD:
				return "This host's glibc is older than 2.18 (RHEL 8+, Debian 8+, Ubuntu 14.04+). "
						+ "RHEL/CentOS 7 is not supported.";
			case NOEXEC_OR_SELINUX:
				return "The filesystem holding " + c.path() + " refuses to execute code. Check "
						+ "findmnt -T " + parentOf(c) + " and systemctl show -p "
						+ "NoExecPaths,ProtectSystem <unit> (a unit-level NoExecPaths= looks like an exec "
						+ "mount from your shell)"
						+ (selinuxEnforcing(c.platform)
								? ", and, on SELinux, ausearch -m avc (needs auditd running)"
								: "")
						+ ". Or set -D" + OVERRIDE_PROPERTY + "=<abs path on an exec filesystem>. "
						+ "Restart the engine after remediation.";
			case WINDOWS_APP_CONTROL:
				return "Windows application control blocked " + c.path() + ". Add an AppLocker or WDAC "
						+ "DLL rule for the install directory, or a publisher rule for the library's "
						+ "code-signing certificate (read its subject with Get-AuthenticodeSignature '"
						+ c.path() + "'). Also check a Defender ASR exclusion for the install directory. "
						+ "Event logs: AppLocker EXE-and-DLL 8003/8004, CodeIntegrity/Operational "
						+ "3076/3077, Defender/Operational 1121. Restart the engine after remediation.";
			case UNKNOWN:
			default:
				return SUPPORT;
		}
	}

	// --- helpers ----------------------------------------------------------------------------------

	private static String line(String verb, LoadResult.Tier tier, Path path, String version, String sha256) {
		return "webrtc native " + verb + " tier=" + tier + " path=" + path + " version=" + version
				+ " sha256=" + Sha256.short12(sha256) + " platform=" + platform.describe();
	}

	private static BinaryHeader.Format expectedFormat(Platform p) {
		if (p.isWindows()) {
			return BinaryHeader.Format.PE;
		}
		if (p.isMacOs()) {
			return BinaryHeader.Format.MACHO;
		}

		return BinaryHeader.Format.ELF;
	}

	private static BinaryHeader header(Path file) {
		if (file == null) {
			return null;
		}

		try {
			return BinaryHeader.read(file);
		}
		catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** The architecture read from the file, or a neutral phrase when it could not be read. */
	private static String fileArch(Context c) {
		if (c.header == null) {
			c.header = header(c.file);
		}

		String arch = c.header == null ? "" : c.header.arch();

		return arch.isEmpty() || "other".equals(arch) ? "another architecture" : arch;
	}

	private static String fileFormat(Context c) {
		if (c.header == null) {
			c.header = header(c.file);
		}

		return c.header == null ? "unrecognised" : c.header.format().name();
	}

	private static String parentOf(Context c) {
		if (c.file == null || c.file.getParent() == null) {
			return c.path();
		}

		return c.file.getParent().toString();
	}

	private static Path procMounts(Platform p) {
		return p.fsRoot().resolve("proc").resolve("self").resolve("mounts");
	}

	/**
	 * SELinux Enforcing is evidence of nothing on its own — it reads 1 on every stock RHEL/Fedora
	 * host and a load from a correctly-labelled file succeeds there. It is read only to decide
	 * whether the remediation is worth an ausearch sentence.
	 */
	private static boolean selinuxEnforcing(Platform p) {
		Path enforce = p.fsRoot().resolve("sys").resolve("fs").resolve("selinux").resolve("enforce");

		try {
			return "1".equals(Files.readString(enforce, StandardCharsets.UTF_8).trim());
		}
		catch (IOException | RuntimeException e) {
			return false;
		}
	}

	/**
	 * True when the message names a module that is not our own file — the classic
	 * missing-dependent-DLL case, whose remediation has nothing to do with application control.
	 */
	private static boolean namesAnotherModule(String message, Path file) {
		String lower = message.toLowerCase(Locale.ROOT);

		if (lower.contains("dependent librar")) {
			return true;
		}

		String own = file.getFileName().toString().toLowerCase(Locale.ROOT);
		int from = 0;

		while (true) {
			int dll = lower.indexOf(".dll", from);

			if (dll < 0) {
				return false;
			}

			int start = dll;

			while (start > 0 && isModuleNameChar(lower.charAt(start - 1))) {
				start--;
			}

			String named = lower.substring(start, dll + 4);

			if (!named.equals(own)) {
				return true;
			}

			from = dll + 4;
		}
	}

	private static boolean isModuleNameChar(char c) {
		return Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' || c == '+';
	}

	private static String messageOf(Throwable t) {
		if (t == null) {
			return "";
		}

		String message = t.getMessage();

		return message == null || message.isBlank() ? t.toString() : message;
	}

	private static String truncate(String text) {
		if (text == null) {
			return null;
		}

		return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT);
	}

	private static List<String> truncateAll(List<String> texts) {
		if (texts == null) {
			return List.of();
		}

		List<String> out = new ArrayList<>(texts.size());

		for (String text : texts) {
			out.add(truncate(text));
		}

		return out;
	}
}
