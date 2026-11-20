// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Saga IT, LLC

#ifndef JNI_WEBRTC_RTC_CALLBACK_GUARD_H_
#define JNI_WEBRTC_RTC_CALLBACK_GUARD_H_

#include "JavaUtils.h"
#include "JavaWrappedException.h"

#include <jni.h>

#include <exception>

namespace jni
{
	/**
	 * Writes one line about a contained callback failure to stderr. It never
	 * allocates on the Java heap, never calls into Java and never goes through
	 * RTC_LOG, whose sink may itself be a Java callback.
	 */
	void ReportCallbackFailure(const char * callback, const char * detail) noexcept;

	/**
	 * If a Java exception is pending: reports it, prints its stack trace
	 * (ExceptionDescribe; the JVM copes with a failure to print, e.g. when the
	 * heap is exhausted) and clears it. Returns whether one was pending.
	 */
	bool ClearPendingException(JNIEnv * env, const char * callback) noexcept;

	/**
	 * Calls a void Java method, unless an exception is already pending. A
	 * failed argument conversion (no heap for a string or an object) can leave
	 * one without throwing, and calling Java with an exception pending is
	 * undefined. CallJava reports and clears it.
	 */
	template <typename... Args>
	void CallVoidJava(JNIEnv * env, jobject object, jmethodID method, Args... args) noexcept
	{
		if (!env->ExceptionCheck()) {
			env->CallVoidMethod(object, method, args...);
		}
	}

	/**
	 * Runs the body of one native-to-Java callback: the code libwebrtc calls on
	 * its own threads (signaling, network) to reach a Java observer.
	 *
	 * Nothing above such a callback catches anything. libwebrtc is built
	 * without exceptions, so a C++ exception leaving the callback ends in
	 * std::terminate, and a Java exception left pending poisons the next JNI
	 * call on that thread. Both used to happen: ExceptionCheck() rethrows a
	 * pending Java exception as a C++ exception, so one OutOfMemoryError while
	 * copying a received message aborted the JVM, and with it the integration
	 * engine hosting this library.
	 *
	 * So this never throws and never leaves a Java exception pending. A
	 * failure, Java or native, is reported to stderr and the callback is
	 * abandoned. Every local reference the body creates is freed when it
	 * returns, also on a failure path: these threads stay attached to the JVM,
	 * so an undeleted local reference would otherwise live as long as the
	 * thread.
	 *
	 * Returns true if the body ran to completion and the Java method it called
	 * returned normally.
	 */
	template <typename Body>
	bool CallJava(const char * callback, Body && body) noexcept
	{
		constexpr jint kLocalFrameCapacity = 16;

		JNIEnv * env = nullptr;

		try {
			env = AttachCurrentThread();
		}
		catch (...) {
			env = nullptr;
		}

		if (env == nullptr) {
			ReportCallbackFailure(callback, "this thread could not be attached to the JVM");
			return false;
		}

		if (env->ExceptionCheck()) {
			// Only possible on a Java thread, inside a JNI call that has already
			// raised an exception for its Java caller (libwebrtc logging from such
			// a call reaches LogSink this way). The exception is the caller's:
			// leave it pending, and call nothing in Java while it is.
			return false;
		}

		if (env->PushLocalFrame(kLocalFrameCapacity) != JNI_OK) {
			ClearPendingException(env, callback);
			return false;
		}

		bool completed = false;

		try {
			body(env);
			completed = true;
		}
		catch (const JavaWrappedException &) {
			// ExceptionCheck() printed and cleared the Java exception before
			// rethrowing it as this C++ exception.
			ReportCallbackFailure(callback, "a Java exception was raised (stack trace above)");
		}
		catch (const std::exception & e) {
			ReportCallbackFailure(callback, e.what());
		}
		catch (...) {
			ReportCallbackFailure(callback, "unknown native exception");
		}

		if (ClearPendingException(env, callback)) {
			completed = false;
		}

		env->PopLocalFrame(nullptr);

		return completed;
	}
}

#endif
