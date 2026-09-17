module dev.kastle.webrtc {
	// NativeLibrary emits the two pinned resolution log lines through java.util.logging; the fork has
	// no logging dependency of its own and must not grow one.
	requires java.logging;

	exports dev.kastle.webrtc;
	exports dev.kastle.webrtc.logging;
}