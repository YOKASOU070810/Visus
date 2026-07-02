package com.visus.app.camera;

public interface CameraFrameProvider {
    void start(FrameListener listener);

    void stop();

    interface FrameListener {
        void onFrame(byte[] jpegBytes);
    }
}

