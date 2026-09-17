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

package dev.kastle.webrtc;

import dev.kastle.webrtc.internal.DisposableNativeObject;

/**
 * The PeerConnectionFactory is the main entry point for a WebRTC application.
 * It provides factory methods for {@link RTCPeerConnection}.
 *
 * @author Alex Andres
 */
public class PeerConnectionFactory extends DisposableNativeObject {

	private long networkThreadHandle;

	private long signalingThreadHandle;

	private long workerThreadHandle;


    /**
     * Creates an instance of PeerConnectionFactory.
     *
     * @throws NativeUnavailableException if the WebRTC native library cannot be loaded. The load is
     *                                    attempted here, on the caller's thread, and never from a
     *                                    static initializer: a throwing {@code <clinit>} would poison
     *                                    this class for the rest of the JVM's life.
     */
    public PeerConnectionFactory() {
        NativeLibrary.ensureLoaded();   // throws NativeUnavailableException; never inside <clinit>
        initialize();
    }

	/**
	 * Creates a new {@link RTCPeerConnection}.
	 *
	 * @param config   The peer connection configuration.
	 * @param observer The observer that receives peer connection state
	 *                 changes.
	 *
	 * @return The created peer connection.
	 */
	public native RTCPeerConnection createPeerConnection(
			RTCConfiguration config, PeerConnectionObserver observer);

	@Override
	public native void dispose();

    /**
     * Initializes the native PeerConnectionFactory.
     */
    private native void initialize();

}
