package org.acme.apps;

import java.time.Instant;

import org.opencv.core.Mat;

public class VideoCapturePayload {

    private Mat mat;
    private Instant startCaptureTime;
    private int captureCount;
    public Mat getMat() {
        return mat;
    }
    public void setMat(Mat mat) {
        this.mat = mat;
    }
    public Instant getStartCaptureTime() {
        return startCaptureTime;
    }
    public void setStartCaptureTime(Instant startCaptureTime) {
        this.startCaptureTime = startCaptureTime;
    }
    public int getCaptureCount() {
        return captureCount;
    }
    public void setCaptureCount(int captureCount) {
        this.captureCount = captureCount;
    }
    
}
