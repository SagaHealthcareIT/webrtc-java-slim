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

#include "api/RTCDtlsTransportObserver.h"
#include "api/WebRTCUtils.h"
#include "JavaEnums.h"
#include "JavaRef.h"
#include "JavaString.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"
#include "rtc/CallbackGuard.h"

namespace jni
{
	RTCDtlsTransportObserver::RTCDtlsTransportObserver(JNIEnv * env, const JavaGlobalRef<jobject> & observer) :
		observer(observer),
		javaClass(JavaClasses::get<JavaRTCDtlsTransportObserverClass>(env))
	{
	}

	// libwebrtc calls both on its network thread. Each body runs in CallJava,
	// which contains any failure there (see CallbackGuard.h).

	void RTCDtlsTransportObserver::OnStateChange(webrtc::DtlsTransportInformation info)
	{
		CallJava("RTCDtlsTransportObserver.onStateChange", [&](JNIEnv * env) {
			auto state = JavaEnums::toJava(env, info.state());

			CallVoidJava(env, observer, javaClass->onStateChange, state.get());
		});
	}

	void RTCDtlsTransportObserver::OnError(webrtc::RTCError error)
	{
		CallJava("RTCDtlsTransportObserver.onError", [&](JNIEnv * env) {
			JavaLocalRef<jstring> errorMessage = JavaString::toJava(env, RTCErrorToString(error));

			CallVoidJava(env, observer, javaClass->onError, errorMessage.get());
		});
	}

	RTCDtlsTransportObserver::JavaRTCDtlsTransportObserverClass::JavaRTCDtlsTransportObserverClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG"RTCDtlsTransportObserver");

		onStateChange = GetMethod(env, cls, "onStateChange", "(L" PKG "RTCDtlsTransportState;)V");
		onError = GetMethod(env, cls, "onError", "(" STRING_SIG ")V");
	}
}
