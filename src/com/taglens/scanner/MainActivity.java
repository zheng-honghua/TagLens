package com.taglens.scanner;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.Toast;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("deprecation")
public final class MainActivity extends Activity {
    private static final String[] FAMILIES = MarkerVision.FAMILIES;
    private static final int CORNER = 16;
    private static final int MOTION = 190;
    private int BG, PANEL, INK, MUTED, ACCENT, ON_ACCENT, VIEW_BG;
    private SharedPreferences preferences;
    private FrameLayout shell;
    private LinearLayout root;
    private View sheetLayer;
    private LinearLayout resultsPane;
    private int selectedFamily, themeIndex, liveThreads, photoThreads, liveSize;
    private boolean boardRecovery, showOverlay;
    // Zero means follow the theme. User-selected colors are always opaque.
    private int customOverlayColor;
    private static final int ASK_CAMERA = 10, OPEN_IMAGE = 11;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean framePending = new AtomicBoolean();
    private volatile int revision;
    private NativeEngine engine; // Access only from the scan executor.
    private ByteBuffer luma; // Reused by the scan executor.
    private FrameLayout viewfinder;
    private SurfaceView surface;
    private ScanOverlay overlay;
    private TextView status, readout;
    private Button liveButton, captureButton, resultButton, boardButton;
    private TextView modeTitle;
    private Button family;
    private Camera camera;
    private ExternalCamera externalCamera;
    private String cameraId;
    private int externalWidth, externalHeight;
    private int mode;
    private boolean hybridBoard;
    private Calibration.Board board;
    private final Calibration calibration = new Calibration();
    private boolean calibrationBusy;
    private Calibration.Sample previewSample;
    private String previewCalibrationStatus;
    private long nextCalibrationCheck, calibrationFeedbackUntil;
    private String calibrationJson, calibrationSource;
    private boolean cameraActive() { return camera != null || externalCamera != null; }
    private Camera.Size previewSize;
    private int rotation;
    private boolean surfaceReady, startPending, foreground, capturing, photoBusy, resumeAfterPicker;
    private ScanResult result;
    private Bitmap still;
    // The camera cannot reuse this buffer until a newer result replaces it.
    private PreviewFrame lastPreview;
    private PhotoScanner.Input lastPhoto;
    private long previousResultAt;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("taglens-settings", MODE_PRIVATE);
        themeIndex = preferences.getInt("theme", 0);
        selectedFamily = Math.max(0, Math.min(FAMILIES.length - 1, preferences.getInt("family", 0)));
        liveThreads = preferences.getInt("liveThreads", Math.min(4, Runtime.getRuntime().availableProcessors()));
        photoThreads = preferences.getInt("photoThreads", Math.min(6, Runtime.getRuntime().availableProcessors()));
        liveSize = preferences.getInt("liveSize", 720);
        boardRecovery = preferences.getBoolean("board", true);
        showOverlay = preferences.getBoolean("overlay", true);
        customOverlayColor = preferences.getInt("overlayColor", 0);
        cameraId = preferences.getString("cameraId", "");
        mode = preferences.getInt("mode", 0);
        hybridBoard = preferences.getBoolean("hybridBoard", false);
        board = loadBoard();
        if (board.hybrid && !MarkerVision.hybridSupported(selectedFamily)) selectedFamily=MarkerVision.CHARUCO_DEFAULT;
        applyPalette();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
    }

    private void applyPalette() {
        switch (themeIndex) {
            case 1:
                BG = 0xfff8f8f8; PANEL = 0xffeaeaea; INK = 0xff171717; MUTED = 0xff555555;
                ACCENT = 0xff171717; ON_ACCENT = 0xffffffff; VIEW_BG = 0xffeeeeee;
                break;
            case 2:
                BG = 0xff090909; PANEL = 0xff202020; INK = 0xfff7f7f7; MUTED = 0xffbdbdbd;
                ACCENT = 0xfff7f7f7; ON_ACCENT = 0xff141414; VIEW_BG = 0xff151515;
                break;
            default:
                BG = 0xff111923; PANEL = 0xff1c2b3b; INK = 0xfff2f7fc; MUTED = 0xffa9bbcc;
                ACCENT = 0xffbff38c; ON_ACCENT = BG; VIEW_BG = 0xff0c1621;
        }
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        int flags = themeIndex == 1 ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0;
        if (themeIndex == 1 && android.os.Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private void buildUi() {
        sheetLayer = null;
        resultsPane = null;
        surfaceReady = false;
        shell = new FrameLayout(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(10));
        root.setBackgroundColor(BG);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        modeTitle = text("TagLens", 23, INK);
        modeTitle.setTypeface(null, Typeface.BOLD);
        modeTitle.setSingleLine();
        modeTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(modeTitle, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button settings = button("设置", false);
        settings.setContentDescription("扫描设置");
        settings.setOnClickListener(view -> showSettings());
        Button example = button("示例图", false);
        example.setOnClickListener(view -> showExample());
        for (Button action : new Button[]{example, settings}) {
            action.setPadding(dp(12), 0, dp(12), 0);
            action.setMinWidth(dp(56));
            action.setSingleLine(true);
            action.setStateListAnimator(null);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(48));
            params.setMarginStart(dp(12));
            header.addView(action, params);
        }
        root.addView(header);

        family = button(FAMILIES[selectedFamily] + "  ▾", false);
        family.setContentDescription("标签家族：" + FAMILIES[selectedFamily]);
        family.setOnClickListener(view -> showFamilyPicker());
        LinearLayout.LayoutParams familyParams = new LinearLayout.LayoutParams(-1, dp(46));
        familyParams.setMargins(0, dp(8), 0, dp(8));
        root.addView(family, familyParams);
        boardButton = button("标定板参数", false);
        boardButton.setOnClickListener(view -> editBoard());
        LinearLayout.LayoutParams boardParams = new LinearLayout.LayoutParams(-1, dp(46));
        boardParams.setMargins(0, dp(8), 0, dp(8));
        root.addView(boardButton, boardParams);

        viewfinder = new FrameLayout(this);
        viewfinder.setBackground(shape(VIEW_BG, CORNER));
        viewfinder.setClipToOutline(true);
        surface = new SurfaceView(this);
        surface.setVisibility(View.INVISIBLE);
        viewfinder.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        overlay = new ScanOverlay();
        viewfinder.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        status = text("对准 AprilTag，或导入整块标签板", 13, INK);
        status.setPadding(dp(9), dp(5), dp(9), dp(5));
        status.setBackground(shape(PANEL, CORNER));
        FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        badge.setMargins(dp(10), dp(10), dp(10), 0);
        viewfinder.addView(status, badge);
        root.addView(viewfinder, new LinearLayout.LayoutParams(-1, 0, 1));
        viewfinder.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if (r-l != or-ol || b-t != ob-ot) fitCameraSurface();
        });
        SurfaceView boundSurface = surface;
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                if (surface != boundSurface) return;
                surfaceReady = true;
                if (startPending) openCamera();
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                if (surface != boundSurface) return;
                surfaceReady = false;
                releaseCamera();
            }
        });

        readout = text("本地识别 · ARM64", 13, MUTED);
        readout.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(readout, new LinearLayout.LayoutParams(-1, dp(36)));

        LinearLayout controls = new LinearLayout(this);
        Button importButton = button("导入照片", false);
        importButton.setOnClickListener(view -> openImage());
        liveButton = button("开启摄像头", true);
        liveButton.setOnClickListener(view -> {
            if (cameraActive() || startPending) {
                releaseCamera();
                if (mode == 0) status.setText(result == null ? "待扫描" : "已保留识别结果");
                else if (previewSample == null && previewCalibrationStatus != null)
                    status.setText(previewCalibrationStatus);
                else status.setText("取景已停止 · 已采集 " + calibration.count() + " 张");
            } else startCamera();
        });
        captureButton = button(mode == 0 ? "拍照精扫" : "采集样本", false);
        captureButton.setEnabled(false);
        captureButton.setOnClickListener(view -> capturePhoto());
        addControl(controls, importButton, 1);
        addControl(controls, liveButton, 1.35f);
        addControl(controls, captureButton, 1);
        root.addView(controls);

        resultButton = button("查看结果", false);
        resultButton.setOnClickListener(view -> { if (mode == 0) showResults(); else showCalibration(calibration.count() >= 8 && calibrationJson == null); });
        LinearLayout.LayoutParams resultParams = new LinearLayout.LayoutParams(-1, dp(46));
        resultParams.topMargin = dp(8);
        root.addView(resultButton, resultParams);
        shell.addView(root, new FrameLayout.LayoutParams(-1, -1));
        setContentView(shell);
        syncModeControls();
        status.setText(idleStatus());
        updateResult();
    }

    private void addControl(LinearLayout row, Button button, float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(52), weight);
        if (row.getChildCount() > 0) params.leftMargin = dp(6);
        row.addView(button, params);
    }

    private TextView text(String content, int sp, int color) {
        TextView text = new TextView(this);
        text.setText(content);
        text.setTextSize(sp);
        text.setTextColor(color);
        text.setGravity(Gravity.CENTER_VERTICAL);
        return text;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setPadding(dp(7), 0, dp(7), 0);
        button.setTextColor(primary ? ON_ACCENT : INK);
        styleButton(button, primary);
        button.setMinHeight(dp(44));
        button.setMinWidth(dp(44));
        return button;
    }

    private void styleButton(Button button, boolean primary) {
        button.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(primary ? 0x33000000 : 0x33ffffff),
            shape(primary ? ACCENT : PANEL, CORNER), null));
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void startCamera() {
        closeResults();
        if (mode != 0 && !(board.size > 0)) { editBoard(); return; }
        previewSample = null;
        previewCalibrationStatus = null;
        nextCalibrationCheck = 0;
        calibrationFeedbackUntil = 0;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, ASK_CAMERA);
            return;
        }
        revision++;
        photoBusy = false;
        lastPhoto = null;
        setStill(null);
        result = null;
        updateResult();
        status.setText("正在开启摄像头");
        liveButton.setText("取消");
        startPending = true;
        surface.setVisibility(View.VISIBLE);
        if (surfaceReady) openCamera();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request != ASK_CAMERA) return;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else status.setText("请在系统应用权限中允许相机，也可导入照片");
    }

    private void openCamera() {
        if (!startPending || !surfaceReady || !foreground || cameraActive()) return;
        startPending = false;
        if (!cameraId.isEmpty()) { openExternal(); return; }
        try {
            surface.getHolder().setSizeFromLayout();
            int back = rearCamera();
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(back, info);
            rotation = info.orientation;
            camera = Camera.open(back);
            Camera.Parameters parameters = camera.getParameters();
            previewSize = choosePreview(parameters.getSupportedPreviewSizes());
            parameters.setPreviewSize(previewSize.width, previewSize.height);
            parameters.setPreviewFormat(ImageFormat.NV21);
            List<String> focusModes = parameters.getSupportedFocusModes();
            if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }
            List<Camera.Size> pictures = parameters.getSupportedPictureSizes();
            if (pictures != null && !pictures.isEmpty()) {
                Camera.Size full = pictures.stream().max(Comparator.comparingLong(s -> (long)s.width * s.height)).get();
                parameters.setPictureSize(full.width, full.height);
            }
            parameters.setJpegQuality(95);
            parameters.setRotation(rotation);
            camera.setParameters(parameters);
            camera.setDisplayOrientation(rotation);
            fitCameraSurface();
            camera.setPreviewDisplay(surface.getHolder());
            int bytes = previewSize.width * previewSize.height * 3 / 2;
            for (int i = 0; i < 3; i++) camera.addCallbackBuffer(new byte[bytes]);
            camera.setPreviewCallbackWithBuffer(this::onFrame);
            camera.startPreview();
            liveButton.setText(stopLabel());
            captureButton.setEnabled(mode == 0);
            status.setText(mode == 0 ? "实时扫描 · " + liveThreads + " 线程" : "对准标定板，正在检测角点");
            previousResultAt = 0;
        } catch (IOException | RuntimeException error) {
            releaseCamera();
            status.setText("摄像头无法开启，可先导入照片");
        }
    }

    private void openExternal() {
        ExternalCamera selected = new ExternalCamera();
        externalCamera = selected;
        try {
            rotation = 0;
            android.util.Size size = selected.open(this, cameraId, liveSize, surface.getHolder(),
                new ExternalCamera.Callback() {
                    @Override public boolean wantsFrame() {
                        return externalCamera == selected && !framePending.get() && !capturing
                            && (mode == 0 || android.os.SystemClock.elapsedRealtime() >= nextCalibrationCheck);
                    }
                    @Override public void frame(byte[] data, int width, int height) {
                        if (externalCamera == selected) onFrame(data, null);
                    }
                    @Override public void ready() {
                        if (externalCamera != selected) return;
                        liveButton.setText(stopLabel());
                        captureButton.setEnabled(mode == 0);
                        status.setText(mode == 0 ? "USB 摄像头 · 实时扫描" : "USB 摄像头 · 正在检测标定角点");
                    }
                    @Override public void failed() {
                        if (externalCamera != selected) return;
                        releaseCamera();
                        status.setText("USB 摄像头断开或无法打开，请在设置中重选");
                    }
                });
            externalWidth = size.getWidth();
            externalHeight = size.getHeight();
            fitCameraSurface();
        } catch (android.hardware.camera2.CameraAccessException | RuntimeException error) {
            releaseCamera();
            status.setText("无法打开 USB 摄像头，请检查连接和系统支持");
        }
    }

    private int rearCamera() {
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return i;
        }
        throw new IllegalStateException("No rear camera");
    }

    private Camera.Size choosePreview(List<Camera.Size> sizes) {
        double targetPixels = liveSize * liveSize * 16.0 / 9;
        return sizes.stream().min(Comparator.comparingDouble(size ->
            Math.abs(Math.log((size.width * (double)size.height) / targetPixels))
              + Math.abs(size.width / (double)size.height - 16.0 / 9) * .3)).get();
    }

    private void fitCameraSurface() {
        if ((previewSize == null && externalCamera == null) || viewfinder.getWidth() == 0 || viewfinder.getHeight() == 0) return;
        boolean swap = rotation % 180 != 0;
        int width = externalCamera != null ? externalWidth : swap ? previewSize.height : previewSize.width;
        int height = externalCamera != null ? externalHeight : swap ? previewSize.width : previewSize.height;
        float scale = Math.min(viewfinder.getWidth() / (float)width, viewfinder.getHeight() / (float)height);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            Math.round(width * scale), Math.round(height * scale), Gravity.CENTER);
        surface.setLayoutParams(params);
    }

    private NativeEngine engine() {
        if (engine == null) engine = new NativeEngine();
        return engine;
    }

    private void onFrame(byte[] nv21, Camera source) {
        if (nv21 == null || source != camera || !cameraActive() || capturing) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if ((mode != 0 && now < nextCalibrationCheck) || !framePending.compareAndSet(false, true)) {
            if (source != null) source.addCallbackBuffer(nv21);
            return;
        }
        int token = revision, selected = selectedFamily, threads = liveThreads;
        boolean recover = boardRecovery;
        int width = source == null ? externalWidth : previewSize.width;
        int height = source == null ? externalHeight : previewSize.height;
        int angle = rotation, scanMode = mode;
        Calibration.Board settings = board;
        if (scanMode != 0) nextCalibrationCheck = now + 350;
        worker.execute(() -> {
            try {
                if (token != revision) return;
                long start = System.nanoTime();
                List<ScanResult.Tag> tags = new java.util.ArrayList<>();
                Calibration.Sample corners = null;
                String boardFeedback = null;
                if (scanMode == 1) {
                    corners = Calibration.chessPreview(nv21, width, height, settings);
                } else if (scanMode == 2 && settings.hybrid) {
                    corners = engine().markers.hybridY(nv21, width, height, selected, settings);
                } else {
                    int pixels = width * height;
                    if (luma == null || luma.capacity() < pixels) luma = ByteBuffer.allocateDirect(pixels);
                    luma.clear();
                    luma.put(nv21, 0, pixels);
                    tags = ScanResult.decode(engine().scanLuma(luma, width, height, selected, threads,
                        recover && scanMode == 0));
                }
                if (scanMode == 2 && !settings.hybrid) {
                    try {
                        corners = Calibration.april(new ScanResult(width, height, FAMILIES[selected],
                            tags, start, false), settings);
                    } catch (IllegalArgumentException rejected) {
                        boardFeedback = rejected.getMessage();
                        if (selected == 0 && !(rejected instanceof Calibration.BoardOriginException)) {
                            List<ScanResult.Tag> dense = ScanResult.decode(
                                engine().markers.denseAprilY(luma, width, height));
                            try {
                                corners = Calibration.april(new ScanResult(width, height, FAMILIES[selected],
                                    dense, start, false), settings);
                                tags = dense;
                            } catch (IllegalArgumentException denseRejected) {
                                if (denseRejected instanceof Calibration.BoardOriginException || dense.size() > tags.size()) {
                                    tags = dense;
                                    boardFeedback = denseRejected.getMessage();
                                }
                            }
                        }
                    }
                }
                int orientation = angle == 90 ? 6 : angle == 180 ? 3 : angle == 270 ? 8 : 1;
                Matrix matrix = PhotoScanner.normalizedOrientation(orientation, width, height);
                for (ScanResult.Tag tag : tags) tag.transform(matrix);
                boolean swap = angle % 180 != 0;
                ScanResult next = new ScanResult(swap ? height : width, swap ? width : height,
                    FAMILIES[selected], tags, start, false);
                final Calibration.Sample sample = Calibration.orient(corners, matrix, next.width, next.height);
                final String feedback = boardFeedback;
                runOnUiThread(() -> {
                    if (token != revision || source != camera || capturing) return;
                    PreviewFrame previous = lastPreview;
                    lastPreview = new PreviewFrame(nv21, width, height, orientation);
                    result = next;
                    previewSample = sample;
                    if (scanMode != 0) {
                        captureButton.setEnabled(sample != null && !calibrationBusy);
                        previewCalibrationStatus = sample == null
                            ? feedback != null ? feedback
                                : scanMode == 1 ? "寻找完整棋盘格 · 请将整块板放入画面" : "寻找标定板 · 请核对家族和板型"
                            : "已检测 " + sample.image.length + " 个角点 · 可采集";
                        if (!calibrationBusy && android.os.SystemClock.elapsedRealtime() >= calibrationFeedbackUntil)
                            status.setText(previewCalibrationStatus);
                    }
                    if (previous != null && source != null) source.addCallbackBuffer(previous.pixels);
                    updateResult();
                });
            } catch (RuntimeException | LinkageError error) {
                runOnUiThread(() -> {
                    if (token == revision && source == camera) {
                        releaseCamera();
                        status.setText(scanMode == 0 ? "识别引擎无法运行" : "角点检测无法运行，可先导入照片试验");
                    }
                });
            } finally {
                runOnUiThread(() -> {
                    try {
                        if (source != null && source == camera && !capturing &&
                            (lastPreview == null || lastPreview.pixels != nv21)) {
                            source.addCallbackBuffer(nv21);
                        }
                    } finally {
                        framePending.set(false);
                    }
                });
            }
        });
    }

    private void capturePhoto() {
        if (mode != 0) {
            if (!calibrationBusy && cameraActive() && previewSample != null)
                acceptSample(previewSample, cameraId.isEmpty() ? "内置后置" : "USB " + cameraId);
            return;
        }
        if (externalCamera != null) {
            PreviewFrame frame = lastPreview;
            if (frame == null) { status.setText("请等取景画面稳定后再采集"); return; }
            // The callback buffer can be reused while this job is running.
            PreviewFrame copy = new PreviewFrame(frame.pixels.clone(), frame.width, frame.height, frame.orientation);
            {
                Bitmap bitmap = copy.toBitmap();
                if (bitmap == null) { status.setText("当前帧无法读取"); return; }
                ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded);
                bitmap.recycle();
                byte[] data = encoded.toByteArray();
                scanPhoto(() -> new ByteArrayInputStream(data));
            }
            return;
        }
        Camera active = camera;
        if (active == null || capturing) return;
        capturing = true;
        captureButton.setEnabled(false);
        status.setText("正在拍摄原图");
        try {
            active.takePicture(null, null, (jpeg, source) -> {
                if (source != camera) return;
                if (jpeg == null || jpeg.length == 0) {
                    releaseCamera();
                    status.setText("拍照失败，请重试");
                } else scanPhoto(() -> new ByteArrayInputStream(jpeg));
            });
        } catch (RuntimeException error) {
            releaseCamera();
            status.setText("拍照失败，请重新开启摄像头");
        }
    }

    private void releaseCamera() {
        startPending = false;
        if (cameraActive()) {
            revision++;
            ExternalCamera usb = externalCamera;
            externalCamera = null;
            if (usb != null) usb.close();
            Camera active = camera;
            PreviewFrame snapshot = lastPreview;
            lastPreview = null;
            camera = null;
            if (active != null) {
                try { active.setPreviewCallbackWithBuffer(null); } catch (RuntimeException ignored) {}
                try { active.stopPreview(); } catch (RuntimeException ignored) {}
                try { active.release(); } catch (RuntimeException ignored) {}
            }
            if (snapshot != null && result != null) {
                try { setStill(snapshot.toBitmap()); }
                catch (RuntimeException error) { android.util.Log.w("TagLens", "Could not freeze preview", error); }
            }
        }
        capturing = false;
        liveButton.setText(startLabel());
        captureButton.setEnabled(false);
        surface.setVisibility(View.INVISIBLE);
        overlay.invalidate();
    }

    private void scanPhoto(PhotoScanner.Input input) {
        closeResults();
        releaseCamera();
        int token = ++revision;
        int selected = selectedFamily, threads = photoThreads;
        boolean recover = boardRecovery;
        lastPhoto = input;
        setStill(null);
        result = null;
        photoBusy = true;
        updateResult();
        status.setText("原图分块精扫 · " + photoThreads + " 线程");
        worker.execute(() -> {
            try {
                if (token != revision) return;
                PhotoScanner.Output output = PhotoScanner.scan(input, engine(), selected, FAMILIES[selected],
                    threads, recover, () -> token != revision,
                    percent -> runOnUiThread(() -> {
                        if (token == revision) status.setText("原图精扫 · " + percent + "%");
                    }));
                runOnUiThread(() -> {
                    if (token != revision || isDestroyed()) {
                        output.preview.recycle();
                        return;
                    }
                    photoBusy = false;
                    setStill(output.preview);
                    result = output.result;
                    status.setText("精扫完成 · " + result.tags.size() + " 个");
                    updateResult();
                });
            } catch (CancellationException ignored) {
            } catch (IOException | RuntimeException | LinkageError error) {
                runOnUiThread(() -> {
                    if (token != revision) return;
                    photoBusy = false;
                    status.setText(error instanceof IOException && error.getMessage() != null
                        ? error.getMessage() : "识别失败，请重试");
                    updateResult();
                });
            }
        });
    }

    private void setStill(Bitmap bitmap) {
        still = bitmap;
        overlay.invalidate();
    }

    private void updateResult() {
        overlay.invalidate();
        int count = result == null ? 0 : result.tags.size();
        if (mode != 0) {
            int samples = calibration.count();
            resultButton.setText(calibrationJson != null ? "标定结果 · " + samples + " 张"
                : samples < 8 ? "标定样本 · " + samples + " / 8 张" : "计算标定 · " + samples + " 张");
            if (mode == 2 && !board.hybrid && count > 0 && previewSample == null
                && previewCalibrationStatus != null) {
                readout.setText("读到 " + count + " 个标签 · 当前画面未通过板型校验");
            } else {
                readout.setText(samples < 8 ? "移动或倾斜标定板，采集 8 张不同姿态" : "可计算内参与畸变，也可以继续采集");
            }
            return;
        }
        resultButton.setText(resultsPane != null ? "返回取景" : count == 0 ? "查看结果" : "查看结果 · " + count + " 个");
        if (result == null) {
            readout.setText(photoBusy ? "正在扫描整张图片" : "本地识别 · ARM64");
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        String fps = camera != null && previousResultAt != 0
            ? String.format(Locale.ROOT, "  ·  %.1f 次/秒", 1000f / Math.max(1, now - previousResultAt)) : "";
        previousResultAt = camera != null ? now : 0;
        readout.setText(String.format(Locale.ROOT, "%d 个标签  ·  %.0f ms%s",
            count, result.milliseconds, fps));
    }

    private void openImage() {
        resumeAfterPicker = cameraActive();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, OPEN_IMAGE);
    }

    private InputStream openUri(Uri uri) throws IOException {
        InputStream stream = getContentResolver().openInputStream(uri);
        if (stream == null) throw new IOException("无法读取所选图片");
        return stream;
    }

    @Override protected void onActivityResult(int request, int response, Intent data) {
        super.onActivityResult(request, response, data);
        if (request == OPEN_IMAGE) {
            boolean resume = resumeAfterPicker;
            resumeAfterPicker = false;
            if (response == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                if (mode == 0) scanPhoto(() -> openUri(uri));
                else collectCalibration(uri);
            } else if (resume) startCamera();

        }
    }

    private void showResults() {
        if (resultsPane != null) {
            closeResults();
            return;
        }
        if (photoBusy) {
            status.setText("正在精扫，完成后可查看结果");
            return;
        }
        final ScanResult saved = result;
        if (cameraActive() || startPending) {
            releaseCamera();
            status.setText("已保留识别结果");
        }
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setPadding(dp(12), dp(10), dp(12), dp(12));
        pane.setBackground(shape(BG, CORNER));
        pane.setClickable(true);
        int count = saved == null ? 0 : saved.tags.size();
        TextView heading = text("识别结果   " + count + " 个", 18, INK);
        heading.setTypeface(null, Typeface.BOLD);
        pane.addView(heading, new LinearLayout.LayoutParams(-1, dp(44)));
        if (count == 0) {
            pane.addView(text("还没有可显示的标签。开启摄像头或导入照片后再查看。", 14, MUTED));
        } else {
            TextView details = text(saved.family + "   ·   点击标签展开四个角点", 13, MUTED);
            pane.addView(details, new LinearLayout.LayoutParams(-1, dp(32)));
            ListView list = new ListView(this);
            list.setDivider(null);
            list.setAdapter(new BaseAdapter() {
                private int expanded = -1;
                @Override public int getCount() { return saved.tags.size(); }
                @Override public Object getItem(int position) { return saved.tags.get(position); }
                @Override public long getItemId(int position) { return position; }
                @Override public View getView(int position, View reusable, ViewGroup parent) {
                    ScanResult.Tag tag = saved.tags.get(position);
                    LinearLayout card = reusable instanceof LinearLayout ? (LinearLayout) reusable : new LinearLayout(MainActivity.this);
                    card.removeAllViews();
                    card.setOrientation(LinearLayout.VERTICAL);
                    card.setPadding(dp(10), dp(3), dp(10), dp(6));
                    Button title = button(String.format(Locale.ROOT, "ID %d   ·   %.1f°   %s", tag.id, tag.angle(),
                        expanded == position ? "▴" : "▾"), false);
                    title.setGravity(Gravity.CENTER_VERTICAL);
                    title.setContentDescription("ID " + tag.id + "，" + (expanded == position ? "收起角点" : "展开角点"));
                    card.addView(title, new LinearLayout.LayoutParams(-1, dp(44)));
                    TextView coords = text(cornerText(tag), 13, MUTED);
                    coords.setPadding(dp(7), dp(7), 0, dp(6));
                    coords.setVisibility(expanded == position ? View.VISIBLE : View.GONE);
                    card.addView(coords);
                    title.setOnClickListener(view -> {
                        if (expanded == position) {
                            expanded = -1;
                            coords.animate().alpha(0).setDuration(MOTION).withEndAction(() -> {
                                if (expanded != position) notifyDataSetChanged();
                            }).start();
                        } else {
                            expanded = position;
                            notifyDataSetChanged();
                            list.post(() -> {
                                int index = position - list.getFirstVisiblePosition();
                                if (index >= 0 && index < list.getChildCount()) {
                                    View row = list.getChildAt(index);
                                    View info = ((LinearLayout)row).getChildAt(1);
                                    info.setAlpha(0);
                                    info.animate().alpha(1).setDuration(MOTION).start();
                                }
                            });
                        }
                    });
                    return card;
                }
            });
            pane.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout actions = new LinearLayout(this);
            Button copy = button("复制结果", false);
            Button share = button("分享结果", true);
            copy.setOnClickListener(view -> recognitionAction(saved, false));
            share.setOnClickListener(view -> recognitionAction(saved, true));
            addControl(actions, copy, 1);
            addControl(actions, share, 1);
            pane.addView(actions);
        }
        resultsPane = pane;
        viewfinder.addView(pane, new FrameLayout.LayoutParams(-1, -1));
        reveal(pane, dp(10));
        resultButton.setText("返回取景");
        heading.announceForAccessibility("识别结果，" + count + " 个");
    }

    private String cornerText(ScanResult.Tag tag) {
        StringBuilder points = new StringBuilder(String.format(Locale.ROOT, "中心  (%.1f, %.1f) px\n",
            tag.x, tag.y));
        for (int p = 0; p < 4; p++) points.append(String.format(Locale.ROOT,
            "P%d  (%.1f, %.1f)%s", p, tag.corners[p * 2], tag.corners[p * 2 + 1], p == 3 ? "" : "\n"));
        return points.toString();
    }

    private void closeResults() {
        LinearLayout pane = resultsPane;
        if (pane == null) return;
        resultsPane = null;
        fadeAway(pane, dp(10));
        updateResult();
    }

    private void reveal(View view, float distance) {
        view.setAlpha(0);
        view.setTranslationY(distance);
        view.animate().alpha(1).translationY(0).setDuration(MOTION).start();
    }

    private void fadeAway(View view, float distance) {
        view.animate().cancel();
        view.setClickable(false);
        view.animate().alpha(0).translationY(distance).setDuration(MOTION).withEndAction(() -> {
            if (view.getParent() instanceof ViewGroup) ((ViewGroup)view.getParent()).removeView(view);
        }).start();
    }

    private LinearLayout sheet(String title) {
        if (sheetLayer != null) closeSheet(false);
        FrameLayout layer = new FrameLayout(this);
        layer.setBackgroundColor(0x76000000);
        layer.setClickable(true);
        layer.setOnClickListener(view -> closeSheet(true));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        card.setBackground(shape(BG, CORNER));
        card.setPadding(dp(14), dp(10), dp(14), dp(14));
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text(title, 19, INK);
        label.setTypeface(null, Typeface.BOLD);
        bar.addView(label, new LinearLayout.LayoutParams(0, dp(44), 1));
        Button close = button("关闭", false);
        close.setOnClickListener(view -> closeSheet(true));
        bar.addView(close);
        card.addView(bar);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        int maxHeight = Math.min(dp(510), Math.max(dp(180), shell.getHeight() - dp(140)));
        card.addView(scroll, new LinearLayout.LayoutParams(-1, maxHeight));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        params.setMargins(dp(8), 0, dp(8), dp(8));
        layer.addView(card, params);
        shell.addView(layer, new FrameLayout.LayoutParams(-1, -1));
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        sheetLayer = layer;
        reveal(layer, 0);
        reveal(card, dp(20));
        close.requestFocus();
        return body;
    }

    private void closeSheet(boolean animated) {
        View layer = sheetLayer;
        if (layer == null) return;
        sheetLayer = null;
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        if (animated) fadeAway(layer, dp(12));
        else shell.removeView(layer);
    }

    private void showFamilyPicker() {
        LinearLayout body = sheet("标签家族");
        for (int i = 0; i < FAMILIES.length; i++) {
            final int choice = i;
            if (board.hybrid && !MarkerVision.hybridSupported(i)) continue;
            addRow(body, FAMILIES[i] + (i == selectedFamily ? "   ✓" : ""), i == selectedFamily, () -> {
                closeSheet(true);
                chooseFamily(choice);
                if (mode == 0) showExample();
            });
        }
    }

    private void chooseFamily(int index) {
        if (index == selectedFamily) return;
        if (mode == 2) resetCalibration();
        selectedFamily = index;
        preferences.edit().putInt("family", index).apply();
        family.setText(FAMILIES[index] + "  ▾");
        family.setContentDescription("标签家族：" + FAMILIES[index]);
        closeResults();
        if (lastPhoto != null) scanPhoto(lastPhoto);
        else {
            revision++;
            PreviewFrame previous = lastPreview;
            lastPreview = null;
            if (previous != null && camera != null && !capturing) camera.addCallbackBuffer(previous.pixels);
            result = null;
            setStill(null);
            updateResult();
        }
    }

    private void addRow(LinearLayout body, String label, boolean selected, Runnable action) {
        Button row = button(label, selected);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(46));
        params.topMargin = dp(6);
        body.addView(row, params);
        row.setOnClickListener(view -> action.run());
    }

    private void choiceRow(LinearLayout body, String label, String[] labels, int checked,
                           java.util.function.IntConsumer apply) {
        TextView heading = text(label, 13, MUTED);
        heading.setPadding(0, dp(9), 0, dp(3));
        body.addView(heading);
        LinearLayout choices = new LinearLayout(this);
        Button[] buttons = new Button[labels.length];
        for (int i = 0; i < labels.length; i++) {
            int index = i;
            Button option = button(labels[i], i == checked);
            buttons[i] = option;
            addControl(choices, option, 1);
            option.setSelected(i == checked);
            option.setOnClickListener(view -> {
                apply.accept(index);
                for (int j = 0; j < buttons.length; j++) {
                    Button current = buttons[j];
                    current.setSelected(j == index);
                    current.setTextColor(j == index ? ON_ACCENT : INK);
                    styleButton(current, j == index);
                }
            });
        }
        body.addView(choices);
    }

    private void toggleRow(LinearLayout body, String label, boolean checked,
                           android.widget.CompoundButton.OnCheckedChangeListener listener) {
        Switch toggle = new Switch(this);
        toggle.setText(label);
        toggle.setTextColor(INK);
        toggle.setTextSize(14);
        toggle.setPadding(dp(8), 0, dp(8), 0);
        toggle.setMinHeight(dp(48));
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener(listener);
        body.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
    }

    private void showSettings() {
        LinearLayout body = sheet("扫描设置");
        addRow(body, "模式 · " + Calibration.MODES[mode], false, this::chooseMode);
        addRow(body, cameraId.isEmpty() ? "摄像头 · 内置后置" : "摄像头 · USB " + cameraId, false, this::chooseCamera);
        if (mode != 0) addRow(body, "标定板参数", false, this::editBoard);
        choiceRow(body, "外观", new String[]{"深蓝绿", "白底", "黑底"}, themeIndex, this::changeTheme);
        int[] sizes = {480, 720, 1080};
        choiceRow(body, "实时分辨率 · 下次开启摄像头生效", new String[]{"480p", "720p", "1080p"},
            liveSize == 1080 ? 2 : liveSize == 480 ? 0 : 1, index -> {
                if (liveSize != sizes[index]) resetCalibration();
                liveSize = sizes[index];
                preferences.edit().putInt("liveSize", liveSize).apply();
            });
        int[] threadOptions = {2, 4, 6};
        choiceRow(body, "实时检测线程", new String[]{"2", "4", "6"},
            liveThreads == 6 ? 2 : liveThreads == 2 ? 0 : 1, index -> {
                liveThreads = threadOptions[index];
                preferences.edit().putInt("liveThreads", liveThreads).apply();
                if (camera != null) status.setText("实时扫描 · " + liveThreads + " 线程");
            });
        choiceRow(body, "拍照精扫线程", new String[]{"2", "4", "6"},
            photoThreads == 6 ? 2 : photoThreads == 2 ? 0 : 1, index -> {
                photoThreads = threadOptions[index];
                preferences.edit().putInt("photoThreads", photoThreads).apply();
            });
        toggleRow(body, "密集标定板增强 · tag36h11", boardRecovery, (view, enabled) -> {
            boardRecovery = enabled;
            preferences.edit().putBoolean("board", enabled).apply();
        });
        toggleRow(body, "显示边框和方向", showOverlay, (view, enabled) -> {
            showOverlay = enabled;
            preferences.edit().putBoolean("overlay", enabled).apply();
            overlay.invalidate();
        });
        addRow(body, "识别框颜色   " + colorLabel() + "  ›", false, this::showOverlayColors);
        addRow(body, "试扫 16 × 16 标定板", false, () -> scanExample("board-16x16.png"));
        addRow(body, "试扫 16 × 16 同 ID 阵列", false, () -> scanExample("array-16x16.png"));
        addRow(body, "试扫 144 码示例", false, () -> scanExample("array-12x12.png"));
    }

    private void scanExample(String asset) {
        closeSheet(true);
        setMode(0);
        lastPhoto = null;
        chooseFamily(0);
        scanPhoto(() -> getAssets().open(asset));
    }

    private String boardKey() { return mode == 2 && hybridBoard ? "board2hybrid" : "board"+mode; }

    private Calibration.Board loadBoard() {
        String key = boardKey();
        boolean hybrid = mode == 2 && hybridBoard;
        return new Calibration.Board(mode, preferences.getInt(key+"rows", mode == 1 ? 8 : hybrid ? 10 : 16),
            preferences.getInt(key+"cols", mode == 1 ? 11 : hybrid ? 10 : 16), preferences.getInt(key+"first", 0),
            preferences.getFloat(key+"size", mode == 1 ? 15 : 0), preferences.getFloat(key+"gap", 0),
            preferences.getBoolean(key+"bottom", true), hybrid, preferences.getFloat(key+"marker", 0),
            preferences.getBoolean(key+"legacy", true));
    }

    private void chooseBoardType() {
        LinearLayout body=sheet("选择标定板类型");
        addRow(body,"规则标记阵列",!hybridBoard,()->selectBoardType(false));
        addRow(body,"ChArUco 混合棋盘",hybridBoard,()->selectBoardType(true));
    }

    private void selectBoardType(boolean hybrid) {
        closeSheet(true);
        if (hybridBoard != hybrid) {
            releaseCamera();
            resetCalibration();
            hybridBoard=hybrid;
            preferences.edit().putBoolean("hybridBoard",hybrid).apply();
            board=loadBoard();
            if(hybrid && !MarkerVision.aruco(selectedFamily)) chooseFamily(MarkerVision.CHARUCO_DEFAULT);
            result=null;
            setStill(null);
            syncModeControls();
            updateResult();
        }
        showExample();
    }

    private void showExample() {
        String title, asset, hint;
        if(mode==1){
            title="棋盘格标定示例";
            asset="examples/chess.png";
            hint="图中是 8 行 × 11 列方格。板型参数填方格数及实测边长，取景时应看见完整棋盘。";
        }else if(mode==2 && board.hybrid){
            title="ChArUco 混合棋盘示例";
            asset="examples/hybrid.png";
            hint="标记和黑格交替。图示为 10 × 10 方格、ArUco 6X6_50、旧版排列。请填写实测方格和标记边长，确认起始 ID。";
        }else if(mode==2){
            title="规则标记阵列示例";
            asset="board-16x16.png";
            hint="每格一个标记，ID 按行递增。填写标签行列、实际黑边边长和间距，选择与板匹配的字典。";
        }else{
            title=FAMILIES[selectedFamily]+" 识别示例";
            asset=MarkerVision.aruco(selectedFamily)
                ? "examples/aruco-"+(selectedFamily-MarkerVision.ARUCO_FIRST)+".png"
                : "examples/"+FAMILIES[selectedFamily]+".png";
            hint="用匹配字典的标记对准相机，四周留白。识别模式可显示 ID 和方向，也能扫一整片阵列。";
        }
        LinearLayout body=sheet(title);
        android.widget.ImageView image=new android.widget.ImageView(this);
        image.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription(title);
        image.setBackground(shape(Color.WHITE,CORNER));
        image.setClipToOutline(true);
        try(InputStream in=getAssets().open(asset)){
            image.setImageBitmap(BitmapFactory.decodeStream(in));
        }catch(IOException ignored){}
        body.addView(image,new LinearLayout.LayoutParams(-1,dp(218)));
        TextView description=text(hint,14,MUTED);
        description.setPadding(0,dp(10),0,dp(8));
        body.addView(description);
        if(mode!=0) addRow(body,"填写标定板参数",false,this::editBoard);
        if(mode==2) addRow(body,"选择其他板型",false,this::chooseBoardType);
        addRow(body,"知道了",true,()->closeSheet(true));
    }

    private void resetCalibration() {
        revision++;
        calibration.clear();
        calibrationSource = null;
        calibrationJson = null;
        calibrationBusy = false;
        previewSample = null;
        previewCalibrationStatus = null;
        nextCalibrationCheck = 0;
        calibrationFeedbackUntil = 0;
    }

    private String startLabel() { return mode == 0 ? "开启摄像头" : "开始标定"; }
    private String stopLabel() { return mode == 0 ? "结束扫描" : "停止取景"; }
    private String idleStatus() {
        return mode == 0 ? "对准 AprilTag，或导入整块标签板" : "点「开始标定」检测角点，也可导入照片";
    }

    private void syncModeControls() {
        modeTitle.setText(mode == 0 ? "TagLens" : mode == 1 ? "棋盘格标定" : board.hybrid ? "混合棋盘标定" : "标记阵列标定");
        modeTitle.setTextSize(mode == 0 ? 23 : 20);
        family.setText(FAMILIES[selectedFamily] + "  ▾");
        family.setContentDescription("标签字典：" + FAMILIES[selectedFamily]);
        family.setVisibility(mode == 1 ? View.GONE : View.VISIBLE);
        boardButton.setVisibility(mode == 0 ? View.GONE : View.VISIBLE);
        if (mode != 0) {
            String label = board.rows + " × " + board.cols + (mode == 1 || board.hybrid ? " 方格" : " 标签");
            if (mode == 2 && !board.hybrid) label += " · ID " + board.first + " 起";
            boardButton.setText(label + (board.size > 0
                ? " · " + String.format(Locale.ROOT, "%.3f", board.size).replaceAll("0+$", "").replaceAll("[.]$", "") + " mm"
                : " · 待填尺寸") + "  ›");
        }
        liveButton.setText(cameraActive() ? stopLabel() : startPending ? "取消" : startLabel());
        captureButton.setText(mode == 0 ? "拍照精扫" : "采集样本");
        captureButton.setEnabled(cameraActive() && (mode == 0 || previewSample != null));
    }

    private void setMode(int next) {
        if (mode == next) return;
        releaseCamera();
        closeResults();
        resetCalibration();
        mode = next;
        preferences.edit().putInt("mode", mode).apply();
        board = loadBoard();
        if (board.hybrid && !MarkerVision.hybridSupported(selectedFamily)) chooseFamily(MarkerVision.CHARUCO_DEFAULT);
        result = null;
        lastPhoto = null;
        setStill(null);
        syncModeControls();
        status.setText(idleStatus());
        updateResult();
    }

    private void chooseMode() {
        LinearLayout body = sheet("工作模式");
        for (int i=0;i<Calibration.MODES.length;i++) {
            int next=i;
            addRow(body, Calibration.MODES[i], i==mode, ()->{closeSheet(true);setMode(next);showExample();});
        }
    }

    private void chooseCamera() {
        LinearLayout body = sheet("选择摄像头");
        addRow(body, "内置后置摄像头", cameraId.isEmpty(), ()->selectCamera(""));
        int count=0;
        try {
            android.hardware.camera2.CameraManager manager=ExternalCamera.manager(this);
            for(String id:manager.getCameraIdList()) if(ExternalCamera.external(manager,id)) {
                count++;
                addRow(body,"USB 摄像头 · "+id,id.equals(cameraId),()->selectCamera(id));
            }
        } catch (android.hardware.camera2.CameraAccessException | RuntimeException ignored) {}
        if(count==0) body.addView(text("系统暂未列出 USB 摄像头。接好扩展坞和摄像头后重新打开此页。",14,MUTED));
    }

    private void selectCamera(String id) {
        closeSheet(true);
        if(cameraId.equals(id))return;
        releaseCamera();
        resetCalibration();
        cameraId=id;
        preferences.edit().putString("cameraId",id).apply();
        result=null;setStill(null);updateResult();
        status.setText("摄像头已选择，点击开启");
    }

    private EditText numberField(LinearLayout body, String label, String value) {
        body.addView(text(label,13,MUTED));
        EditText field=new EditText(this);
        field.setTextColor(INK);field.setSingleLine(true);field.setText(value);
        field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        body.addView(field,new LinearLayout.LayoutParams(-1,dp(46)));
        return field;
    }

    private void editBoard() {
        LinearLayout body=sheet("标定板参数");
        boolean chess=mode==1, hybrid=board.hybrid, squares=chess||hybrid;
        if(mode==2)addRow(body,"板型 · "+(hybrid?"ChArUco 混合棋盘":"规则标记阵列")+"  ›",false,this::chooseBoardType);
        EditText rows=numberField(body,squares?"方格行数":"标签行数",""+board.rows);
        EditText cols=numberField(body,squares?"方格列数":"标签列数",""+board.cols);
        EditText size=numberField(body,squares?"方格边长（毫米）":"标签黑边边长（毫米）",""+board.size);
        EditText spacing=chess?null:numberField(body,hybrid?"标记黑边边长（毫米）":"相邻标签黑边间距（毫米）",
            ""+(hybrid?board.markerSize:board.gap));
        EditText first=chess?null:numberField(body,"起始 ID · 每行从左到右递增",""+board.first);
        Switch pattern=new Switch(this);
        pattern.setText(hybrid?"旧版排列（这张 10 × 10 示例使用旧版）":"从底行开始编号");
        pattern.setTextColor(INK);pattern.setChecked(hybrid?board.legacy:board.bottomFirst);
        if(!chess)body.addView(pattern);
        else body.addView(text("8 × 11 个方格对应 7 × 10 个内角点。",13,MUTED));
        TextView message=text("",13,MUTED);body.addView(message);
        addRow(body,"保存板型参数",true,()->{
            try{
                int r=Integer.parseInt(rows.getText().toString()),c=Integer.parseInt(cols.getText().toString());
                double mm=Double.parseDouble(size.getText().toString());
                double detail=chess?0:Double.parseDouble(spacing.getText().toString());
                int id=chess?0:Integer.parseInt(first.getText().toString());
                int min=squares?4:2;
                if(r<min||c<min||r>32||c>32||!(mm>0&&mm<=10000)||!(detail>=0&&detail<=10000)||id<0
                    || (hybrid && !(detail>0 && detail<mm)))throw new IllegalArgumentException();
                resetCalibration();
                board=new Calibration.Board(mode,r,c,id,mm,hybrid?0:detail,!hybrid&&pattern.isChecked(),
                    hybrid,hybrid?detail:0,hybrid&&pattern.isChecked());
                String key=boardKey();
                preferences.edit().putInt(key+"rows",r).putInt(key+"cols",c).putInt(key+"first",id)
                    .putFloat(key+"size",(float)mm).putFloat(key+"gap",(float)board.gap)
                    .putFloat(key+"marker",(float)board.markerSize).putBoolean(key+"bottom",board.bottomFirst)
                    .putBoolean(key+"legacy",board.legacy).apply();
                android.view.inputmethod.InputMethodManager ime=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
                if(ime!=null)ime.hideSoftInputFromWindow(size.getWindowToken(),0);
                closeSheet(true);syncModeControls();updateResult();
            }catch(IllegalArgumentException e){message.setText(hybrid?"请填写有效尺寸，标记边长必须小于方格边长":"请填写有效的行列、起始 ID 和实际毫米尺寸");}
        });
    }

    private Bitmap importCalibrationBitmap(Uri uri) throws IOException {
        BitmapFactory.Options options=new BitmapFactory.Options(); options.inJustDecodeBounds=true;
        try(InputStream in=openUri(uri)){BitmapFactory.decodeStream(in,null,options);}
        int orientation=1;
        try(InputStream in=openUri(uri)){
            orientation=new android.media.ExifInterface(in).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1);
        } catch(IOException ignored) {}
        options.inJustDecodeBounds=false;
        options.inSampleSize=1;
        while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>2600)options.inSampleSize*=2;
        Bitmap image;
        try(InputStream in=openUri(uri)){image=BitmapFactory.decodeStream(in,null,options);}
        if(image==null)throw new IOException("无法解码照片");
        if(orientation==1)return image;
        Bitmap rotated=Bitmap.createBitmap(image,0,0,image.getWidth(),image.getHeight(),
            PhotoScanner.normalizedOrientation(orientation,image.getWidth(),image.getHeight()),true);
        if(rotated!=image)image.recycle();
        return rotated;
    }

    private void acceptSample(Calibration.Sample sample, String source) {
        calibrationFeedbackUntil = android.os.SystemClock.elapsedRealtime() + 2000;
        if (calibrationSource != null && !calibrationSource.equals(source)) {
            status.setText("请先开始新一组标定，避免混用实时帧与照片");
            return;
        }
        try {
            calibration.add(sample);
            calibrationSource = source;
            calibrationJson = null;
            status.setText("已采集 " + calibration.count() + " 张 · 改变角度和位置后继续");
            updateResult();
        } catch (IllegalArgumentException error) {
            status.setText(error.getMessage());
        }
    }

    private void collectCalibration(Uri uri) {
        if(calibrationBusy)return;
        if(!(board.size>0)){editBoard();return;}
        final Calibration.Board settings=board;
        final String source = "导入照片";
        if(calibrationSource!=null&&!calibrationSource.equals(source)){
            status.setText("请先开始新一组标定，避免混用实时帧与照片");
            return;
        }
        int token=revision, familyIndex=selectedFamily, threads=photoThreads;
        calibrationBusy=true;
        status.setText("正在提取标定角点");
        worker.execute(()->{
            Bitmap image=null;
            try {
                image=importCalibrationBitmap(uri);
                if(image==null)throw new IOException("无法读取当前帧");
                Calibration.Sample sample;
                if(settings.mode==1) sample=Calibration.chess(image,settings);
                else if(settings.hybrid) {
                    sample=engine().markers.hybridBitmap(image,familyIndex,settings);
                    if(sample==null)throw new IllegalArgumentException("混合棋盘角点不足，请核对字典、板型和旧版排列");
                } else {
                    long start=System.nanoTime();
                    ScanResult detected=new ScanResult(image.getWidth(),image.getHeight(),FAMILIES[familyIndex],
                        ScanResult.decode(engine().scanPhoto(image,familyIndex,threads,false)),start,true);
                    try {
                        sample=Calibration.april(detected,settings);
                    } catch (IllegalArgumentException rejected) {
                        if (familyIndex != 0 || rejected instanceof Calibration.BoardOriginException) throw rejected;
                        ScanResult dense = new ScanResult(image.getWidth(), image.getHeight(), FAMILIES[familyIndex],
                            ScanResult.decode(engine().markers.denseAprilBitmap(image)), start, true);
                        try {
                            sample=Calibration.april(dense,settings);
                        } catch (IllegalArgumentException denseRejected) {
                            if (denseRejected instanceof Calibration.BoardOriginException
                                || dense.tags.size() > detected.tags.size()) throw denseRejected;
                            throw rejected;
                        }
                    }
                }
                Calibration.Sample captured = sample;
                runOnUiThread(()->{
                    calibrationBusy=false;
                    if(token!=revision||isDestroyed())return;
                    acceptSample(captured, source);
                });
            }catch(Exception|LinkageError error){
                runOnUiThread(()->{calibrationBusy=false;if(token==revision)status.setText("采集失败 · "+error.getMessage());});
            }finally{if(image!=null)image.recycle();}
        });
    }

    private void showCalibration(boolean autoSolve) {
        LinearLayout body=sheet("相机标定");
        TextView summary=text("已采集 "+calibration.count()+" 张。请让板覆盖中心、四角，并改变俯仰角。",14,INK);
        LinearLayout actions = new LinearLayout(this);
        Button copy = button("复制结果", false);
        Button share = button("分享结果", true);
        copy.setOnClickListener(view -> copyResult(calibrationJson));
        share.setOnClickListener(view -> shareResult(calibrationJson, "calibration", () -> showCalibration(false)));
        addControl(actions, copy, 1);
        addControl(actions, share, 1);
        body.addView(actions);
        Runnable refreshActions = () -> {
            boolean available = calibrationJson != null;
            copy.setEnabled(available);
            share.setEnabled(available);
            copy.setAlpha(available ? 1 : 0.5f);
            share.setAlpha(available ? 1 : 0.5f);
        };
        refreshActions.run();
        summary.setPadding(0, dp(12), 0, dp(8));
        body.addView(summary);
        Runnable solve = ()->{
            if(calibrationBusy)return;
            if(calibration.count()<8){summary.setText("至少需要 8 张不同姿态的有效样本");return;}
            int token=revision;
            Calibration.Board settings=board;
            String cameraName=calibrationSource;
            String dictionary=FAMILIES[selectedFamily];
            calibrationBusy=true;summary.setText("正在求解内参和畸变");
            worker.execute(()->{
                try{
                    String json=calibration.solve(cameraName,settings,dictionary);
                    runOnUiThread(()->{
                        calibrationBusy=false;
                        if(token!=revision)return;
                        calibrationJson=json;
                        refreshActions.run();
                        summary.setText(json);
                        updateResult();
                    });
                }catch(Exception|LinkageError error){
                    runOnUiThread(()->{calibrationBusy=false;if(token==revision)summary.setText("求解失败 · "+error.getMessage());});
                }
            });
        };
        addRow(body,"计算相机内参",true,solve);
        addRow(body,"开始新一组标定",false,()->{
            resetCalibration();updateResult();refreshActions.run();summary.setText("已开始新一组标定，请采集样本");
        });
        if(calibrationJson!=null)summary.setText(calibrationJson);
        else if(autoSolve)solve.run();
    }

    private int overlayColor() {
        return customOverlayColor != 0 ? customOverlayColor : themeIndex == 1 ? 0xff168523 : ACCENT;
    }

    private String colorLabel() {
        return customOverlayColor == 0 ? "跟随主题" : hexColor(customOverlayColor);
    }

    private static String hexColor(int color) {
        return String.format(Locale.ROOT, "#%06X", color & 0xffffff);
    }

    private void saveOverlayColor(int color) {
        customOverlayColor = color;
        preferences.edit().putInt("overlayColor", color).apply();
        overlay.invalidate();
    }

    private void showOverlayColors() {
        LinearLayout body = sheet("识别框颜色");
        TextView current = text("当前颜色 · " + colorLabel(), 14, INK);
        body.addView(current, new LinearLayout.LayoutParams(-1, dp(42)));
        View swatch = new View(this);
        swatch.setBackground(shape(overlayColor(), CORNER));
        body.addView(swatch, new LinearLayout.LayoutParams(-1, dp(22)));
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setContentDescription("自定义颜色的十六进制 RGB 值");
        input.setHint("输入颜色，例如 #FF5733");
        input.setText(hexColor(overlayColor()));
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);

        java.util.function.IntConsumer selectColor = color -> {
            saveOverlayColor(color);
            current.setText("当前颜色 · " + colorLabel());
            swatch.setBackground(shape(overlayColor(), CORNER));
            input.setText(hexColor(overlayColor()));
            input.setError(null);
        };

        int[] colors = {0xffbff38c, 0xff38c7ff, 0xffffbf42, 0xffff5468, 0xffe487ff, 0xffffffff};
        String[] names = {"亮绿", "亮蓝", "琥珀", "亮红", "亮紫", "白色"};
        for (int row = 0; row < 2; row++) {
            LinearLayout line = new LinearLayout(this);
            LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(-1, -2);
            lineParams.topMargin = dp(8);
            body.addView(line, lineParams);
            for (int column = 0; column < 3; column++) {
                int i = row * 3 + column;
                Button choice = button(names[i], false);
                choice.setBackground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x33000000), shape(colors[i], CORNER), null));
                choice.setTextColor(Color.BLACK);
                choice.setOnClickListener(view -> selectColor.accept(colors[i]));
                addControl(line, choice, 1);
            }
        }
        addRow(body, "跟随主题", false, () -> selectColor.accept(0));
        body.addView(input, new LinearLayout.LayoutParams(-1, dp(50)));
        Runnable applyCustom = () -> {
            String value = input.getText().toString().trim();
            if (!value.matches("#?[0-9a-fA-F]{6}")) {
                input.setError("请输入 6 位 RGB 色值，例如 #FF5733");
                return;
            }
            selectColor.accept(Color.parseColor(value.startsWith("#") ? value : "#" + value));
            android.view.inputmethod.InputMethodManager ime =
                (android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            if (ime != null) ime.hideSoftInputFromWindow(input.getWindowToken(), 0);
            input.clearFocus();
        };
        input.setOnEditorActionListener((view, id, event) -> {
            if (id != android.view.inputmethod.EditorInfo.IME_ACTION_DONE) return false;
            applyCustom.run();
            return true;
        });
        addRow(body, "应用自定义颜色", true, applyCustom);
        addRow(body, "返回设置", false, this::showSettings);
    }

    private void changeTheme(int index) {
        if (themeIndex == index) return;
        boolean reopen = cameraActive() || startPending;
        boolean showingResults = resultsPane != null;
        String priorStatus = status.getText().toString();
        releaseCamera();
        themeIndex = index;
        preferences.edit().putInt("theme", index).apply();
        applyPalette();
        buildUi();
        if (reopen) startCamera();
        else {
            status.setText(priorStatus);
            if (showingResults) showResults();
        }
        shell.post(this::showSettings);
    }

    @Override public void onBackPressed() {
        if (sheetLayer != null) closeSheet(true);
        else if (resultsPane != null) closeResults();
        else super.onBackPressed();
    }

    private void recognitionAction(ScanResult saved, boolean share) {
        try {
            String json = saved.json();
            if (share) shareResult(json, "apriltag", null);
            else copyResult(json);
        } catch (org.json.JSONException error) {
            toast("无法读取识别结果");
        }
    }

    private void copyResult(String json) {
        if (json == null) return;
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("TagLens 结果", json));
        if (android.os.Build.VERSION.SDK_INT < 33) toast("结果已复制");
    }

    private void shareResult(String json, String prefix, Runnable back) {
        if (json == null) return;
        LinearLayout body = sheet("分享结果");
        addRow(body, "发送到微信", false, () -> sendResult(json, prefix, "com.tencent.mm", false));
        addRow(body, "发送到 QQ", false, () -> sendResult(json, prefix, "com.tencent.mobileqq", false));
        addRow(body, "通过其他应用发送附件", false, () -> sendResult(json, prefix, null, false));
        addRow(body, "分享纯文本", false, () -> sendResult(json, prefix, null, true));
        if (back != null) addRow(body, "返回标定结果", false, back);
    }

    private void sendResult(String json, String prefix, String target, boolean plainText) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        try {
            if (plainText) {
                intent.setType("text/plain").putExtra(Intent.EXTRA_TEXT, json);
            } else {
                Uri attachment = ResultShare.prepare(this, json, prefix);
                intent.setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, attachment);
                intent.setClipData(ClipData.newUri(getContentResolver(), "TagLens 结果", attachment));
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            if (target == null) startActivity(Intent.createChooser(intent, "分享 TagLens 结果"));
            else startActivity(intent.setPackage(target));
        } catch (ActivityNotFoundException | SecurityException error) {
            toast("目标应用不可用或不接收附件，可尝试分享纯文本");
        } catch (IOException error) {
            toast("无法生成分享附件");
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        if (startPending && surfaceReady) openCamera();
    }

    @Override protected void onPause() {
        foreground = false;
        releaseCamera();
        super.onPause();
    }

    @Override protected void onDestroy() {
        revision++;
        worker.execute(() -> { if (engine != null) engine.close(); });
        worker.shutdown();
        setStill(null);
        super.onDestroy();
    }

    private static final class PreviewFrame {
        final byte[] pixels;
        final int width, height, orientation;

        PreviewFrame(byte[] pixels, int width, int height, int orientation) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
            this.orientation = orientation;
        }

        Bitmap toBitmap() {
            // Encode once when scanning ends, never during live detection.
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            YuvImage image = new YuvImage(pixels, ImageFormat.NV21, width, height, null);
            if (!image.compressToJpeg(new Rect(0, 0, width, height), 95, encoded)) return null;
            byte[] bytes = encoded.toByteArray();
            Bitmap raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (raw == null || orientation == 1) return raw;
            Matrix transform = PhotoScanner.normalizedOrientation(orientation, width, height);
            Bitmap rotated = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), transform, true);
            if (rotated != raw) raw.recycle();
            return rotated;
        }
    }

    private final class ScanOverlay extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Path path = new Path();
        private final RectF target = new RectF();

        ScanOverlay() { super(MainActivity.this); }

        @Override protected void onDraw(Canvas canvas) {
            ScanResult current = result;
            if (current == null) {
                if (!cameraActive()) drawGuide(canvas);
                return;
            }
            if (still == null && !cameraActive()) {
                drawGuide(canvas);
                return;
            }
            float scale = Math.min(getWidth() / (float)current.width, getHeight() / (float)current.height);
            float dx = (getWidth() - current.width * scale) / 2;
            float dy = (getHeight() - current.height * scale) / 2;
            if (still != null) {
                target.set(dx, dy, dx + current.width * scale, dy + current.height * scale);
                paint.setAlpha(255);
                canvas.drawBitmap(still, null, target, paint);
            }
            if (!showOverlay) return;
            paint.setColor(overlayColor());
            if ((mode == 1 || board.hybrid) && previewSample != null) {
                paint.setStyle(Paint.Style.FILL);
                int color = overlayColor();
                int outline = Color.luminance(color) > .35f ? Color.BLACK : Color.WHITE;
                for (org.opencv.core.Point point : previewSample.image) {
                    float x = dx + (float)point.x * scale, y = dy + (float)point.y * scale;
                    paint.setColor(outline);
                    canvas.drawCircle(x, y, dp(4), paint);
                    paint.setColor(color);
                    canvas.drawCircle(x, y, dp(2.5f), paint);
                }
                return;
            }
            paint.setStrokeWidth(dp(2));
            paint.setTextSize(dp(current.tags.size() > 24 ? 10 : 13));
            paint.setTypeface(Typeface.MONOSPACE);
            for (ScanResult.Tag tag : current.tags) {
                paint.setStyle(Paint.Style.STROKE);
                path.rewind();
                for (int i = 0; i < 8; i += 2) {
                    float x = dx + tag.corners[i] * scale, y = dy + tag.corners[i + 1] * scale;
                    if (i == 0) path.moveTo(x, y);
                    else path.lineTo(x, y);
                }
                path.close();
                canvas.drawPath(path, paint);
                float topX = dx + (tag.corners[4] + tag.corners[6]) * .5f * scale;
                float topY = dy + (tag.corners[5] + tag.corners[7]) * .5f * scale;
                canvas.drawLine(dx + tag.x * scale, dy + tag.y * scale, topX, topY, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawText("#" + tag.id, topX + dp(2), Math.max(dp(13), topY - dp(3)), paint);
            }
        }

        private void drawGuide(Canvas canvas) {
            paint.setColor(ACCENT);
            paint.setStrokeWidth(dp(2));
            float x = getWidth() / 2f, y = getHeight() / 2f;
            float r = dp(52), mark = dp(20);
            for (int sx : new int[]{-1, 1}) {
                for (int sy : new int[]{-1, 1}) {
                    float edgeX = x + sx * r, edgeY = y + sy * r;
                    canvas.drawLine(edgeX, edgeY, edgeX - sx * mark, edgeY, paint);
                    canvas.drawLine(edgeX, edgeY, edgeX, edgeY - sy * mark, paint);
                }
            }
        }
    }
}
