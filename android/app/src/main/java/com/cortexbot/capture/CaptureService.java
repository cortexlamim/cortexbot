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
        int w=bitmap.getWidth(), h=bitmap.getHeight();
        float[] hsv=new float[3];
        StringBuilder out=new StringBuilder("FULL SCREEN COLOR MAP");
        for(int band=0;band<12;band++){
            int y1=band*h/12, y2=(band+1)*h/12;
            long red=0,green=0;
            for(int y=y1;y<y2;y+=4){
                for(int x=w/20;x<w*19/20;x+=4){
                    Color.colorToHSV(bitmap.getPixel(x,y),hsv);
                    if(hsv[1]<0.35f||hsv[2]<0.20f)continue;
                    if(hsv[0]>=70&&hsv[0]<=170)green++;
                    else if(hsv[0]<=15||hsv[0]>=345)red++;
                }
            }
            out.append("\nBand ").append(band+1)
               .append(" y=").append(y1).append("-").append(y2)
               .append(" RED=").append(red).append(" GREEN=").append(green);
        }
        out.append("\nDiagnostic only; not candle data.");
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
