package com.cortexbot.capture;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.graphics.PixelFormat;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.view.WindowManager;
import android.util.DisplayMetrics;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;

public class CaptureService extends Service {
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private TextRecognizer recognizer;
    private long lastScan = 0;
    private boolean processing = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotification();
        startForeground(1, notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra("data");

        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        recognizer = TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS);

        MediaProjectionManager manager =
            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(resultCode, data);

        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(metrics);

        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;

        reader = ImageReader.newInstance(
            width, height, PixelFormat.RGBA_8888, 3);

        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                cleanup();
                stopSelf();
            }
        }, null);

        display = projection.createVirtualDisplay(
            "CORTEX-Capture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.getSurface(), null, null);

        reader.setOnImageAvailableListener(r -> scanScreen(r), null);
        return START_NOT_STICKY;
    }

    private void scanScreen(ImageReader source) {
        Image image = null;
        try {
            image = source.acquireLatestImage();
            if (image == null) return;

            long now = SystemClock.elapsedRealtime();
            if (processing || now - lastScan < 1500) {
                image.close();
                return;
            }

            lastScan = now;
            processing = true;

            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int paddedWidth = rowStride / pixelStride;

            Bitmap padded = Bitmap.createBitmap(
                paddedWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buffer);

            Bitmap bitmap = Bitmap.createBitmap(
                padded, 0, 0, image.getWidth(), image.getHeight());
            padded.recycle();

            image.close();
            image = null;

            String candleScan = scanCandlePixels(bitmap);
            getSharedPreferences("cortex", MODE_PRIVATE).edit()
                .putString("last_candle_scan", candleScan).apply();

            InputImage input = InputImage.fromBitmap(bitmap, 0);

            recognizer.process(input)
                .addOnSuccessListener(result -> {
                    String text = result.getText();
                    getSharedPreferences("cortex", MODE_PRIVATE)
                        .edit().putString("last_ocr_text", text)
                        .putLong("last_ocr_time", System.currentTimeMillis())
                        .apply();
                    Log.d("CORTEX_OCR", text);
                })
                .addOnFailureListener(e ->
                    Log.e("CORTEX_OCR", "OCR failed", e))
                .addOnCompleteListener(task -> {
                    bitmap.recycle();
                    processing = false;
                });

        } catch (Exception e) {
            Log.e("CORTEX_CAPTURE", "Frame error", e);
            processing = false;
            if (image != null) image.close();
        }
    }


    private String scanCandlePixels(Bitmap bitmap) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int left = w * 5 / 100, right = w * 88 / 100;
        int top = h * 38 / 100, bottom = h * 82 / 100;
        int step = 2;
        int n = (right - left + 1) / step;
        int[] red = new int[n], green = new int[n];
        int[] minY = new int[n], maxY = new int[n];
        java.util.Arrays.fill(minY, h);
        java.util.Arrays.fill(maxY, -1);
        float[] hsv = new float[3];

        for (int x = left; x < right; x += step) {
            int c = (x - left) / step;
            for (int y = top; y < bottom; y += step) {
                Color.colorToHSV(bitmap.getPixel(x, y), hsv);
                if (hsv[1] < 0.35f || hsv[2] < 0.20f) continue;
                boolean g = hsv[0] >= 70 && hsv[0] <= 170;
                boolean r = hsv[0] <= 15 || hsv[0] >= 345;
                if (!g && !r) continue;
                if (g) green[c]++; else red[c]++;
                minY[c] = Math.min(minY[c], y);
                maxY[c] = Math.max(maxY[c], y);
            }
        }

        StringBuilder out = new StringBuilder("Chart region scan");
        int found = 0, i = 0;
        while (i < n && found < 60) {
            if (red[i] + green[i] < 2) { i++; continue; }
            int first = i, last = i, reds = 0, greens = 0;
            int y1 = h, y2 = -1, gap = 0;
            while (i < n) {
                if (red[i] + green[i] < 2) {
                    if (++gap > 1) break;
                } else {
                    gap = 0;
                    last = i;
                    reds += red[i]; greens += green[i];
                    y1 = Math.min(y1, minY[i]);
                    y2 = Math.max(y2, maxY[i]);
                }
                i++;
            }
            int width = (last - first + 1) * step;
            int height = y2 - y1;
            if (width <= 36 && height >= 8 && reds + greens >= 5) {
                out.append("\nCandidate ").append(++found)
                   .append(": ").append(greens > reds ? "GREEN" : "RED")
                   .append(" x=").append(left + first * step)
                   .append("-").append(left + last * step)
                   .append(" y=").append(y1).append("-").append(y2)
                   .append(" height=").append(height);
            }
            if (i <= first) i = first + 1;
        }
        if (found == 0) out.append("\nNo candidates detected");
        out.append("\nTotal candidates: ").append(found)
           .append("\nApproximate scan only; not verified OHLC candles.");
        return out.toString();
    }

    private void createNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                "cortex_capture", "CORTEX Screen Capture",
                NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class)
                .createNotificationChannel(channel);
        }
    }

    private Notification notification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, "cortex_capture")
            : new Notification.Builder(this);

        return builder.setContentTitle("CORTEX Capture")
            .setContentText("Reading visible screen text")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build();
    }

    private void cleanup() {
        if (display != null) {
            display.release();
            display = null;
        }
        if (reader != null) {
            reader.close();
            reader = null;
        }
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
    }

    @Override
    public void onDestroy() {
        cleanup();
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
