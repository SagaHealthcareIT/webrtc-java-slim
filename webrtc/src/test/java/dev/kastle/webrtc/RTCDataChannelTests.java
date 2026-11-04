/*
 * Copyright 2019 Alex Andres
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.kastle.webrtc;

import static java.util.Objects.nonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RTCDataChannelTests extends TestBase {

	@Test
	void bufferedAmountChangeCallback() throws Exception {
		DataPeerConnection caller = new DataPeerConnection(factory);
		DataPeerConnection callee = new DataPeerConnection(factory);

		caller.setRemotePeerConnection(callee);
		callee.setRemotePeerConnection(caller);

		callee.setRemoteDescription(caller.createOffer());
		caller.setRemoteDescription(callee.createAnswer());

		caller.waitUntilConnected();
		callee.waitUntilConnected();

		// Prepare a latch-based observer to detect buffered amount change.
		CountDownLatch latch = new CountDownLatch(1);

		caller.getLocalDataChannel().registerObserver(new RTCDataChannelObserver() {
			@Override
			public void onBufferedAmountChange(long previousAmount) {
				latch.countDown();
			}

			@Override
			public void onStateChange() { }

			@Override
			public void onMessage(RTCDataChannelBuffer buffer) { }
		});

		// Send a large enough message to cause buffering (increase from 0).
		byte[] big = new byte[64 * 1024]; // 64 KB
		ByteBuffer data = ByteBuffer.wrap(big);
		RTCDataChannelBuffer buffer = new RTCDataChannelBuffer(data, true);
		caller.getLocalDataChannel().send(buffer);

		// Wait for the callback to fire to avoid flakiness.
		boolean signaled = latch.await(5, java.util.concurrent.TimeUnit.SECONDS);

		assertTrue(signaled, "onBufferedAmountChange should be called when sending data");

		caller.close();
		callee.close();
	}

	@Test
	void textMessage() throws Exception {
		DataPeerConnection caller = new DataPeerConnection(factory);
		DataPeerConnection callee = new DataPeerConnection(factory);

		caller.setRemotePeerConnection(callee);
		callee.setRemotePeerConnection(caller);

		callee.setRemoteDescription(caller.createOffer());
		caller.setRemoteDescription(callee.createAnswer());

		caller.waitUntilConnected();
		callee.waitUntilConnected();

		Thread.sleep(500);

		caller.sendTextMessage("Hello world");
		callee.sendTextMessage("Hi :)");

		Thread.sleep(500);

		assertEquals(Collections.singletonList("Hello world"), callee.getReceivedTexts());
		assertEquals(Collections.singletonList("Hi :)"), caller.getReceivedTexts());

		caller.close();
		callee.close();
	}

	/**
	 * The direct-buffer send path (the one gateway's DataChannelStream uses)
	 * must send exactly the position/limit window, byte for byte, and must copy
	 * it: changing the buffer right after send() must not change what arrives.
	 */
	@Test
	void binaryDirectBufferWindow() throws Exception {
		DataPeerConnection caller = new DataPeerConnection(factory);
		DataPeerConnection callee = new DataPeerConnection(factory);

		caller.setRemotePeerConnection(callee);
		callee.setRemotePeerConnection(caller);

		callee.setRemoteDescription(caller.createOffer());
		caller.setRemoteDescription(callee.createAnswer());

		caller.waitUntilConnected();
		callee.waitUntilConnected();
		caller.waitUntilLocalChannelOpen();

		// High-bit bytes 0x80..0x8F; send only [3, 9).
		ByteBuffer direct = ByteBuffer.allocateDirect(16);
		for (int i = 0; i < 16; i++) {
			direct.put(i, (byte) (0x80 + i));
		}
		direct.position(3).limit(9);
		byte[] expected = new byte[] { (byte) 0x83, (byte) 0x84, (byte) 0x85, (byte) 0x86, (byte) 0x87, (byte) 0x88 };

		caller.getLocalDataChannel().send(new RTCDataChannelBuffer(direct, true));

		ByteBuffer whole = direct.duplicate().clear();
		for (int i = 0; i < 16; i++) {
			whole.put(i, (byte) 0);
		}

		byte[] received = callee.pollReceivedBinary();

		Assertions.assertNotNull(received, "binary message should arrive within 5 s");
		Assertions.assertArrayEquals(expected, received);

		caller.close();
		callee.close();
	}

	/**
	 * Once onMessage returns, nothing on the native side may keep the received
	 * buffer reachable. The callback runs on a libwebrtc thread that stays
	 * attached to the JVM, so a JNI local reference left undeleted there pins
	 * every message until the thread exits, i.e. for the life of the
	 * connection (RTC-1).
	 */
	@Test
	void receivedBuffersAreNotRetained() throws Exception {
		DataPeerConnection caller = new DataPeerConnection(factory);
		DataPeerConnection callee = new DataPeerConnection(factory);

		caller.setRemotePeerConnection(callee);
		callee.setRemotePeerConnection(caller);

		callee.setRemoteDescription(caller.createOffer());
		caller.setRemoteDescription(callee.createAnswer());

		caller.waitUntilConnected();
		callee.waitUntilConnected();
		caller.waitUntilLocalChannelOpen();

		final int count = 32;
		for (int i = 0; i < count; i++) {
			caller.getLocalDataChannel().send(new RTCDataChannelBuffer(ByteBuffer.wrap(new byte[16 * 1024]), true));
		}
		for (int i = 0; i < count; i++) {
			Assertions.assertNotNull(callee.pollReceivedBinary(), "message " + i + " should arrive within 5 s");
		}

		// The connection stays open: the leak only lets go when the thread does.
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		long retained;
		do {
			System.gc();
			Thread.sleep(50);
			retained = callee.receivedBufferRefs.stream().filter(ref -> ref.get() != null).count();
		}
		while (retained > 0 && System.nanoTime() < deadline);

		assertEquals(count, callee.receivedBufferRefs.size());
		assertEquals(0, retained, "received buffers still reachable after onMessage returned");

		caller.close();
		callee.close();
	}

	private static class DataPeerConnection extends TestPeerConnection {

		private final List<String> receivedTexts = new ArrayList<>();

		private final BlockingQueue<byte[]> receivedBinary = new LinkedBlockingQueue<>();

		/**
		 * The payload array of every binary buffer onMessage was handed, held
		 * weakly. It is reachable through the RTCDataChannelBuffer, its
		 * ByteBuffer and the array itself, so a native reference leaked to any
		 * of the three keeps it alive.
		 */
		private final List<WeakReference<byte[]>> receivedBufferRefs = new CopyOnWriteArrayList<>();

		private final RTCDataChannel localDataChannel;

		private RTCDataChannel remoteDataChannel;


		DataPeerConnection(PeerConnectionFactory factory) {
			super(factory);

			localDataChannel = getPeerConnection().createDataChannel("dc", new RTCDataChannelInit());
		}

		@Override
		public void onDataChannel(RTCDataChannel dataChannel) {
			remoteDataChannel = dataChannel;
			remoteDataChannel.registerObserver(new RTCDataChannelObserver() {

				@Override
				public void onBufferedAmountChange(long previousAmount) { }

				@Override
				public void onStateChange() { }

				@Override
				public void onMessage(RTCDataChannelBuffer buffer) {
					try {
						decodeMessage(buffer);
					}
					catch (Exception e) {
						Assertions.fail(e);
					}
				}
			});
		}

		RTCDataChannel getLocalDataChannel() {
			return localDataChannel;
		}

		List<String> getReceivedTexts() {
			return receivedTexts;
		}

		byte[] pollReceivedBinary() throws InterruptedException {
			return receivedBinary.poll(5, TimeUnit.SECONDS);
		}

		void waitUntilLocalChannelOpen() throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (localDataChannel.getState() != RTCDataChannelState.OPEN) {
				assertTrue(System.nanoTime() < deadline, "data channel should open within 5 s");
				Thread.sleep(20);
			}
		}

		void sendTextMessage(String message) throws Exception {
			ByteBuffer data = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));
			RTCDataChannelBuffer buffer = new RTCDataChannelBuffer(data, false);

			localDataChannel.send(buffer);
		}

		private void decodeMessage(RTCDataChannelBuffer buffer) {
			if (buffer.binary) {
				receivedBufferRefs.add(new WeakReference<>(buffer.data.array()));
				ByteBuffer window = buffer.data.duplicate();
				byte[] bytes = new byte[window.remaining()];
				window.get(bytes);
				receivedBinary.add(bytes);
				return;
			}

			ByteBuffer byteBuffer = buffer.data;
			byte[] payload;

			if (byteBuffer.hasArray()) {
				payload = byteBuffer.array();
			}
			else {
				payload = new byte[byteBuffer.limit()];

				byteBuffer.get(payload);
			}

			String text = new String(payload, StandardCharsets.UTF_8);

			receivedTexts.add(text);
		}

		void close() {
			if (nonNull(localDataChannel)) {
				localDataChannel.unregisterObserver();
				localDataChannel.close();
				localDataChannel.dispose();
			}
			if (nonNull(remoteDataChannel)) {
				remoteDataChannel.unregisterObserver();
				remoteDataChannel.close();
				remoteDataChannel.dispose();
			}

			super.close();
		}
	}
}
