// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Saga IT, LLC

package dev.kastle.webrtc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;

/**
 * Since 1.4.1, close() also frees the native peer connection, so it must free
 * it exactly once: on repeated calls, when an observer callback that close()
 * itself triggers calls close() again, and when close() is called from inside
 * a callback (the release then waits until the callback returns).
 * <p>
 * Every callback here catches everything: an exception escaping a libwebrtc
 * callback aborts the JVM.
 */
class RTCPeerConnectionCloseTests extends TestBase {

	@Test
	void closingTwiceIsANoOp() {
		RTCPeerConnection pc = factory.createPeerConnection(new RTCConfiguration(), candidate -> { });

		pc.close();
		pc.close();
	}

	/**
	 * close() fires onIceConnectionChange and onConnectionChange(CLOSED)
	 * synchronously on the signaling thread. A handler that closes again must
	 * not release the connection a second time. Repeated so that a double
	 * release would show up as a crash, not as luck.
	 */
	@Test
	void closingAgainFromTheClosedCallbackReleasesOnce() {
		for (int i = 0; i < 50; i++) {
			AtomicReference<RTCPeerConnection> self = new AtomicReference<>();
			AtomicReference<Throwable> failure = new AtomicReference<>();
			AtomicInteger nestedCloses = new AtomicInteger();

			PeerConnectionObserver observer = new PeerConnectionObserver() {

				@Override
				public void onIceCandidate(RTCIceCandidate candidate) { }

				@Override
				public void onConnectionChange(RTCPeerConnectionState state) {
					try {
						if (state == RTCPeerConnectionState.CLOSED) {
							nestedCloses.incrementAndGet();
							self.get().close();
						}
					}
					catch (Throwable t) {
						failure.set(t);
					}
				}
			};

			RTCPeerConnection pc = factory.createPeerConnection(new RTCConfiguration(), observer);
			self.set(pc);
			pc.createDataChannel("closing", new RTCDataChannelInit());

			pc.close();

			assertNull(failure.get(), () -> "close() inside onConnectionChange(CLOSED) threw: " + failure.get());
			assertEquals(1, nestedCloses.get(), "close() should report CLOSED once, from inside the outer close()");
		}
	}

	/**
	 * A second close() on another application thread must not return while
	 * the first is still closing; the caller may dispose the factory next. The
	 * first close is held inside its own onConnectionChange(CLOSED) callback.
	 */
	@Test
	void aConcurrentCloseWaitsForTheFirstToFinish() throws Exception {
		AtomicReference<RTCPeerConnection> self = new AtomicReference<>();
		AtomicReference<Thread> second = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		AtomicLong callbackEnded = new AtomicLong();
		AtomicLong secondReturned = new AtomicLong();

		PeerConnectionObserver observer = new PeerConnectionObserver() {

			@Override
			public void onIceCandidate(RTCIceCandidate candidate) { }

			@Override
			public void onConnectionChange(RTCPeerConnectionState state) {
				try {
					if (state == RTCPeerConnectionState.CLOSED && second.get() == null) {
						Thread thread = new Thread(() -> {
							self.get().close();
							secondReturned.set(System.nanoTime());
						});
						second.set(thread);
						thread.start();

						// Keeps the first close() inside Close().
						Thread.sleep(300);
						callbackEnded.set(System.nanoTime());
					}
				}
				catch (Throwable t) {
					failure.set(t);
				}
			}
		};

		RTCPeerConnection pc = factory.createPeerConnection(new RTCConfiguration(), observer);
		self.set(pc);

		pc.close();

		second.get().join(TimeUnit.SECONDS.toMillis(5));
		assertNull(failure.get(), () -> "the CLOSED callback threw: " + failure.get());
		assertTrue(secondReturned.get() != 0, "the second close() should return within 5 s");
		assertTrue(secondReturned.get() >= callbackEnded.get(),
				"the second close() returned while the first was still inside Close()");
	}

