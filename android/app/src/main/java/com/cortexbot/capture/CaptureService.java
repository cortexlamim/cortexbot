package com.cortexbot.capture;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.view.WindowManager;
import android.util.DisplayMetrics;

public class CaptureService extends Service {
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;

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
            width, height, PixelFormat.RGBA_8888, 2);

        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                cleanup();
                stopSelf();
            }
        }, null);

        display = projection.createVirtualDisplay(
            "CORTEX-Capture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.getSurface(), null, null);

        reader.setOnImageAvailableListener(r -> {
            Image image = r.acquireLatestImage();
            if (image != null) {
                image.close();
            }
        }, null);

        return START_NOT_STICKY;
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
            .setContentText("Screen capture is active")
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
