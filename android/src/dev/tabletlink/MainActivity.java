package dev.tabletlink;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.AudioManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity implements Link.Events {
    private static final int INK = Color.rgb(30, 37, 43);
    private static final int MUTED = Color.rgb(96, 106, 112);
    private static final int SURFACE = Color.rgb(246, 247, 245);
    private static final int ACCENT = Color.rgb(35, 91, 77);
    private static final int ERROR = Color.rgb(156, 55, 48);
    private Link link;
    private EditText address;
    private TextView status;
    private Button connect;
    private FrameLayout root;
    private DisplayView display;
    private boolean connected;
    private boolean visible;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        link = new Link(this);
        setupScreen();
    }

    @Override public void onStart() {
        super.onStart();
        visible = true;
        String saved = getPreferences(MODE_PRIVATE).getString("desktop_ip", "");
        if (!saved.isEmpty()) {
            address.setText(saved);
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            else link.start(saved);
        }
    }

    @Override public void onStop() {
        visible = false;
        link.stop();
        if (connected) setupScreen();
        super.onStop();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 1) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED && visible)
                link.start(address.getText().toString().trim());
            else status.setText("Microphone permission is required to connect.");
        }
    }

    private int dp(float value) { return Math.round(getResources().getDisplayMetrics().density * value); }

    private TextView label(String value, int sp, int color) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sp);
        text.setTextColor(color);
        return text;
    }

    private void setupScreen() {
        connected = false;
        getWindow().getDecorView().setSystemUiVisibility(0);
        root = new FrameLayout(this);
        root.setBackgroundColor(SURFACE);
        setContentView(root);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(36), dp(30), dp(36), dp(30));
        FrameLayout.LayoutParams columnParams = new FrameLayout.LayoutParams(dp(470), -2, Gravity.CENTER);
        root.addView(column, columnParams);

        TextView eyebrow = label("TABLET LINK", 12, ACCENT);
        eyebrow.setLetterSpacing(0.14f);
        column.addView(eyebrow);

        TextView title = label("Your desktop, here.", 34, INK);
        title.setTypeface(null, 1);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(14);
        column.addView(title, titleParams);

        TextView description = label("Display, microphone and speaker over your Tailscale network.", 16, MUTED);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, -2);
        descriptionParams.topMargin = dp(10);
        column.addView(description, descriptionParams);

        TextView inputLabel = label("DESKTOP TAILSCALE IP", 12, MUTED);
        inputLabel.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams inputLabelParams = new LinearLayout.LayoutParams(-1, -2);
        inputLabelParams.topMargin = dp(38);
        column.addView(inputLabel, inputLabelParams);

        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(18);
        address.setTextColor(INK);
        address.setHint("100.x.y.z");
        address.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        address.setPadding(dp(14), dp(10), dp(14), dp(10));
        address.setBackgroundColor(Color.rgb(232, 235, 232));
        address.setText(getPreferences(MODE_PRIVATE).getString("desktop_ip", ""));
        LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(-1, dp(54));
        addressParams.topMargin = dp(8);
        column.addView(address, addressParams);

        connect = new Button(this);
        connect.setText("Save and connect");
        connect.setTextColor(Color.rgb(248, 251, 248));
        connect.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, dp(54));
        buttonParams.topMargin = dp(20);
        column.addView(connect, buttonParams);
        connect.setOnClickListener(view -> {
            String ip = address.getText().toString().trim();
            if (!ip.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) {
                status.setText("Enter the desktop's Tailscale IPv4 address.");
                status.setTextColor(ERROR);
                return;
            }
            getPreferences(MODE_PRIVATE).edit().putString("desktop_ip", ip).apply();
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            else link.start(ip);
        });

        status = label("Enter the desktop IP once. Next time, opening the app connects automatically.", 14, MUTED);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(20);
        column.addView(status, statusParams);
    }

    private void displayScreen() {
        connected = true;
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(19, 23, 25));
        display = new DisplayView();
        root.addView(display, new FrameLayout.LayoutParams(-1, -1));
        Button disconnect = new Button(this);
        disconnect.setText("Disconnect");
        disconnect.setTextColor(INK);
        disconnect.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(242, 244, 241)));
        FrameLayout.LayoutParams button = new FrameLayout.LayoutParams(dp(130), dp(46), Gravity.TOP | Gravity.RIGHT);
        button.setMargins(0, dp(12), dp(12), 0);
        root.addView(disconnect, button);
        disconnect.setOnClickListener(view -> { link.stop(); setupScreen(); status.setText("Disconnected"); });
        setContentView(root);
    }

    @Override public void state(String state, String message) {
        if (!visible) return;
        if ("connected".equals(state)) {
            if (!connected) displayScreen();
        } else if ("error".equals(state) || "disconnected".equals(state)) {
            if (connected) setupScreen();
            status.setText(message);
            status.setTextColor("error".equals(state) ? ERROR : MUTED);
            connect.setEnabled(true);
        } else {
            status.setText(message);
            status.setTextColor(MUTED);
            connect.setEnabled(false);
        }
    }

    @Override public void image(byte[] jpeg) {
        if (connected && display != null) display.setJpeg(jpeg);
    }

    @Override public void size(int width, int height) { /* JPEG supplies the exact render dimensions. */ }

    private final class DisplayView extends View {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private Bitmap bitmap;
        private final RectF image = new RectF();
        private float scrollY;
        private float lastX;
        private float lastY;
        private boolean touching;

        DisplayView() { super(MainActivity.this); setFocusable(true); }

        void setJpeg(byte[] jpeg) {
            Bitmap next = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (next == null) return;
            Bitmap old = bitmap;
            bitmap = next;
            if (old != null) old.recycle();
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (bitmap == null) return;
            float scale = Math.min(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight());
            float width = bitmap.getWidth() * scale;
            float height = bitmap.getHeight() * scale;
            image.set((getWidth() - width) / 2, (getHeight() - height) / 2,
                    (getWidth() + width) / 2, (getHeight() + height) / 2);
            canvas.drawBitmap(bitmap, null, image, paint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (bitmap == null) return true;
            if (event.getPointerCount() >= 2) {
                if (touching) { link.touch(1, lastX, lastY); touching = false; }
                float current = (event.getY(0) + event.getY(1)) / 2;
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    float delta = current - scrollY;
                    if (Math.abs(delta) >= dp(10)) {
                        link.touch(3, 0, Math.max(-1, Math.min(1, delta / dp(60))));
                        scrollY = current;
                    }
                } else scrollY = current;
                return true;
            }
            float x = Math.max(0, Math.min(1, (event.getX() - image.left) / image.width()));
            float y = Math.max(0, Math.min(1, (event.getY() - image.top) / image.height()));
            lastX = x;
            lastY = y;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: link.touch(0, x, y); touching = true; break;
                case MotionEvent.ACTION_MOVE: if (touching) link.touch(2, x, y); break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: if (touching) link.touch(1, x, y); touching = false; break;
            }
            return true;
        }
    }
}