	/**
	 * Closing from inside onConnectionChange(CONNECTED) runs on the signaling
	 * thread inside the peer connection's own callback, so the native release
	 * (and the observer delete) waits until that callback returns. The
	 * observer is reachable only through the native side, so its collection
	 * shows that deferred release ran. (Without ASan this cannot tell a
	 * deferred release from an immediate one.)
	 */
	@Test
	void closingFromInsideACallbackReleasesAfterItReturns() throws Exception {
		CountDownLatch closedInside = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();

		Peer caller = Peer.create(factory, (self, state) -> {
			if (state == RTCPeerConnectionState.CONNECTED && closedInside.getCount() > 0) {
				self.pc.close();
				closedInside.countDown();
			}
		}, failure);
		Peer callee = Peer.create(factory, (self, state) -> { }, failure);

		caller.remote = callee;
		callee.remote = caller;

		callee.setRemote(caller.offer());
		caller.setRemote(callee.answer());

		assertTrue(closedInside.await(10, TimeUnit.SECONDS), "caller should connect and close itself within 10 s");
		assertNull(failure.get(), () -> "a callback threw: " + failure.get());

		awaitCollected(caller.observer, "observer of a peer connection closed inside its callback");

		callee.pc.close();
		caller.pc.close();
		awaitCollected(callee.observer, "observer of a closed peer connection");

		RTCPeerConnection after = factory.createPeerConnection(new RTCConfiguration(), candidate -> { });
		after.close();
		assertNull(failure.get(), () -> "a callback threw: " + failure.get());
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

	/** A peer whose observer only the native side holds. */
	private static class Peer {

		volatile RTCPeerConnection pc;

		volatile Peer remote;

		WeakReference<PeerConnectionObserver> observer;

		static Peer create(PeerConnectionFactory factory, BiConsumer<Peer, RTCPeerConnectionState> onState,
				AtomicReference<Throwable> failure) {
			Peer peer = new Peer();
			PeerConnectionObserver observer = new PeerObserver(peer, onState, failure);

			peer.pc = factory.createPeerConnection(new RTCConfiguration(), observer);
			peer.pc.createDataChannel("close", new RTCDataChannelInit());
			peer.observer = new WeakReference<>(observer);

			return peer;
		}

		RTCSessionDescription offer() throws Exception {
			TestCreateDescObserver create = new TestCreateDescObserver();
			pc.createOffer(new RTCOfferOptions(), create);
			RTCSessionDescription description = create.get();
			setLocal(description);
			return description;
		}

		RTCSessionDescription answer() throws Exception {
			TestCreateDescObserver create = new TestCreateDescObserver();
			pc.createAnswer(new RTCAnswerOptions(), create);
			RTCSessionDescription description = create.get();
			setLocal(description);
			return description;
		}

		private void setLocal(RTCSessionDescription description) throws Exception {
			TestSetDescObserver set = new TestSetDescObserver();
			pc.setLocalDescription(description, set);
			set.get();
		}

		void setRemote(RTCSessionDescription description) throws Exception {
			TestSetDescObserver set = new TestSetDescObserver();
			pc.setRemoteDescription(description, set);
			set.get();
		}
	}

	private static class PeerObserver implements PeerConnectionObserver {

		private final Peer peer;

		private final BiConsumer<Peer, RTCPeerConnectionState> onState;

		private final AtomicReference<Throwable> failure;

		PeerObserver(Peer peer, BiConsumer<Peer, RTCPeerConnectionState> onState, AtomicReference<Throwable> failure) {
			this.peer = peer;
			this.onState = onState;
			this.failure = failure;
		}

		@Override
		public void onIceCandidate(RTCIceCandidate candidate) {
			try {
				peer.remote.pc.addIceCandidate(candidate);
			}
			catch (Throwable t) {
				// The other side may already be closed; that is expected here.
			}
		}

		@Override
		public void onConnectionChange(RTCPeerConnectionState state) {
			try {
				onState.accept(peer, state);
			}
			catch (Throwable t) {
				failure.compareAndSet(null, t);
			}
		}
	}
}
