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

#include "api/RTCDataChannelObserver.h"
#include "rtc/CallbackGuard.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

#include <cstdio>

namespace jni
{
	RTCDataChannelObserver::RTCDataChannelObserver(JNIEnv * env, const JavaGlobalRef<jobject> & observer, webrtc::DataChannelInterface * channel) :
		observer(observer),
		channel(channel),
		bufferFactory(DataBufferFactory::shared(env)),
		javaClass(JavaClasses::get<JavaRTCDataChannelObserverClass>(env))
	{
	}

	// libwebrtc delivers all three callbacks on its signaling thread (this
	// observer does not opt in to the network thread), and only while this
	// observer is registered.

	void RTCDataChannelObserver::OnStateChange()
	{
		CallJava("RTCDataChannelObserver.onStateChange", [&](JNIEnv * env) {
			CallVoidJava(env, observer, javaClass->onStateChange);
		});
	}

	void RTCDataChannelObserver::OnMessage(const webrtc::DataBuffer & buffer)
	{
		if (deliveryFailed.load()) {
			// An earlier message never reached Java and the channel is closing.
			// Delivering this one would hand the stream above a gap.
			return;
		}

		const bool delivered = CallJava("RTCDataChannelObserver.onMessage", [&](JNIEnv * env) {
			// create() returns null, with an OutOfMemoryError pending, when the
			// Java copy of the message cannot be allocated. CallJava reports and
			// clears it.
			JavaLocalRef<jobject> jBuffer = bufferFactory.create(env, &buffer);

			if (jBuffer.get() == nullptr) {
				return;
			}

			// Pass get(), never release(): jBuffer must delete its local reference
			// when this returns. This libwebrtc thread attached once and never
			// returns to Java, so a reference left undeleted pins the message copy
			// until the thread exits, i.e. for the life of the tunnel (RTC-1).
			CallVoidJava(env, observer, javaClass->onMessage, jBuffer.get());
		});

		if (!delivered) {
			closeAfterFailedDelivery(buffer.size());
		}
	}

	void RTCDataChannelObserver::OnBufferedAmountChange(uint64_t sent_data_size)
	{
		CallJava("RTCDataChannelObserver.onBufferedAmountChange", [&](JNIEnv * env) {
			CallVoidJava(env, observer, javaClass->onBufferedAmountChange, static_cast<jlong>(sent_data_size));
		});
	}

	/**
	 * A message that did not reach Java, or whose Java callback failed, may be
	 * part of a larger message the layer above is reassembling. Dropping it
	 * silently would corrupt that stream. Closing the channel instead makes the
	 * loss visible on both ends: this side's observer sees the channel close,
	 * and the remote sees its channel close, so the sender's own failure and
	 * retry path runs.
	 *
	 * Closing synchronously from inside this callback is safe. The callback
	 * runs on the signaling thread; Close() goes through the channel proxy as a
	 * blocking call to the network thread, the same path a Java observer takes
	 * when it calls RTCDataChannel.close() from inside onMessage. libwebrtc never
	 * blocks the network thread on the signaling thread, so this cannot
	 * deadlock, and the state change Close() causes is posted back to the
	 * signaling thread: it reaches the observer after this callback returns,
	 * not inside it.
	 */
	void RTCDataChannelObserver::closeAfterFailedDelivery(size_t size)
	{
		deliveryFailed.store(true);

		std::fprintf(stderr, "webrtc-java: RTCDataChannelObserver.onMessage: a %zu-byte message "
			"could not be delivered to Java; closing the data channel so that neither end "
			"treats the stream as intact\n", size);
		std::fflush(stderr);

		channel->Close();
	}

	RTCDataChannelObserver::JavaRTCDataChannelObserverClass::JavaRTCDataChannelObserverClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG"RTCDataChannelObserver");

		onStateChange = GetMethod(env, cls, "onStateChange", "()V");
		onMessage = GetMethod(env, cls, "onMessage", "(L" PKG "RTCDataChannelBuffer;)V");
		onBufferedAmountChange = GetMethod(env, cls, "onBufferedAmountChange", "(J)V");
	}
}
