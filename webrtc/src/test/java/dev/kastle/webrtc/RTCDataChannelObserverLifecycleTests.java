// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Saga IT, LLC

package dev.kastle.webrtc;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

/**
 * The native side holds a global reference to the registered
 * {@link RTCDataChannelObserver}. It must let go when the observer is
 * replaced, unregistered or the channel is disposed, including when that
 * happens inside one of the observer's own callbacks. Before 1.4.1 the native
 * observer was never freed, so every observer, and everything it reached,
 * stayed reachable for the life of the process.
 * <p>
 * Each observer is created in a helper that returns only a weak reference, so
 * nothing on the test's stack keeps it alive.
 */
class RTCDataChannelObserverLifecycleTests extends TestBase {

	@Test
	void unregisteredObserverIsReleased() {
		RTCPeerConnection pc = factory.createPeerConnection(new RTCConfiguration(), new NoopPeerConnectionObserver());
		RTCDataChannel channel = pc.createDataChannel("unregister", new RTCDataChannelInit());

		WeakReference<RTCDataChannelObserver> observer = register(channel);
		assertReachableAfterGc(observer);
		channel.unregisterObserver();

		awaitCollected(observer, "unregistered observer");

		channel.dispose();
		pc.close();
	}

	@Test
	void disposingTheChannelReleasesItsObserver() {
		RTCPeerConnection pc = factory.createPeerConnection(new RTCConfiguration(), new NoopPeerConnectionObserver());
		RTCDataChannel channel = pc.createDataChannel("dispose", new RTCDataChannelInit());

		WeakReference<RTCDataChannelObserver> observer = register(channel);
		assertReachableAfterGc(observer);
		channel.dispose();

		awaitCollected(observer, "observer of a disposed channel");

		pc.close();
	}

	@Test
	void replacedObserverIsReleased() {
		RTCPeerConnection pc = factory.createPeerConnection(new RTCConfiguration(), new NoopPeerConnectionObserver());
		RTCDataChannel channel = pc.createDataChannel("replace", new RTCDataChannelInit());

		WeakReference<RTCDataChannelObserver> first = register(channel);
		WeakReference<RTCDataChannelObserver> second = register(channel);

		awaitCollected(first, "replaced observer");
		assertReachableAfterGc(second);

		channel.unregisterObserver();
		awaitCollected(second, "unregistered observer");

		channel.dispose();
		pc.close();
	}

	/**
	 * Unregistering from inside onMessage runs on the libwebrtc signaling
	 * thread while that observer's callback is on the stack: the native
	 * observer must be freed only after the callback returns, and still be
	 * freed.
	 */
	@Test
	void observerUnregisteredInsideItsCallbackIsReleased() throws Exception {
		awaitReleasedInsideCallback(RTCDataChannel::unregisterObserver, true);
	}

	/** As above, disposing the channel itself from inside onMessage. */
	@Test
	void channelDisposedInsideItsObserverCallbackReleasesTheObserver() throws Exception {
		awaitReleasedInsideCallback(RTCDataChannel::dispose, false);
	}

	private void awaitReleasedInsideCallback(Consumer<RTCDataChannel> teardown, boolean disposeAfterwards)
			throws Exception {
		ChannelPeer caller = new ChannelPeer(factory);
		ChannelPeer callee = new ChannelPeer(factory);

		caller.setRemotePeerConnection(callee);
		callee.setRemotePeerConnection(caller);

		callee.setRemoteDescription(caller.createOffer());
		caller.setRemoteDescription(callee.createAnswer());

		caller.waitUntilConnected();
		callee.waitUntilConnected();

		RTCDataChannel remote = callee.awaitRemoteChannel();
		CountDownLatch tornDown = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();

		WeakReference<RTCDataChannelObserver> observer = registerTearingDown(remote, teardown, tornDown, failure);

		caller.awaitLocalChannelOpen();
		caller.local.send(new RTCDataChannelBuffer(ByteBuffer.wrap(new byte[] { 1, 2, 3 }), true));

		assertTrue(tornDown.await(5, TimeUnit.SECONDS), "onMessage should run within 5 s");
		assertNull(failure.get(), () -> "teardown inside the callback threw: " + failure.get());

		awaitCollected(observer, "observer released inside its own callback");

		if (disposeAfterwards) {
			remote.dispose();
		}
		caller.local.dispose();
		caller.close();
		callee.close();
	}

	private static WeakReference<RTCDataChannelObserver> register(RTCDataChannel channel) {
		RTCDataChannelObserver observer = new NoopDataChannelObserver();
		channel.registerObserver(observer);
		return new WeakReference<>(observer);
	}

	private static WeakReference<RTCDataChannelObserver> registerTearingDown(RTCDataChannel channel,
			Consumer<RTCDataChannel> teardown, CountDownLatch tornDown, AtomicReference<Throwable> failure) {
		RTCDataChannelObserver observer = new NoopDataChannelObserver() {

			@Override
			public void onMessage(RTCDataChannelBuffer buffer) {
				// Anything thrown out of a libwebrtc callback aborts the JVM.
				try {
					if (tornDown.getCount() > 0) {
						teardown.accept(channel);
					}
				}
				catch (Throwable t) {
					failure.set(t);
				}
				finally {
					tornDown.countDown();
				}
			}
		};
		channel.registerObserver(observer);
		return new WeakReference<>(observer);
	}

	/**
	 * The native side alone keeps a registered observer alive: if it did not,
	 * the "released" checks would pass for the wrong reason.
	 */
	private static void assertReachableAfterGc(WeakReference<?> ref) {
		for (int i = 0; i < 3; i++) {
			System.gc();
		}
		assertNotNull(ref.get(), "a registered observer must stay reachable");
	}

	private static void awaitCollected(WeakReference<?> ref, String what) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

		while (ref.get() != null && System.nanoTime() < deadline) {
			System.gc();
			try {
				Thread.sleep(50);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}

		assertNull(ref.get(), what + " still reachable");
	}

	private static class NoopDataChannelObserver implements RTCDataChannelObserver {

		@Override
		public void onBufferedAmountChange(long previousAmount) { }

		@Override
		public void onStateChange() { }

		@Override
		public void onMessage(RTCDataChannelBuffer buffer) { }
	}

	private static class NoopPeerConnectionObserver implements PeerConnectionObserver {

		@Override
		public void onIceCandidate(RTCIceCandidate candidate) { }
	}

	private static class ChannelPeer extends TestPeerConnection {

		private static final String LABEL = "lifecycle";

		final RTCDataChannel local;

		private final CountDownLatch remoteChannel = new CountDownLatch(1);

		private volatile RTCDataChannel remote;

		ChannelPeer(PeerConnectionFactory factory) {
			super(factory);

			local = getPeerConnection().createDataChannel(LABEL, new RTCDataChannelInit());
		}

		@Override
		public void onDataChannel(RTCDataChannel dataChannel) {
			// TestPeerConnection also opens a "dummy" channel.
			if (LABEL.equals(dataChannel.getLabel())) {
				remote = dataChannel;
				remoteChannel.countDown();
			}
		}

		RTCDataChannel awaitRemoteChannel() throws InterruptedException {
			assertTrue(remoteChannel.await(5, TimeUnit.SECONDS), "remote data channel should arrive within 5 s");
			return remote;
		}

		void awaitLocalChannelOpen() throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (local.getState() != RTCDataChannelState.OPEN) {
				assertTrue(System.nanoTime() < deadline, "data channel should open within 5 s");
				Thread.sleep(20);
			}
		}
	}
}
