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

#include "api/RTCStatsCollectorCallback.h"
#include "api/RTCStatsReport.h"
#include "JNI_WebRTC.h"
#include "rtc/CallbackGuard.h"

namespace jni
{
	RTCStatsCollectorCallback::RTCStatsCollectorCallback(JNIEnv * env, const JavaGlobalRef<jobject> & callback) :
		callback(callback),
		javaClass(JavaClasses::get<JavaRTCStatsCollectorCallbackClass>(env))
	{
	}

	// libwebrtc calls this on its signaling thread. The body runs in CallJava,
	// which contains any failure there (see CallbackGuard.h).
	void RTCStatsCollectorCallback::OnStatsDelivered(const webrtc::scoped_refptr<const webrtc::RTCStatsReport> & report)
	{
		CallJava("RTCStatsCollectorCallback.onStatsDelivered", [&](JNIEnv * env) {
			JavaLocalRef<jobject> javaReport = jni::RTCStatsReport::toJava(env, report);

			CallVoidJava(env, callback, javaClass->onStatsDelivered, javaReport.get());
		});
	}

	RTCStatsCollectorCallback::JavaRTCStatsCollectorCallbackClass::JavaRTCStatsCollectorCallbackClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG"RTCStatsCollectorCallback");

		onStatsDelivered = GetMethod(env, cls, "onStatsDelivered", "(L" PKG "RTCStatsReport;)V");
	}
}
