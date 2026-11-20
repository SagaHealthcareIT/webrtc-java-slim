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

#ifndef JNI_WEBRTC_API_RTC_DATA_CHANNEL_OBSERVER_H_
#define JNI_WEBRTC_API_RTC_DATA_CHANNEL_OBSERVER_H_

#include "JavaClass.h"
#include "JavaRef.h"

#include "api/data_channel_interface.h"
#include <api/DataBufferFactory.h>

#include <jni.h>
#include <atomic>
#include <memory>

namespace jni
{
	class RTCDataChannelObserver : public webrtc::DataChannelObserver
	{
		public:
			/**
			 * The channel is not owned. It outlives every callback to this
			 * observer: RTCDataChannel.dispose() unregisters the observer, which
			 * waits for a running callback, before it releases the channel.
			 */
			RTCDataChannelObserver(JNIEnv * env, const JavaGlobalRef<jobject> & observer, webrtc::DataChannelInterface * channel);
			~RTCDataChannelObserver() = default;

			// DataChannelObserver implementation.
			void OnStateChange() override;
			void OnMessage(const webrtc::DataBuffer & buffer) override;
			void OnBufferedAmountChange(uint64_t sent_data_size) override;

		private:
			class JavaRTCDataChannelObserverClass : public JavaClass
			{
				public:
					explicit JavaRTCDataChannelObserverClass(JNIEnv * env);

					jmethodID onStateChange;
					jmethodID onMessage;
					jmethodID onBufferedAmountChange;
			};

		private:
			void closeAfterFailedDelivery(size_t size);

			JavaGlobalRef<jobject> observer;

			webrtc::DataChannelInterface * const channel;

			/**
			 * Set once a message could not be handed to Java. Every later message
			 * is dropped: the stream above must not see a gap, and the channel is
			 * already closing.
			 */
			std::atomic<bool> deliveryFailed { false };

			const DataBufferFactory & bufferFactory;

			const std::shared_ptr<JavaRTCDataChannelObserverClass> javaClass;
	};
}

#endif