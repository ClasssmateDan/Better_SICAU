package com.dan.qrbatch;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Camera-only QR reader. Decoded text is returned to the caller, never opened. */
@SuppressWarnings("deprecation")
public final class CameraScanActivity extends Activity implements SurfaceHolder.Callback {
    private static final int CAMERA_PERMISSION_REQUEST = 2101;
    private static final long FRAME_INTERVAL_MS = 160;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService decoderExecutor = Executors.newSingleThreadExecutor(runnable ->
            new Thread(runnable, "QrCameraDecode"));
    private final AtomicBoolean decoding = new AtomicBoolean(false);
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private final QRCodeReader qrReader = new QRCodeReader();
    private final Map<DecodeHintType, Object> decodeHints = new EnumMap<>(DecodeHintType.class);

    private PreviewSurface preview;
    private TextView status;
    private Button retryButton;
    private Camera camera;
    private boolean surfaceReady;
    private volatile boolean resumed;
    private volatile boolean destroyed;
    private int cameraGeneration;
    private long lastDecodeAt;
    private boolean needsAutoFocus;

    private final Runnable autofocus = new Runnable() {
        @Override public void run() {
            if (camera == null || !resumed || completed.get() || !needsAutoFocus) return;
            try {
                camera.autoFocus(null);
            } catch (RuntimeException ignored) {
                // Some legacy drivers reject autofocus while a previous focus is in progress.
            }
            mainHandler.postDelayed(this, 1800);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(SigninTheme.BACKGROUND);
        getWindow().setNavigationBarColor(SigninTheme.BACKGROUND);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        decodeHints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        buildUi();
        if (!hasCameraPermission()) {
            status.setText("请允许相机权限以扫描二维码");
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(SigninTheme.BACKGROUND); SigninTheme.insets(root);

        TextView title = text("扫描签到二维码", 21, SigninTheme.INK);
        title.setPadding(dp(20), dp(22), dp(20), dp(18));
        root.addView(title);

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        preview = new PreviewSurface();
        frame.addView(preview, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
        preview.getHolder().addCallback(this);

        status = text("请将二维码完整放入画面", 15, SigninTheme.INK);
        status.setPadding(dp(20), dp(20), dp(20), dp(8));
        root.addView(status);
        TextView subtitle = text("识别后返回账号队列", 14, SigninTheme.MUTED);
        subtitle.setPadding(dp(20), 0, dp(20), dp(12));
        root.addView(subtitle);

        retryButton = new Button(this);
        SigninTheme.button(retryButton, true);
        retryButton.setText("重试相机");
        retryButton.setVisibility(View.GONE);
        retryButton.setOnClickListener(view -> startCamera());
        root.addView(retryButton, buttonParams());

        Button cancel = new Button(this);
        SigninTheme.button(cancel, false);
        cancel.setText("取消");
        cancel.setOnClickListener(view -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        root.addView(cancel, buttonParams());
        setContentView(root);
        root.requestApplyInsets();
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.setMargins(dp(20), 0, dp(20), dp(12));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                    int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CAMERA_PERMISSION_REQUEST || destroyed || completed.get()) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            status.setText("请将二维码完整放入画面");
            startCamera();
        } else {
            retryButton.setVisibility(View.GONE);
            status.setText("未获得相机权限。可返回后在系统设置中允许相机访问。");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        startCamera();
    }

    @Override protected void onPause() {
        resumed = false;
        stopCamera();
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        stopCamera();
        decoderExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public void finish() {
        completed.set(true);
        super.finish();
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        startCamera();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        startCamera();
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        stopCamera();
    }

    private void startCamera() {
        if (camera != null || !resumed || !surfaceReady || destroyed || completed.get()
                || !hasCameraPermission() || !preview.getHolder().getSurface().isValid()) return;
        Camera opened = null;
        try {
            int cameraId = findCamera();
            if (cameraId < 0) {
                showCameraError("未找到可用相机，请使用有摄像头的设备。");
                return;
            }
            opened = Camera.open(cameraId);
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(cameraId, info);
            final int rotation = displayOrientation(info);
            Camera.Parameters parameters = opened.getParameters();
            Camera.Size size = choosePreviewSize(parameters.getSupportedPreviewSizes(), rotation);
            if (size != null) parameters.setPreviewSize(size.width, size.height);
            parameters.setPreviewFormat(ImageFormat.NV21);
            List<String> modes = parameters.getSupportedFocusModes();
            if (modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            } else if (modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }
            opened.setParameters(parameters);
            Camera.Size actualSize = opened.getParameters().getPreviewSize();
            final int width = actualSize.width;
            final int height = actualSize.height;
            final int generation = ++cameraGeneration;
            needsAutoFocus = Camera.Parameters.FOCUS_MODE_AUTO.equals(parameters.getFocusMode());
            opened.setDisplayOrientation(rotation);
            preview.setAspect(rotation % 180 == 0 ? width : height,
                    rotation % 180 == 0 ? height : width);
            opened.setPreviewDisplay(preview.getHolder());
            camera = opened;
            opened.setErrorCallback((error, source) -> mainHandler.post(() -> {
                if (camera != source || destroyed || completed.get()) return;
                stopCamera();
                showCameraError("相机暂时不可用，请关闭其他相机应用后重试。");
            }));
            opened.setPreviewCallbackWithBuffer((data, source) ->
                    onPreviewFrame(data, source, width, height, rotation, generation));
            int bufferBytes = (width * height * ImageFormat.getBitsPerPixel(ImageFormat.NV21) + 7) / 8;
            opened.addCallbackBuffer(new byte[bufferBytes]);
            opened.addCallbackBuffer(new byte[bufferBytes]);
            lastDecodeAt = 0;
            opened.startPreview();
            retryButton.setVisibility(View.GONE);
            status.setText("请将二维码完整放入画面");
            if (needsAutoFocus) mainHandler.postDelayed(autofocus, 350);
        } catch (SecurityException error) {
            closeFailedCamera(opened);
            showCameraError("无法访问相机，请在系统设置中确认相机权限。");
        } catch (IOException | RuntimeException error) {
            closeFailedCamera(opened);
            showCameraError("无法打开相机，请关闭其他相机应用后重试。");
        }
    }

    private void closeFailedCamera(Camera opened) {
        if (camera == opened) {
            stopCamera();
        } else if (opened != null) {
            try { opened.release(); } catch (RuntimeException ignored) { }
        }
    }

    private void showCameraError(String message) {
        if (destroyed || completed.get()) return;
        status.setText(message);
        retryButton.setVisibility(hasCameraPermission() ? View.VISIBLE : View.GONE);
    }

    private int findCamera() {
        int count = Camera.getNumberOfCameras();
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int id = 0; id < count; id++) {
            Camera.getCameraInfo(id, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return id;
        }
        return count > 0 ? 0 : -1;
    }

    private int displayOrientation(Camera.CameraInfo info) {
        int surfaceRotation = getWindowManager().getDefaultDisplay().getRotation();
        int degrees = surfaceRotation == Surface.ROTATION_90 ? 90
                : surfaceRotation == Surface.ROTATION_180 ? 180
                : surfaceRotation == Surface.ROTATION_270 ? 270 : 0;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return (360 - (info.orientation + degrees) % 360) % 360;
        }
        return (info.orientation - degrees + 360) % 360;
    }

    private Camera.Size choosePreviewSize(List<Camera.Size> sizes, int rotation) {
        if (sizes == null || sizes.isEmpty()) return null;
        double wantedRatio = 4.0 / 3.0;
        if (preview.getWidth() > 0 && preview.getHeight() > 0) {
            wantedRatio = rotation % 180 == 0
                    ? (double) preview.getWidth() / preview.getHeight()
                    : (double) preview.getHeight() / preview.getWidth();
        }
        Camera.Size best = null;
        Camera.Size smallest = sizes.get(0);
        double bestScore = Double.MAX_VALUE;
        for (Camera.Size size : sizes) {
            long pixels = (long) size.width * size.height;
            if (pixels < (long) smallest.width * smallest.height) smallest = size;
            if (size.width > 1280 || size.height > 1280 || pixels > 1280L * 960) continue;
            double score = Math.abs((double) size.width / size.height - wantedRatio) * 10
                    + Math.abs(pixels - 1280.0 * 720) / (1280.0 * 720);
            if (score < bestScore) {
                best = size;
                bestScore = score;
            }
        }
        return best != null ? best : smallest;
    }

    private void onPreviewFrame(byte[] data, Camera source, int width, int height,
                                int rotation, int generation) {
        if (data == null) return;
        long now = SystemClock.elapsedRealtime();
        if (!resumed || destroyed || completed.get() || source != camera
                || now - lastDecodeAt < FRAME_INTERVAL_MS || !decoding.compareAndSet(false, true)) {
            returnBuffer(data, source, generation);
            return;
        }
        lastDecodeAt = now;
        try {
            decoderExecutor.execute(() -> {
                try {
                    if (!resumed || destroyed || completed.get() || Thread.currentThread().isInterrupted()) return;
                    String text = decodeFrame(data, width, height, rotation);
                    if (text != null && !text.isEmpty()) {
                        mainHandler.post(() -> {
                            if (!resumed || destroyed || source != camera || generation != cameraGeneration
                                    || isFinishing() || !completed.compareAndSet(false, true)) return;
                            setResult(RESULT_OK, new Intent().putExtra("qr_text", text));
                            finish();
                        });
                    }
                } catch (RuntimeException ignored) {
                    // A malformed or partially delivered frame must not terminate the camera screen.
                } finally {
                    qrReader.reset();
                    decoding.set(false);
                    mainHandler.post(() -> returnBuffer(data, source, generation));
                }
            });
        } catch (RejectedExecutionException ignored) {
            decoding.set(false);
            returnBuffer(data, source, generation);
        }
    }

    private String decodeFrame(byte[] frame, int width, int height, int rotation) {
        if (width <= 0 || height <= 0 || frame.length < width * height) return null;
        double scale = Math.min(1.0, 960.0 / Math.max(width, height));
        int sampledWidth = Math.max(1, (int) Math.round(width * scale));
        int sampledHeight = Math.max(1, (int) Math.round(height * scale));
        byte[] sampled = new byte[sampledWidth * sampledHeight];
        for (int y = 0; y < sampledHeight; y++) {
            int from = (y * height / sampledHeight) * width;
            int to = y * sampledWidth;
            for (int x = 0; x < sampledWidth; x++) {
                sampled[to + x] = frame[from + x * width / sampledWidth];
            }
        }
        byte[] luminance = sampled;
        int outputWidth = sampledWidth;
        int outputHeight = sampledHeight;
        if (rotation != 0) {
            luminance = new byte[sampled.length];
            if (rotation == 90 || rotation == 270) {
                outputWidth = sampledHeight;
                outputHeight = sampledWidth;
            }
            for (int y = 0; y < sampledHeight; y++) {
                for (int x = 0; x < sampledWidth; x++) {
                    int destination;
                    if (rotation == 90) destination = x * sampledHeight + sampledHeight - 1 - y;
                    else if (rotation == 270) destination = (sampledWidth - 1 - x) * sampledHeight + y;
                    else destination = (sampledHeight - 1 - y) * sampledWidth + sampledWidth - 1 - x;
                    luminance[destination] = sampled[y * sampledWidth + x];
                }
            }
        }
        PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(luminance,
                outputWidth, outputHeight, 0, 0, outputWidth, outputHeight, false);
        try {
            Result result = qrReader.decode(new BinaryBitmap(new HybridBinarizer(source)), decodeHints);
            return result.getText();
        } catch (ReaderException ignored) {
            return null;
        }
    }

    private void returnBuffer(byte[] data, Camera source, int generation) {
        if (resumed && !destroyed && !completed.get() && camera == source && generation == cameraGeneration) {
            try { source.addCallbackBuffer(data); } catch (RuntimeException ignored) { }
        }
    }

    private void stopCamera() {
        mainHandler.removeCallbacks(autofocus);
        Camera old = camera;
        camera = null;
        cameraGeneration++;
        if (old == null) return;
        try { old.setErrorCallback(null); } catch (RuntimeException ignored) { }
        try { old.setPreviewCallbackWithBuffer(null); } catch (RuntimeException ignored) { }
        try { old.stopPreview(); } catch (RuntimeException ignored) { }
        try { old.release(); } catch (RuntimeException ignored) { }
    }

    /** Fits the sensor aspect ratio inside its parent instead of stretching the preview. */
    private final class PreviewSurface extends SurfaceView {
        private int aspectWidth = 3;
        private int aspectHeight = 4;

        PreviewSurface() { super(CameraScanActivity.this); }

        void setAspect(int width, int height) {
            aspectWidth = Math.max(1, width);
            aspectHeight = Math.max(1, height);
            requestLayout();
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int height = MeasureSpec.getSize(heightSpec);
            if ((long) width * aspectHeight > (long) height * aspectWidth) {
                width = (int) ((long) height * aspectWidth / aspectHeight);
            } else {
                height = (int) ((long) width * aspectHeight / aspectWidth);
            }
            setMeasuredDimension(width, height);
        }
    }
}
