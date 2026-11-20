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

#include "api/DataBufferFactory.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"

#include <atomic>

namespace
{
	std::atomic<int> injectedCopyFailures { 0 };

	bool takeInjectedCopyFailure()
	{
		int remaining = injectedCopyFailures.load(std::memory_order_relaxed);

		while (remaining > 0) {
			if (injectedCopyFailures.compare_exchange_weak(remaining, remaining - 1)) {
				return true;
			}
		}

		return false;
	}
}

namespace jni
{
	const DataBufferFactory & DataBufferFactory::shared(JNIEnv * env)
	{
		// A constructor that throws leaves this unset; the next call retries.
		static const DataBufferFactory * const factory = new DataBufferFactory(env, PKG"RTCDataChannelBuffer");

		return *factory;
	}

	void DataBufferFactory::injectCopyFailures(int count)
	{
		injectedCopyFailures.store(count > 0 ? count : 0);
	}

	DataBufferFactory::DataBufferFactory(JNIEnv * env, const char * className) :
		JavaFactory(env, className, "(" BYTE_BUFFER_SIG "Z)V"),
		byteBufferClass(env, FindClass(env, "java/nio/ByteBuffer")),
		byteBufferWrap(GetStaticMethod(env, byteBufferClass, "wrap", "([B)" BYTE_BUFFER_SIG))
	{
	}

	JavaLocalRef<jobject> DataBufferFactory::create(JNIEnv * env, const webrtc::DataBuffer * dataBuffer) const
	{
		// Copy into a Java-owned array: the webrtc::DataBuffer's memory is only
		// valid for the duration of the OnMessage callback. The previous
		// NewDirectByteBuffer aliased that memory, so any Java consumer that
		// retained the buffer past the callback read freed/reused memory.
		const jsize size = static_cast<jsize>(dataBuffer->data.size());

		if (takeInjectedCopyFailure()) {
			jclass error = env->FindClass("java/lang/OutOfMemoryError");

			if (error != nullptr) {
				env->ThrowNew(error, "injected message copy failure (test hook)");
				env->DeleteLocalRef(error);
			}
			// Either way an exception is pending, as after a failed NewByteArray.
			return JavaLocalRef<jobject>(env, nullptr);
		}

		jbyteArray array = env->NewByteArray(size);
		if (array == nullptr) {
			// OutOfMemoryError is pending.
			return JavaLocalRef<jobject>(env, nullptr);
		}
		env->SetByteArrayRegion(array, 0, size, reinterpret_cast<const jbyte *>(dataBuffer->data.data<char>()));

		jobject buffer = env->CallStaticObjectMethod(byteBufferClass, byteBufferWrap, array);
		ExceptionCheck(env);

		const jboolean isBinary = static_cast<jboolean>(dataBuffer->binary);

		jobject object = env->NewObject(javaClass, javaCtor, buffer, isBinary);
		ExceptionCheck(env);

		env->DeleteLocalRef(array);
		env->DeleteLocalRef(buffer);

		return JavaLocalRef<jobject>(env, object);
	}
}