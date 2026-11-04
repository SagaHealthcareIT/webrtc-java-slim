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

#include "JNI_RTCDataChannel.h"
#include "api/RTCDataChannelObserver.h"
#include "JavaEnums.h"
#include "JavaError.h"
#include "JavaRef.h"
#include "JavaString.h"
#include "JavaUtils.h"
#include "JavaNullPointerException.h"
#include "rtc/RunAfterCallback.h"

#include "api/data_channel_interface.h"

#include <span>

// libwebrtc never owns a DataChannelObserver, so this JNI does: the observer
// lives in RTCDataChannel.observerHandle until it is replaced, unregistered or
// the channel is disposed. Before 1.4.1 it was never freed, and with it the
// global reference to the Java observer and everything that observer reaches.

static void DeleteObserver(jni::RTCDataChannelObserver * observer)
{
	if (observer != nullptr) {
		// After RegisterObserver or UnregisterObserver returns, libwebrtc
		// delivers nothing more to this observer, but its callback may be on
		// the stack right now (the caller is inside it).
		jni::RunNowOrAfterCallback([observer] { delete observer; });
	}
}

JNIEXPORT void JNICALL Java_dev_kastle_webrtc_RTCDataChannel_registerObserver
(JNIEnv * env, jobject caller, jobject jObserver)
{
	if (jObserver == nullptr) {
		env->Throw(jni::JavaNullPointerException(env, "RTCDataChannelObserver must not be null"));
		return;
	}

	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLE(channel);

	auto * previous = GetHandle<jni::RTCDataChannelObserver>(env, caller, "observerHandle");
	auto * observer = new jni::RTCDataChannelObserver(env, jni::JavaGlobalRef<jobject>(env, jObserver));

	// Record the new observer first: its first callback can run before
	// RegisterObserver returns, and if that callback unregisters or disposes,
	// it must free the new observer, not the previous one.
	SetHandle(env, caller, "observerHandle", observer);

	// Replaces the previous observer. Callbacks still queued for it are dropped.
	channel->RegisterObserver(observer);

	DeleteObserver(previous);
}

JNIEXPORT void JNICALL Java_dev_kastle_webrtc_RTCDataChannel_unregisterObserver
(JNIEnv * env, jobject caller)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLE(channel);

	channel->UnregisterObserver();

	auto * observer = GetHandle<jni::RTCDataChannelObserver>(env, caller, "observerHandle");
	SetHandle<std::nullptr_t>(env, caller, "observerHandle", nullptr);
	DeleteObserver(observer);
}

JNIEXPORT jstring JNICALL Java_dev_kastle_webrtc_RTCDataChannel_getLabel
(JNIEnv * env, jobject caller)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLEV(channel, nullptr);

	return jni::JavaString::toJava(env, channel->label()).release();
}

JNIEXPORT jboolean JNICALL Java_dev_kastle_webrtc_RTCDataChannel_isReliable
(JNIEnv * env, jobject caller)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLEV(channel, false);

	return static_cast<jboolean>(channel->reliable());
}

JNIEXPORT jboolean JNICALL Java_dev_kastle_webrtc_RTCDataChannel_isOrdered
(JNIEnv * env, jobject caller)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLEV(channel, false);

	return static_cast<jboolean>(channel->ordered());
}

JNIEXPORT jint JNICALL Java_dev_kastle_webrtc_RTCDataChannel_getMaxPacketLifeTime
(JNIEnv * env, jobject caller)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLEV(channel, 0);

	return static_cast<jint>(channel->maxPacketLifeTime().value_or(0));
}

JNIEXPORT jint JNICALL Java_dev_kastle_webrtc_RTCDataChannel_getMaxRetransmits
(JNIEnv * env, jobject caller)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLEV(channel, 0);

	return static_cast<jint>(channel->maxRetransmitsOpt().value_or(0));
}

JNIEXPORT jstring JNICALL Java_dev_kastle_webrtc_RTCDataChannel_getProtocol
(JNIEnv * env, jobject caller)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLEV(channel, nullptr);

	return jni::JavaString::toJava(env, channel->protocol()).release();
}

JNIEXPORT jboolean JNICALL Java_dev_kastle_webrtc_RTCDataChannel_isNegotiated
(JNIEnv * env, jobject caller)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLEV(channel, false);

	return static_cast<jboolean>(channel->negotiated());
}

JNIEXPORT jint JNICALL Java_dev_kastle_webrtc_RTCDataChannel_getId
(JNIEnv * env, jobject caller)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLEV(channel, 0);

	return static_cast<jint>(channel->id());
}

