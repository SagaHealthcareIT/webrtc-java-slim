// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Saga IT, LLC

package dev.kastle.webrtc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * libwebrtc calls the Java observers from its own threads, and nothing above
 * those calls catches anything. Previously, a Java exception left by a
 * callback, or an OutOfMemoryError while copying a received message, was
 * rethrown there as a C++ exception and aborted the JVM.
 * <p>
 * These tests make callbacks fail on purpose. The JVM must survive. A message
 * that could not be delivered must close its channel on both ends and must
 * not be followed by later messages of that channel (the stream above would
 * see a gap). Callbacks after the failure must still arrive.
 */
class NativeCallbackContainmentTests extends TestBase {

	private static final long TIMEOUT_SECONDS = 10;

	@Test
	void failedMessageCopyClosesTheChannelOnBothEnds() throws Exception {
		assertFailedDeliveryClosesTheChannel(false);
	}

	@Test
	void onMessageThatThrowsClosesTheChannelOnBothEnds() throws Exception {
		assertFailedDeliveryClosesTheChannel(true);
	}

	/**
	 * Every PeerConnectionObserver callback, every session description
	 * callback, and the data channel onStateChange and onBufferedAmountChange
	 * callbacks below throw an Error. The connection must still come up and
	 * carry data.
	 */
	@Test
	void throwingCallbacksAreContained() throws Exception {
		assertDescriptionFailureIsContained();

		Peer caller = new ThrowingPeer(factory);
		Peer callee = new ThrowingPeer(factory);

		try {
			caller.setRemotePeerConnection(callee);
			callee.setRemotePeerConnection(caller);

			RTCSessionDescription offer = createDescription(caller.getPeerConnection(), true);
			setDescription(caller.getPeerConnection(), offer, true);
			setDescription(callee.getPeerConnection(), offer, false);

			RTCSessionDescription answer = createDescription(callee.getPeerConnection(), false);
			setDescription(callee.getPeerConnection(), answer, true);
			setDescription(caller.getPeerConnection(), answer, false);

			caller.waitUntilConnected();
			callee.waitUntilConnected();

			assertChannelCarriesData(caller, callee, "after-throwing-callbacks", true);
		}
		finally {
			caller.dispose();
			callee.dispose();
		}
	}

	private void assertFailedDeliveryClosesTheChannel(boolean observerThrows) throws Exception {
		Peer caller = new Peer(factory);
		Peer callee = new Peer(factory);

		try {
			caller.setRemotePeerConnection(callee);
			callee.setRemotePeerConnection(caller);

			callee.setRemoteDescription(caller.createOffer());
			caller.setRemoteDescription(callee.createAnswer());

			caller.waitUntilConnected();
			callee.waitUntilConnected();

			RTCDataChannel sending = caller.openChannel("failing");
			RecordingObserver sender = caller.observe(sending);
			RTCDataChannel receiving = callee.awaitRemoteChannel("failing");

			awaitOpen(sending);
			awaitOpen(receiving);

			if (!observerThrows) {
				RTCDataChannel.injectMessageCopyFailures(1);
			}

			// Send all three before the receiving side has an observer: libwebrtc
			// holds them and hands all three over in one go when the observer is
			// registered, so the second and third are already queued for it when
			// the first fails. Only the failure gate keeps them from Java.
			for (byte i = 1; i <= 3; i++) {
				sending.send(new RTCDataChannelBuffer(ByteBuffer.wrap(new byte[] { i, i, i }), true));
			}
			awaitSent(sending);
			Thread.sleep(500);

			RecordingObserver receiver = new RecordingObserver(receiving);
			receiver.throwOnMessage = observerThrows;
			receiving.registerObserver(receiver);

			assertTrue(receiver.closed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
					"the receiving channel should close after a failed delivery");
			assertTrue(sender.closed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
					"the sending channel should see the close");

			// Only the first message reached onMessage, and only when the observer
			// itself failed. Nothing after the failed message was delivered.
			assertEquals(observerThrows ? 1 : 0, receiver.onMessageCalls.get());

			// Later callbacks on the same connection still arrive: onDataChannel,
			// onStateChange and onMessage of a new channel.
			assertChannelCarriesData(caller, callee, "after-failure", false);
		}
		finally {
			RTCDataChannel.injectMessageCopyFailures(0);
			caller.dispose();
			callee.dispose();
		}
	}

	private static void assertChannelCarriesData(Peer caller, Peer callee, String label, boolean throwing)
			throws Exception {
		RTCDataChannel sending = caller.openChannel(label);
		RecordingObserver sender = new RecordingObserver(sending);
		sender.throwOnStateAndBufferedAmount = throwing;
		sending.registerObserver(sender);

		RTCDataChannel remote = callee.awaitRemoteChannel(label);
		RecordingObserver receiver = new RecordingObserver(remote);
		receiver.throwOnStateAndBufferedAmount = throwing;
		remote.registerObserver(receiver);

		awaitOpen(sending);

		byte[] payload = new byte[] { 4, 5, 6, 7 };
		sending.send(new RTCDataChannelBuffer(ByteBuffer.wrap(payload), true));

		byte[] received = receiver.messages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		assertNotNull(received, "a message on a new channel should arrive after the failure");
		assertArrayEquals(payload, received);
	}

