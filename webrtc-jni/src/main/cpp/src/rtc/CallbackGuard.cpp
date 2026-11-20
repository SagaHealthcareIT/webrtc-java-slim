// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Saga IT, LLC

#include "rtc/CallbackGuard.h"

#include <cstdio>

namespace jni
{
	void ReportCallbackFailure(const char * callback, const char * detail) noexcept
	{
		std::fprintf(stderr, "webrtc-java: %s: %s; contained, the callback did not complete\n",
			callback, detail != nullptr ? detail : "");
		std::fflush(stderr);
	}

	bool ClearPendingException(JNIEnv * env, const char * callback) noexcept
	{
		if (!env->ExceptionCheck()) {
			return false;
		}

		// Prints the stack trace and clears the exception. The JVM writes its
		// "Exception in thread ..." prefix straight to stderr, without a line
		// end, when System.err is redirected; the line below then completes it.
		env->ExceptionDescribe();
		env->ExceptionClear();

		std::fprintf(stderr, "webrtc-java: %s: the Java callback raised an exception; "
			"cleared so it cannot unwind into libwebrtc\n", callback);
		std::fflush(stderr);

		return true;
	}
}
