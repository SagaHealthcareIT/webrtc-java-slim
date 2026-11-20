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

#include "api/PeerConnectionObserver.h"
#include "api/RTCIceCandidate.h"
#include "api/RTCPeerConnectionIceErrorEvent.h"
#include "Exception.h"
#include "JavaArray.h"
#include "JavaClasses.h"
#include "JavaEnums.h"
#include "JavaFactories.h"
#include "JavaRuntimeException.h"
#include "JavaUtils.h"
#include "JNI_WebRTC.h"
#include "rtc/CallbackGuard.h"

namespace jni
{
	PeerConnectionObserver::PeerConnectionObserver(JNIEnv * env, const JavaGlobalRef<jobject> & observer) :
		observer(observer),
		javaClass(JavaClasses::get<JavaPeerConnectionObserverClass>(env))
	{
	}

	// libwebrtc calls every method below on its signaling thread. Each body runs
	// in CallJava, which contains any failure there (see CallbackGuard.h).

	void PeerConnectionObserver::OnConnectionChange(webrtc::PeerConnectionInterface::PeerConnectionState state)
	{
		CallJava("PeerConnectionObserver.onConnectionChange", [&](JNIEnv * env) {
			auto jState = JavaEnums::toJava(env, state);

			CallVoidJava(env, observer, javaClass->onConnectionChange, jState.get());
		});
	}

	void PeerConnectionObserver::OnSignalingChange(webrtc::PeerConnectionInterface::SignalingState state)
	{
		CallJava("PeerConnectionObserver.onSignalingChange", [&](JNIEnv * env) {
			auto jState = JavaEnums::toJava(env, state);

			CallVoidJava(env, observer, javaClass->onSignalingChange, jState.get());
		});
	}

	void PeerConnectionObserver::OnDataChannel(webrtc::scoped_refptr<webrtc::DataChannelInterface> channel)
	{
		CallJava("PeerConnectionObserver.onDataChannel", [&](JNIEnv * env) {
			auto jDataChannel = JavaFactories::create(env, channel.get());

			// The Java RTCDataChannel holds this reference from here on and
			// releases it in dispose(). Until it exists, `channel` keeps it.
			channel.release();

			CallVoidJava(env, observer, javaClass->onDataChannel, jDataChannel.get());
		});

		if (channel.get() != nullptr) {
			// No Java object was created for the channel, so nothing will ever
			// read it or close it. Close it now, so the remote sees it closed and
			// does not send into a channel nobody reads. `channel` drops this
			// callback's reference when this returns.
			channel->Close();
		}
	}

	void PeerConnectionObserver::OnRenegotiationNeeded()
	{
		CallJava("PeerConnectionObserver.onRenegotiationNeeded", [&](JNIEnv * env) {
			CallVoidJava(env, observer, javaClass->onRenegotiationNeeded);
		});
	}

	void PeerConnectionObserver::OnIceConnectionChange(webrtc::PeerConnectionInterface::IceConnectionState state)
	{
		CallJava("PeerConnectionObserver.onIceConnectionChange", [&](JNIEnv * env) {
			auto jState = JavaEnums::toJava(env, state);

			CallVoidJava(env, observer, javaClass->onIceConnectionChange, jState.get());
		});
	}

	void PeerConnectionObserver::OnIceGatheringChange(webrtc::PeerConnectionInterface::IceGatheringState state)
	{
		CallJava("PeerConnectionObserver.onIceGatheringChange", [&](JNIEnv * env) {
			auto jState = JavaEnums::toJava(env, state);

			CallVoidJava(env, observer, javaClass->onIceGatheringChange, jState.get());
		});
	}

	void PeerConnectionObserver::OnIceCandidate(const webrtc::IceCandidateInterface * candidate)
	{
		CallJava("PeerConnectionObserver.onIceCandidate", [&](JNIEnv * env) {
			JavaLocalRef<jobject> jCandidate = RTCIceCandidate::toJava(env, candidate);

			CallVoidJava(env, observer, javaClass->onIceCandidate, jCandidate.get());
		});
	}

	void PeerConnectionObserver::OnIceCandidateError(const std::string & address, int port, const std::string & url, int error_code, const std::string & error_text)
	{
		CallJava("PeerConnectionObserver.onIceCandidateError", [&](JNIEnv * env) {
			JavaLocalRef<jobject> event = RTCPeerConnectionIceErrorEvent::toJava(env, address, port, url, error_code, error_text);

			CallVoidJava(env, observer, javaClass->onIceCandidateError, event.get());
		});
	}

	void PeerConnectionObserver::OnIceCandidateRemoved(const webrtc::IceCandidate * candidate)
	{
		CallJava("PeerConnectionObserver.onIceCandidateRemoved", [&](JNIEnv * env) {
			JavaLocalRef<jobject> jCandidate = RTCIceCandidate::toJava(env, candidate);

			CallVoidJava(env, observer, javaClass->onIceCandidateRemoved, jCandidate.get());
		});
	}

	void PeerConnectionObserver::OnIceConnectionReceivingChange(bool receiving)
	{
		CallJava("PeerConnectionObserver.onIceConnectionReceivingChange", [&](JNIEnv * env) {
			CallVoidJava(env, observer, javaClass->onIceConnectionReceivingChange, receiving);
		});
	}

	PeerConnectionObserver::JavaPeerConnectionObserverClass::JavaPeerConnectionObserverClass(JNIEnv * env)
	{
		jclass cls = FindClass(env, PKG"PeerConnectionObserver");

		onConnectionChange = GetMethod(env, cls, "onConnectionChange", "(L" PKG "RTCPeerConnectionState;)V");
		onSignalingChange = GetMethod(env, cls, "onSignalingChange", "(L" PKG "RTCSignalingState;)V");
		onDataChannel = GetMethod(env, cls, "onDataChannel", "(L" PKG "RTCDataChannel;)V");
		onRenegotiationNeeded = GetMethod(env, cls, "onRenegotiationNeeded", "()V");
		onIceConnectionChange = GetMethod(env, cls, "onIceConnectionChange", "(L" PKG "RTCIceConnectionState;)V");
		onIceGatheringChange = GetMethod(env, cls, "onIceGatheringChange", "(L" PKG "RTCIceGatheringState;)V");
		onIceCandidate = GetMethod(env, cls, "onIceCandidate", "(L" PKG "RTCIceCandidate;)V");
		onIceCandidateError = GetMethod(env, cls, "onIceCandidateError", "(L" PKG "RTCPeerConnectionIceErrorEvent;)V");
		onIceCandidateRemoved = GetMethod(env, cls, "onIceCandidateRemoved", "(L" PKG "RTCIceCandidate;)V");
		onIceConnectionReceivingChange = GetMethod(env, cls, "onIceConnectionReceivingChange", "(Z)V");
	}
}