JNIEXPORT jobject JNICALL Java_dev_kastle_webrtc_RTCDataChannel_getState
(JNIEnv * env, jobject caller)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLEV(channel, nullptr);

	return jni::JavaEnums::toJava(env, channel->state()).release();
}

JNIEXPORT jlong JNICALL Java_dev_kastle_webrtc_RTCDataChannel_getBufferedAmount
(JNIEnv * env, jobject caller)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLEV(channel, 0);

	return static_cast<jlong>(channel->buffered_amount());
}

JNIEXPORT void JNICALL Java_dev_kastle_webrtc_RTCDataChannel_close
(JNIEnv * env, jobject caller)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLE(channel);

	channel->Close();
}

JNIEXPORT void JNICALL Java_dev_kastle_webrtc_RTCDataChannel_dispose
(JNIEnv * env, jobject caller)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLE(channel);

	// Unregister first: the channel itself can outlive this handle (the peer
	// connection holds its own reference), and it must not keep calling an
	// observer that is about to be deleted.
	auto * observer = GetHandle<jni::RTCDataChannelObserver>(env, caller, "observerHandle");

	if (observer != nullptr) {
		channel->UnregisterObserver();
		SetHandle<std::nullptr_t>(env, caller, "observerHandle", nullptr);
		DeleteObserver(observer);
	}

	SetHandle<std::nullptr_t>(env, caller, nullptr);

	if (webrtc::Thread::Current() != nullptr) {
		// Inside a callback: release once it returns (see RunNowOrAfterCallback).
		jni::RunNowOrAfterCallback([channel] { channel->Release(); });
		return;
	}

	webrtc::RefCountReleaseStatus status = channel->Release();

	if (status != webrtc::RefCountReleaseStatus::kDroppedLastRef) {
		env->Throw(jni::JavaError(env, "Native object was not deleted. A reference is still around somewhere."));
	}
}

JNIEXPORT void JNICALL Java_dev_kastle_webrtc_RTCDataChannel_sendDirectBuffer
(JNIEnv * env, jobject caller, jobject jBuffer, jint position, jint length, jboolean isBinary)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLE(channel);

	uint8_t * address = static_cast<uint8_t *>(env->GetDirectBufferAddress(jBuffer));

	if (address != NULL) {
		// Honor the caller's position/limit window. Sending the full capacity
		// from offset 0 transmitted the wrong bytes for sliced buffers and
		// leaked stale bytes past the limit.
		jlong capacity = env->GetDirectBufferCapacity(jBuffer);

		if (position < 0 || length < 0 || static_cast<jlong>(position) + length > capacity) {
			env->Throw(jni::JavaError(env, "Buffer window out of bounds"));
			return;
		}

		// Copies the window (CopyOnWriteBuffer's span constructor copies).
		webrtc::CopyOnWriteBuffer data(std::span<const uint8_t>(address + position, static_cast<size_t>(length)));

		try {
			channel->Send(webrtc::DataBuffer(data, static_cast<bool>(isBinary)));
		}
		catch (...) {
			ThrowCxxJavaException(env);
		}
	}
	else {
		env->Throw(jni::JavaError(env, "Non-direct buffer provided"));
	}
}

JNIEXPORT void JNICALL Java_dev_kastle_webrtc_RTCDataChannel_sendByteArrayBuffer
(JNIEnv * env, jobject caller, jbyteArray jBufferArray, jboolean isBinary)
{
	webrtc::DataChannelInterface * channel = GetHandle<webrtc::DataChannelInterface>(env, caller);
	CHECK_HANDLE(channel);

	int8_t * arrayPtr = env->GetByteArrayElements(jBufferArray, nullptr);

	if (arrayPtr == nullptr) {
		// GetByteArrayElements failed (OutOfMemoryError is usually pending).
		if (!env->ExceptionCheck()) {
			env->Throw(jni::JavaError(env, "Failed to access byte array"));
		}
		return;
	}

	size_t arrayLength = env->GetArrayLength(jBufferArray);

	// Must copy: the array is released (JNI_ABORT) right below, and
	// CopyOnWriteBuffer's span constructor copies the bytes.
	webrtc::CopyOnWriteBuffer data(std::span<const uint8_t>(reinterpret_cast<const uint8_t *>(arrayPtr), arrayLength));

	env->ReleaseByteArrayElements(jBufferArray, arrayPtr, JNI_ABORT);

	try {
		channel->Send(webrtc::DataBuffer(data, static_cast<bool>(isBinary)));
	}
	catch (...) {
		ThrowCxxJavaException(env);
	}
}