	/**
	 * createAnswer without a remote offer fails; the onFailure callback throws.
	 */
	private void assertDescriptionFailureIsContained() throws Exception {
		RTCPeerConnection pc = factory.createPeerConnection(new RTCConfiguration(), candidate -> { });
		CountDownLatch failed = new CountDownLatch(1);

		try {
			pc.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {

				@Override
				public void onSuccess(RTCSessionDescription description) { }

				@Override
				public void onFailure(String error) {
					failed.countDown();
					throw new Error("injected test failure");
				}
			});

			assertTrue(failed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "createAnswer should fail");
		}
		finally {
			pc.close();
		}
	}

	private static void awaitSent(RTCDataChannel channel) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
		while (channel.getBufferedAmount() > 0) {
			assertTrue(System.nanoTime() < deadline, "data channel should send its buffer");
			Thread.sleep(20);
		}
	}

	private static void awaitOpen(RTCDataChannel channel) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
		while (channel.getState() != RTCDataChannelState.OPEN) {
			assertTrue(System.nanoTime() < deadline, "data channel should open");
			Thread.sleep(20);
		}
	}

	private static RTCSessionDescription createDescription(RTCPeerConnection pc, boolean offer) throws Exception {
		TestCreateDescObserver observer = new TestCreateDescObserver() {

			@Override
			public void onSuccess(RTCSessionDescription description) {
				super.onSuccess(description);
				throw new Error("injected test failure");
			}
		};

		if (offer) {
			pc.createOffer(new RTCOfferOptions(), observer);
		}
		else {
			pc.createAnswer(new RTCAnswerOptions(), observer);
		}

		return observer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	private static void setDescription(RTCPeerConnection pc, RTCSessionDescription description, boolean local)
			throws Exception {
		TestSetDescObserver observer = new TestSetDescObserver() {

			@Override
			public void onSuccess() {
				super.onSuccess();
				throw new Error("injected test failure");
			}
		};

		if (local) {
			pc.setLocalDescription(description, observer);
		}
		else {
			pc.setRemoteDescription(description, observer);
		}

		observer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	private static class RecordingObserver implements RTCDataChannelObserver {

		private final RTCDataChannel channel;

		final BlockingQueue<byte[]> messages = new LinkedBlockingQueue<>();

		final AtomicInteger onMessageCalls = new AtomicInteger();

		final CountDownLatch closed = new CountDownLatch(1);

		volatile boolean throwOnMessage;

		volatile boolean throwOnStateAndBufferedAmount;

		RecordingObserver(RTCDataChannel channel) {
			this.channel = channel;
		}

		@Override
		public void onBufferedAmountChange(long previousAmount) {
			if (throwOnStateAndBufferedAmount) {
				throw new Error("injected test failure");
			}
		}

		@Override
		public void onStateChange() {
			if (channel.getState() == RTCDataChannelState.CLOSED) {
				closed.countDown();
			}
			if (throwOnStateAndBufferedAmount) {
				throw new Error("injected test failure");
			}
		}

		@Override
		public void onMessage(RTCDataChannelBuffer buffer) {
			onMessageCalls.incrementAndGet();

			ByteBuffer window = buffer.data.duplicate();
			byte[] bytes = new byte[window.remaining()];
			window.get(bytes);
			messages.add(bytes);

			if (throwOnMessage) {
				throw new Error("injected test failure");
			}
		}
	}

	private static class Peer extends TestPeerConnection {

		private final Map<String, CompletableFuture<RTCDataChannel>> remoteChannels = new ConcurrentHashMap<>();

		private final List<RTCDataChannel> channels = new CopyOnWriteArrayList<>();

		Peer(PeerConnectionFactory factory) {
			super(factory);
		}

		@Override
		public void onDataChannel(RTCDataChannel dataChannel) {
			remote(dataChannel.getLabel()).complete(dataChannel);
		}

		RTCDataChannel openChannel(String label) {
			RTCDataChannel channel = getPeerConnection().createDataChannel(label, new RTCDataChannelInit());
			channels.add(channel);
			return channel;
		}

		RTCDataChannel awaitRemoteChannel(String label) throws Exception {
			RTCDataChannel channel = remote(label).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			channels.add(channel);
			return channel;
		}

		RecordingObserver observe(RTCDataChannel channel) {
			RecordingObserver observer = new RecordingObserver(channel);
			channel.registerObserver(observer);
			return observer;
		}

		void dispose() {
			channels.forEach(RTCDataChannel::dispose);
			channels.clear();
			close();
		}

		private CompletableFuture<RTCDataChannel> remote(String label) {
			return remoteChannels.computeIfAbsent(label, key -> new CompletableFuture<>());
		}
	}

	/** Throws an Error from every PeerConnectionObserver callback it receives. */
	private static class ThrowingPeer extends Peer {

		ThrowingPeer(PeerConnectionFactory factory) {
			super(factory);
		}

		@Override
		public void onIceCandidate(RTCIceCandidate candidate) {
			super.onIceCandidate(candidate);
			throw new Error("injected test failure");
		}

		@Override
		public void onConnectionChange(RTCPeerConnectionState state) {
			super.onConnectionChange(state);
			throw new Error("injected test failure");
		}

		@Override
		public void onSignalingChange(RTCSignalingState state) {
			throw new Error("injected test failure");
		}

		@Override
		public void onIceConnectionChange(RTCIceConnectionState state) {
			throw new Error("injected test failure");
		}

		@Override
		public void onIceGatheringChange(RTCIceGatheringState state) {
			throw new Error("injected test failure");
		}

		@Override
		public void onIceConnectionReceivingChange(boolean receiving) {
			throw new Error("injected test failure");
		}

		@Override
		public void onRenegotiationNeeded() {
			throw new Error("injected test failure");
		}

		@Override
		public void onDataChannel(RTCDataChannel dataChannel) {
			super.onDataChannel(dataChannel);
			throw new Error("injected test failure");
		}
	}
}
