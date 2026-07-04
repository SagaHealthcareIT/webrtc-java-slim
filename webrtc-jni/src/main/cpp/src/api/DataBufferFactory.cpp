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

namespace jni
{
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