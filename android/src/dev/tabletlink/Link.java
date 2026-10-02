package dev.tabletlink;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.os.Handler;
import android.os.Looper;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class Link {
    interface Events {
        void state(String state, String message);
        void image(byte[] jpeg);
        void size(int width, int height);
    }

    private final Events events;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean imageQueued = new AtomicBoolean(false);
    private final Object writerLock = new Object();
    private final ArrayBlockingQueue<byte[]> speakerQueue = new ArrayBlockingQueue<>(12);
    private final LinkedBlockingQueue<byte[]> touchQueue = new LinkedBlockingQueue<>();
    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private AudioRecord recorder;
    private AudioTrack player;
    private AcousticEchoCanceler echo;
    private Thread thread;
    private volatile String finalError = null;

    Link(Events events) { this.events = events; }

    void start(String ip) {
        if (!running.compareAndSet(false, true)) return;
        finalError = null;
        speakerQueue.clear();
        touchQueue.clear();
        notifyState("connecting", "Connecting to " + ip + "…");
        thread = new Thread(() -> run(ip), "Tablet Link");
        thread.start();
    }

    void stop() {
        running.set(false);
        speakerQueue.offer(new byte[0]);
        touchQueue.offer(new byte[0]);
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        if (recorder != null) {
            try { recorder.stop(); } catch (IllegalStateException ignored) { }
        }
    }

    private void run(String ip) {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(ip, 39471), 5000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(15000);
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
            byte[] magic = new byte[5];
            input.readFully(magic);
            if (!MessageDigest.isEqual(magic, "TLNK1".getBytes("UTF-8"))) throw new IOException("Invalid desktop companion");
            byte[] challenge = new byte[32];
            input.readFully(challenge);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(fromHex(Secret.HEX), "HmacSHA256"));
            output.write(mac.doFinal(challenge));
            output.flush();
            if (input.readUnsignedByte() != 1) throw new IOException("Shared secret rejected");
            startAudio();
            new Thread(this::recordLoop, "Tablet microphone").start();
            new Thread(this::playLoop, "Tablet speaker").start();
            new Thread(this::heartbeatLoop, "Tablet heartbeat").start();
            new Thread(this::touchLoop, "Tablet touch").start();
            while (running.get()) {
                int type = input.readUnsignedByte();
                int length = input.readInt();
                if (length < 0 || length > 4 * 1024 * 1024) throw new IOException("Invalid frame size");
                byte[] payload = new byte[length];
                input.readFully(payload);
                if (type == 1) {
                    String json = new String(payload, "UTF-8");
                    org.json.JSONObject status = new org.json.JSONObject(json);
                    if ("error".equals(status.optString("state"))) throw new IOException(status.optString("message", "Desktop error"));
                    int width = status.optInt("width");
                    int height = status.optInt("height");
                    ui.post(() -> events.size(width, height));
                    notifyState("connected", "Connected");
                } else if (type == 2) {
                    if (imageQueued.compareAndSet(false, true)) {
                        ui.post(() -> {
                            try { events.image(payload); }
                            finally { imageQueued.set(false); }
                        });
                    }
                } else if (type == 3) {
                    if (!speakerQueue.offer(payload)) {
                        speakerQueue.poll();
                        speakerQueue.offer(payload);
                    }
                } else {
                    throw new IOException("Unknown desktop frame");
                }
            }
        } catch (Exception error) {
            if (running.get()) finalError = error.getMessage() == null ? "Connection lost" : error.getMessage();
        } finally {
            running.set(false);
            speakerQueue.offer(new byte[0]);
            touchQueue.offer(new byte[0]);
            try { if (socket != null) socket.close(); } catch (IOException ignored) { }
            if (recorder != null) {
                try { recorder.stop(); } catch (IllegalStateException ignored) { }
                recorder.release(); recorder = null;
            }
            if (player != null) { player.release(); player = null; }
            if (echo != null) { echo.release(); echo = null; }
            notifyState(finalError == null ? "disconnected" : "error", finalError == null ? "Disconnected" : finalError);
        }
    }

    private void startAudio() throws IOException {
        int inMin = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int outMin = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (inMin < 0 || outMin < 0) throw new IOException("48 kHz audio is unavailable");
        recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 48000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(inMin, 7680));
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Microphone could not start");
        if (AcousticEchoCanceler.isAvailable()) {
            echo = AcousticEchoCanceler.create(recorder.getAudioSessionId());
            if (echo != null) echo.setEnabled(true);
        }
        player = new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                Math.max(outMin, 7680), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
        if (player.getState() != AudioTrack.STATE_INITIALIZED) throw new IOException("Speaker could not start");
        player.play();
        recorder.startRecording();
    }

    private void recordLoop() {
        byte[] buffer = new byte[1920];
        while (running.get()) {
            int count = recorder.read(buffer, 0, buffer.length);
            if (count < 0) { fail("Microphone stopped"); return; }
            if (count > 0) {
                byte[] data = new byte[count];
                System.arraycopy(buffer, 0, data, 0, count);
                try { send(4, data); } catch (IOException error) { fail("Microphone connection lost"); return; }
            }
        }
    }

    private void playLoop() {
        while (running.get()) {
            try {
                byte[] data = speakerQueue.take();
                if (player.write(data, 0, data.length) < 0) { fail("Speaker stopped"); return; }
            } catch (InterruptedException error) { return; }
        }
    }

    private void heartbeatLoop() {
        while (running.get()) {
            try { Thread.sleep(3000); if (running.get()) send(6, new byte[0]); }
            catch (Exception error) { fail("Desktop connection lost"); return; }
        }
    }

    void touch(int action, float x, float y) {
        if (!running.get()) return;
        ByteBuffer packet = ByteBuffer.allocate(9);
        packet.put((byte) action).putFloat(x).putFloat(y);
        touchQueue.offer(packet.array());
    }

    // Touch events arrive on the main thread, which must not write to the socket.
    private void touchLoop() {
        while (running.get()) {
            try {
                byte[] packet = touchQueue.take();
                if (packet.length > 0) send(5, packet);
            } catch (InterruptedException error) { return; }
            catch (IOException error) { fail("Touch connection lost"); return; }
        }
    }

    private void send(int type, byte[] data) throws IOException {
        synchronized (writerLock) {
            if (!running.get() || output == null) return;
            output.writeByte(type);
            output.writeInt(data.length);
            output.write(data);
            output.flush();
        }
    }

    private void fail(String message) {
        if (!running.get()) return;
        finalError = message;
        stop();
    }

    private void notifyState(String state, String message) { ui.post(() -> events.state(state, message)); }

    private static byte[] fromHex(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++)
            bytes[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return bytes;
    }
}
