// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 Saga IT, LLC

#ifndef JNI_WEBRTC_RTC_RUN_AFTER_CALLBACK_H_
#define JNI_WEBRTC_RTC_RUN_AFTER_CALLBACK_H_

#include "rtc_base/thread.h"

#include <utility>

namespace jni
{
	/**
	 * Runs a teardown task (deleting an observer, releasing a native object)
	 * now, or, when called on a libwebrtc thread, right after the task that is
	 * running on that thread returns.
	 *
	 * A JNI method only runs on a libwebrtc thread when Java code calls back
	 * into this library from inside an observer callback: this JNI never wraps
	 * a Java thread as a webrtc::Thread, and webrtc::Thread::Current() does not
	 * auto-wrap one, so it is null on every Java thread. Freeing the observer
	 * whose callback is on the stack, or the object that invoked it, would free
	 * memory still in use there, so the task waits until the stack unwinds.
	 *
	 * A task still queued when its thread is stopped (PeerConnectionFactory
	 * disposed) runs while the thread drains its queue in Stop(); one posted
	 * after the thread has quit is dropped, which leaks but does not crash.
	 * PeerConnectionFactory.dispose() stops the network thread first, so a
	 * peer connection released during the signaling thread's drain skips its
	 * network-thread cleanup (BlockingCall to a quitting thread returns
	 * without running). That needs close() inside a callback racing the
	 * factory's disposal on another thread.
	 */
	template <typename Task>
	void RunNowOrAfterCallback(Task && task)
	{
		webrtc::Thread * current = webrtc::Thread::Current();

		if (current == nullptr) {
			std::forward<Task>(task)();
		}
		else {
			current->PostTask(std::forward<Task>(task));
		}
	}
}

#endif
