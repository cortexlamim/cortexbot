package com.cortexbot.capture;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.widget.Toast;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int CAPTURE_REQUEST = 1001;
    private TextView status;
    private TextView output;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 48, 32, 32);

        status = new TextView(this);
        status.setText("CORTEX Screen Capture");
        status.setTextSize(18);
        layout.addView(status);

        Button start = new Button(this);
        start.setText("Start Screen Capture");
        layout.addView(start);

        output = new TextView(this);
        output.setTextSize(15);
        output.setText("OCR text will appear here.");
        layout.addView(output);

        Button read = new Button(this);
        read.setText("Read Chart Text");
        layout.addView(read);

        read.setOnClickListener(v -> showLatestText());

        Button copy = new Button(this);
        copy.setText("Copy OCR Text");
        layout.addView(copy);
        copy.setOnClickListener(v -> {
            String text = getSharedPreferences("cortex", MODE_PRIVATE).getString("last_ocr_text", "");
            if (text == null || text.trim().isEmpty()) {
                Toast.makeText(this, "No OCR text available", Toast.LENGTH_SHORT).show();
            } else {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("CORTEX OCR", text));
                Toast.makeText(this, "OCR text copied", Toast.LENGTH_SHORT).show();
            }
        });

        start.setOnClickListener(v -> {
            MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE);
            startActivityForResult(
                manager.createScreenCaptureIntent(), CAPTURE_REQUEST);
        });

        setContentView(layout);
    }

    private void showLatestText() {
        String text = getSharedPreferences("cortex", MODE_PRIVATE)
            .getString("last_ocr_text", "");
        if (text == null || text.trim().isEmpty()) {
            output.setText("No OCR text yet. Keep Quotex visible and try again.");
        } else {
            String scan = getSharedPreferences("cortex", MODE_PRIVATE)
                .getString("last_candle_scan", "Waiting for chart scan...");
            output.setText("Latest screen text:\n\n" + text
                + "\n\nCANDLE PIXEL SCAN:\n" + scan);
        }
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
