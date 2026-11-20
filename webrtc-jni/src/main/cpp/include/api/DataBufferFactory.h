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

#ifndef JNI_WEBRTC_API_DATA_BUFFER_FACTORY_H_
#define JNI_WEBRTC_API_DATA_BUFFER_FACTORY_H_

#include "JavaFactory.h"

#include "api/data_channel_interface.h"

namespace jni
{
	class DataBufferFactory : public JavaFactory<webrtc::DataBuffer>
	{
		public:
			DataBufferFactory(JNIEnv * env, const char * className);

			/**
			 * The one factory for RTCDataChannelBuffer, created on first use and
			 * never freed. One per observer cost two class lookups through the
			 * Java class loader on every registerObserver call, which can fail
			 * when the heap is exhausted, and leaked two global references.
			 * Never freed, so no JNI call runs from a static destructor at exit.
			 */
			static const DataBufferFactory & shared(JNIEnv * env);

			JavaLocalRef<jobject> create(JNIEnv * env, const webrtc::DataBuffer * dataBuffer) const override;

			/**
			 * Test hook (RTCDataChannel.injectMessageCopyFailures): the next
			 * {@code count} calls to create(), on any channel, fail the way an
			 * exhausted Java heap makes them fail: null, with an
			 * OutOfMemoryError pending. Zero or less disarms it.
			 */
			static void injectCopyFailures(int count);

		private:
			JavaGlobalRef<jclass> byteBufferClass;
			jmethodID byteBufferWrap;
	};
}

#endif