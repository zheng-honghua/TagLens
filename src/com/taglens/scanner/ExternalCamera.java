package com.taglens.scanner;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;

final class ExternalCamera implements AutoCloseable {
    interface Callback {
        boolean wantsFrame();
        void frame(byte[] nv21, int width, int height);
        void ready();
        void failed();
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private boolean closed;

    static CameraManager manager(Context context) {
        return (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    }

    static boolean external(CameraManager manager, String id) throws CameraAccessException {
        Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
        return facing != null && facing == CameraCharacteristics.LENS_FACING_EXTERNAL;
    }

    Size open(Context context, String id, int height, SurfaceHolder holder, Callback callback)
            throws CameraAccessException {
        CameraManager manager = manager(context);
        android.hardware.camera2.params.StreamConfigurationMap outputs = manager.getCameraCharacteristics(id)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (outputs == null) throw new IllegalStateException("No stream configuration");
        Size[] sizes = outputs.getOutputSizes(ImageFormat.YUV_420_888);
        Size[] previews = outputs.getOutputSizes(SurfaceHolder.class);
        if (sizes == null) throw new IllegalStateException("No YUV stream");
        double desired = height * height * 16.0 / 9;
        Size size = Arrays.stream(sizes).filter(s -> s.getWidth() % 2 == 0 && s.getHeight() % 2 == 0)
            .filter(s -> previews == null || Arrays.asList(previews).contains(s))
            .min(Comparator.comparingDouble(s -> Math.abs(Math.log(s.getWidth() * (double)s.getHeight() / desired))))
            .orElseThrow(IllegalStateException::new);
        int w = size.getWidth(), h = size.getHeight();
        holder.setFixedSize(w, h);
        reader = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 2);
        reader.setOnImageAvailableListener(source -> {
            try (Image image = source.acquireLatestImage()) {
                if (image == null || closed || !callback.wantsFrame()) return;
                byte[] pixels = new byte[w*h*3/2];
                Image.Plane[] planes = image.getPlanes();
                copy(planes[0], pixels, 0, w, h, 1);
                copy(planes[2], pixels, w*h, w/2, h/2, 2);
                copy(planes[1], pixels, w*h+1, w/2, h/2, 2);
                callback.frame(pixels, w, h);
            } catch (RuntimeException error) {
                if (!closed) callback.failed();
            }
        }, main);
        manager.openCamera(id, new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice opened) {
                if (closed) { opened.close(); return; }
                device = opened;
                try {
                    Surface preview = holder.getSurface();
                    Surface analysis = reader.getSurface();
                    opened.createCaptureSession(Arrays.asList(preview, analysis), new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession configured) {
                            if (closed) { configured.close(); return; }
                            session = configured;
                            try {
                                CaptureRequest.Builder request = opened.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                request.addTarget(preview);
                                request.addTarget(analysis);
                                configured.setRepeatingRequest(request.build(), null, main);
                                callback.ready();
                            } catch (CameraAccessException | RuntimeException error) { callback.failed(); }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession ignored) { if (!closed) callback.failed(); }
                    }, main);
                } catch (CameraAccessException | RuntimeException error) { callback.failed(); }
            }
            @Override public void onDisconnected(CameraDevice disconnected) { disconnected.close(); if (!closed) callback.failed(); }
            @Override public void onError(CameraDevice failed, int code) { failed.close(); if (!closed) callback.failed(); }
        }, main);
        return size;
    }

    private static void copy(Image.Plane plane, byte[] out, int offset, int w, int h, int step) {
        ByteBuffer input = plane.getBuffer();
        int start = input.position(), stride = plane.getRowStride(), pixel = plane.getPixelStride();
        for (int y = 0; y < h; y++) {
            int from = start + y*stride;
            int to = offset + y*w*step;
            if (pixel == 1 && step == 1) {
                input.position(from);
                input.get(out, to, w);
            } else for (int x = 0; x < w; x++) out[to+x*step] = input.get(from+x*pixel);
        }
    }

    @Override public void close() {
        closed = true;
        if (session != null) session.close();
        if (device != null) device.close();
        if (reader != null) reader.close();
    }
}
