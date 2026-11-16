package com.taglens.scanner;

import android.graphics.Bitmap;
import java.nio.ByteBuffer;

final class NativeEngine implements AutoCloseable {
    static { System.loadLibrary("taglens"); }
    private final long handle = create();
    final MarkerVision markers = new MarkerVision();

    float[] scanLuma(ByteBuffer plane, int width, int height, int family, int threads, boolean board) {
        return MarkerVision.aruco(family) ? markers.scanY(plane, width, height, family) : detectY(handle, plane, width, height, family, threads, 1.5f, board);
    }

    float[] scanPhoto(Bitmap bitmap, int family, int threads, boolean board) {
        return MarkerVision.aruco(family) ? markers.scanBitmap(bitmap, family, false)
            : detectBitmap(handle, bitmap, family, threads, 1f, board);
    }

    float[] scanPhotoAlternate(Bitmap bitmap, int family, int threads, boolean board) {
        return MarkerVision.aruco(family) ? markers.scanBitmap(bitmap, family, true)
            : detectBitmap(handle, bitmap, family, threads, 1.5f, board);
    }

    @Override public void close() { destroy(handle); }

    private static native long create();
    private static native void destroy(long handle);
    private static native float[] detectY(long handle, ByteBuffer plane, int width, int height,
                                         int family, int threads, float decimate, boolean board);
    private static native float[] detectBitmap(long handle, Bitmap bitmap, int family,
                                              int threads, float decimate, boolean board);
}
