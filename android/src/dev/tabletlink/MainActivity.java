package dev.tabletlink;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
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
    private Button disconnect;
    private volatile DisplayView display;
    private final Runnable hideDisconnect = () -> { if (disconnect != null) disconnect.setVisibility(View.INVISIBLE); };
    private int videoWidth;
    private int videoHeight;
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

    @Override public void onBackPressed() {
        if (connected) disconnect();
        else super.onBackPressed();
    }

    private void disconnect() {
        link.stop();
        setupScreen();
        status.setText("Disconnected");
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
        display = null;
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
            status.setText("");
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
        display = new DisplayView(videoWidth, videoHeight);
        root.addView(display, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        disconnect = new Button(this);
        disconnect.setText("Disconnect");
        disconnect.setTextColor(INK);
        disconnect.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(242, 244, 241)));
        FrameLayout.LayoutParams button = new FrameLayout.LayoutParams(dp(130), dp(46), Gravity.TOP | Gravity.RIGHT);
        button.setMargins(0, dp(12), dp(12), 0);
        root.addView(disconnect, button);
        disconnect.setOnClickListener(view -> disconnect());
        setContentView(root);
        showDisconnect();
    }

    // The button would cover the desktop, so it only appears briefly: on connecting and on a three-finger touch.
    private void showDisconnect() {
        disconnect.setVisibility(View.VISIBLE);
        root.removeCallbacks(hideDisconnect);
        root.postDelayed(hideDisconnect, 4000);
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

    @Override public boolean picture(byte[] data, int length, boolean key) {
        DisplayView view = display;
        return view != null && view.picture(data, length, key);
    }

    @Override public void size(int width, int height) {
        videoWidth = width;
        videoHeight = height;
    }

    private final class DisplayView extends SurfaceView implements SurfaceHolder.Callback {
        private final int width;
        private final int height;
        private final Object codecLock = new Object();
        private volatile MediaCodec codec;
        private Thread drain;
        private float scrollY;
        private float lastX;
        private float lastY;
        private boolean touching;

        DisplayView(int width, int height) {
            super(MainActivity.this);
            this.width = Math.max(width, 1);
            this.height = Math.max(height, 1);
            getHolder().addCallback(this);
        }

        // Keeps the desktop's shape, leaving bars when the tablet's differs.
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int w = MeasureSpec.getSize(widthSpec);
            int h = MeasureSpec.getSize(heightSpec);
            if ((long) w * height > (long) h * width) w = (int) ((long) h * width / height);
            else h = (int) ((long) w * height / width);
            setMeasuredDimension(w, h);
        }

        @Override public void surfaceCreated(SurfaceHolder holder) {
            try {
                MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
                format.setInteger("low-latency", 1);
                MediaCodec next = MediaCodec.createDecoderByType("video/avc");
                next.configure(format, holder.getSurface(), null, 0);
                next.start();
                codec = next;
                drain = new Thread(() -> drain(next), "Tablet display");
                drain.start();
                link.videoReady(true);
            } catch (Exception error) {
                link.stop();
                setupScreen();
                status.setText("This tablet cannot decode the display stream.");
                status.setTextColor(ERROR);
            }
        }

        @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) { }

        @Override public void surfaceDestroyed(SurfaceHolder holder) {
            link.videoReady(false);
            MediaCodec old;
            synchronized (codecLock) { old = codec; codec = null; }
            if (old == null) return;
            try { drain.join(500); } catch (InterruptedException ignored) { }
            try { old.stop(); } catch (IllegalStateException ignored) { }
            old.release();
        }

        boolean picture(byte[] data, int length, boolean key) {
            synchronized (codecLock) {
                MediaCodec decoder = codec;
                if (decoder == null) return false;
                try {
                    int index = decoder.dequeueInputBuffer(10000);
                    if (index < 0) return false;
                    java.nio.ByteBuffer buffer = decoder.getInputBuffer(index);
                    if (buffer == null || buffer.capacity() < length) {
                        decoder.queueInputBuffer(index, 0, 0, 0, 0);
                        return false;
                    }
                    buffer.clear();
                    buffer.put(data, 0, length);
                    decoder.queueInputBuffer(index, 0, length, System.nanoTime() / 1000,
                            key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                    return true;
                } catch (IllegalStateException error) { return false; }
            }
        }

        // Shows each decoded picture immediately; the newest one is always the right one.
        private void drain(MediaCodec decoder) {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int shown = 0;
            long reported = System.nanoTime();
            try {
                while (codec == decoder) {
                    int index = decoder.dequeueOutputBuffer(info, 20000);
                    if (index >= 0) { decoder.releaseOutputBuffer(index, true); shown++; }
                    long now = System.nanoTime();
                    if (now - reported >= 2_000_000_000L) {
                        Log.i("TabletLink", String.format("display: %.1f fps", shown * 1e9 / (now - reported)));
                        shown = 0;
                        reported = now;
                    }
                }
            } catch (IllegalStateException ignored) { }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getPointerCount() >= 3) {
                if (touching) { link.touch(1, lastX, lastY); touching = false; }
                showDisconnect();
                return true;
            }
            if (event.getPointerCount() == 2) {
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
            float x = Math.max(0, Math.min(1, event.getX() / getWidth()));
            float y = Math.max(0, Math.min(1, event.getY() / getHeight()));
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
