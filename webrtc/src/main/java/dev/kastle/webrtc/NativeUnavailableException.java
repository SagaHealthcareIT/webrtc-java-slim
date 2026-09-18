// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Saga IT, LLC

package dev.kastle.webrtc;

/**
 * Thrown at every point that needs the WebRTC native library while it is unavailable.
 *
 * <p>The carried {@link LoadResult} is memoised for the life of the classloader: a failed load is a
 * JVM-lifetime state, and the operator-facing remediation says "restart the engine". Callers report
 * {@link LoadResult#message()} and {@link LoadResult#remediation()} — never a stack trace.
 *
 * <p>The one fault that is <em>not</em> memoised is {@link NativeFault#NOT_CONFIGURED}, which
 * reports a host that called before {@link NativeLibrary#configure(NativeSource)} rather than
 * anything about the machine; a later correct {@code configure()} loads normally.
 */
public class NativeUnavailableException extends IllegalStateException {

	private static final long serialVersionUID = 1L;

	private final transient LoadResult result;

	public NativeUnavailableException(LoadResult result) {
		super(result.message() + " · " + result.remediation());

		this.result = result;
	}

	/** The classified failure. Never null on an instance this JVM threw. */
	public LoadResult result() {
		return result;
	}
}
