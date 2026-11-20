package com.cortexbot.capture;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int CAPTURE_REQUEST = 1001;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 48, 32, 32);

        status = new TextView(this);
        status.setText("CORTEX Screen Capture is ready");
        status.setTextSize(18);
        layout.addView(status);

        Button start = new Button(this);
        start.setText("Start Screen Capture");
        layout.addView(start);

        start.setOnClickListener(v -> {
            MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE);
            startActivityForResult(
                manager.createScreenCaptureIntent(), CAPTURE_REQUEST);
        });

        setContentView(layout);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == CAPTURE_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                Intent service = new Intent(this, CaptureService.class);
                service.putExtra("resultCode", resultCode);
                service.putExtra("data", data);
                startForegroundService(service);
                status.setText("Screen capture permission granted");
            } else {
                status.setText("Screen capture permission denied");
            }
        }
    }
}
