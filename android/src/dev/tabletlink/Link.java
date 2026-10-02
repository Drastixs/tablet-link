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
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
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
        void size(int width, int height);
        /** Called off the main thread. Returns false when the picture could not be decoded. */
        boolean picture(byte[] data, int length, boolean key);
    }

    private static final String TAG = "TabletLink";
    private static final int VIDEO_HEADER = 9;
    private static final int VIDEO_PAYLOAD = 1150;
    private static final int VIDEO_MAX_PARTS = 4096;
    private static final int VIDEO_WINDOW = 64;
    private static final long RESEND_EVERY = 80_000_000L;
    private static final long RESEND_GIVE_UP = 300_000_000L;

    private static final class Picture {
        int number, parts, received, length;
        boolean key;
        boolean[] seen;
        byte[] data;
        long asked;
    }

    private final Events events;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object writerLock = new Object();
    private final ArrayBlockingQueue<byte[]> speakerQueue = new ArrayBlockingQueue<>(12);
    private final LinkedBlockingQueue<byte[]> touchQueue = new LinkedBlockingQueue<>();
    private Socket socket;
    private DatagramSocket video;
    private DataInputStream input;
    private DataOutputStream output;
    private AudioRecord recorder;
    private AudioTrack player;
    private AcousticEchoCanceler echo;
    private Thread thread;
    private volatile boolean videoReady;
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
        if (video != null) video.close();
        if (recorder != null) {
            try { recorder.stop(); } catch (IllegalStateException ignored) { }
        }
    }

    /** The desktop starts the display stream once the decoder is ready for its first keyframe. */
    void videoReady(boolean ready) { videoReady = ready; }

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
                    if (video == null) startVideo(ip, status.optString("token"));
                    ui.post(() -> events.size(width, height));
                    notifyState("connected", "Connected");
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
            videoReady = false;
            speakerQueue.offer(new byte[0]);
            touchQueue.offer(new byte[0]);
            try { if (socket != null) socket.close(); } catch (IOException ignored) { }
            if (video != null) { video.close(); video = null; }
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

    private void startVideo(String ip, String token) throws IOException {
        byte[] id = fromHex(token);
        if (id.length != 16) throw new IOException("Desktop companion is out of date");
        byte[] hello = new byte[4 + id.length];
        System.arraycopy("TLV1".getBytes("UTF-8"), 0, hello, 0, 4);
        System.arraycopy(id, 0, hello, 4, id.length);
        byte[] resend = hello.clone();
        resend[2] = 'N';
        DatagramSocket next = new DatagramSocket();
        next.setReceiveBufferSize(1 << 20);
        next.setSoTimeout(1000);
        next.connect(new InetSocketAddress(ip, 39471));
        video = next;
        new Thread(() -> helloLoop(next, hello), "Tablet video hello").start();
        new Thread(() -> videoLoop(next, resend), "Tablet video").start();
    }

    // Tells the desktop where to send video, and repeats it to keep the path open.
    private void helloLoop(DatagramSocket socket, byte[] hello) {
        int sent = 0;
        while (running.get() && !socket.isClosed()) {
            try {
                if (videoReady) { socket.send(new DatagramPacket(hello, hello.length)); sent++; }
                Thread.sleep(videoReady && sent > 3 ? 1000 : 100);
            } catch (IOException ignored) {
            } catch (InterruptedException error) { return; }
        }
    }

    // Pictures arrive as numbered datagram parts and are decoded strictly in order. Missing
    // parts are asked for again; if they still do not come, decoding resumes at the next
    // keyframe so a loss never smears across the screen.
    private void videoLoop(DatagramSocket socket, byte[] resend) {
        byte[] buffer = new byte[1500];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        Picture[] window = new Picture[VIDEO_WINDOW];
        boolean started = false, needKey = true;
        int next = 0, newest = 0;
        long waiting = 0;
        int shown = 0, lost = 0, skipped = 0, asked = 0;
        long bytes = 0, reported = System.nanoTime();
        while (running.get()) {
            try { socket.receive(packet); }
            catch (SocketTimeoutException error) { continue; }
            catch (IOException error) { return; }
            int size = packet.getLength() - VIDEO_HEADER;
            if (size <= 0 || size > VIDEO_PAYLOAD) continue;
            ByteBuffer header = ByteBuffer.wrap(buffer, 0, VIDEO_HEADER);
            int number = header.getInt();
            int index = header.getShort() & 0xffff;
            int count = header.getShort() & 0xffff;
            boolean key = header.get() != 0;
            if (count == 0 || count > VIDEO_MAX_PARTS || index >= count) continue;
            bytes += packet.getLength();
            if (!started || number - next >= VIDEO_WINDOW) {
                // First picture, or the stream ran too far ahead to catch up.
                java.util.Arrays.fill(window, null);
                started = true;
                needKey = true;
                next = newest = number;
                waiting = 0;
            }
            if (number - next < 0) continue;
            if (number - newest > 0) newest = number;
            Picture picture = window[number & (VIDEO_WINDOW - 1)];
            if (picture == null || picture.number != number || picture.parts < 0) {
                long before = picture != null && picture.number == number ? picture.asked : 0;
                picture = new Picture();
                picture.asked = before;
                picture.number = number;
                picture.parts = count;
                picture.key = key;
                picture.seen = new boolean[count];
                picture.data = new byte[count * VIDEO_PAYLOAD];
                window[number & (VIDEO_WINDOW - 1)] = picture;
            }
            if (count == picture.parts && !picture.seen[index]) {
                picture.seen[index] = true;
                picture.received++;
                System.arraycopy(buffer, VIDEO_HEADER, picture.data, index * VIDEO_PAYLOAD, size);
                if (index == count - 1) picture.length = index * VIDEO_PAYLOAD + size;
            }
            long now = System.nanoTime();
            while (next - newest <= 0) {
                Picture head = window[next & (VIDEO_WINDOW - 1)];
                boolean known = head != null && head.number == next && head.parts > 0;
                boolean complete = known && head.received == head.parts;
                if (!complete) {
                    // A newer keyframe makes everything before it unnecessary.
                    int jump = next;
                    for (int later = newest; later - next > 0 && jump == next; later--) {
                        Picture candidate = window[later & (VIDEO_WINDOW - 1)];
                        if (candidate != null && candidate.number == later && candidate.key
                                && (needKey || candidate.received == candidate.parts)) jump = later;
                    }
                    if (jump != next) {
                        skipped += jump - next;
                        needKey = true;
                        for (; next != jump; next++) window[next & (VIDEO_WINDOW - 1)] = null;
                        waiting = 0;
                        continue;
                    }
                    if (next == newest) break;  // still arriving
                    if (needKey && known && !head.key) {
                        skipped++;  // cannot be decoded without its keyframe
                    } else {
                        if (waiting == 0) waiting = now;
                        if (now - waiting < RESEND_GIVE_UP) break;
                        lost++;
                        needKey = true;
                    }
                } else {
                    if (head.key) needKey = false;
                    if (needKey) skipped++;
                    else if (events.picture(head.data, head.length, head.key)) shown++;
                    else needKey = true;
                }
                window[next & (VIDEO_WINDOW - 1)] = null;
                next++;
                waiting = 0;
            }
            // Ask again for every picture that a newer one has overtaken.
            for (int wanted = next; wanted - newest < 0; wanted++) {
                Picture hole = window[wanted & (VIDEO_WINDOW - 1)];
                if (hole != null && hole.number != wanted) hole = null;
                if (hole != null && (hole.received == hole.parts || now - hole.asked < RESEND_EVERY)) continue;
                if (needKey && hole != null && hole.parts > 0 && !hole.key) continue;
                if (hole == null) {
                    // Nothing of it arrived, so remember only when it was asked for.
                    hole = new Picture();
                    hole.number = wanted;
                    hole.parts = -1;
                    window[wanted & (VIDEO_WINDOW - 1)] = hole;
                }
                hole.asked = now;
                ByteBuffer request = ByteBuffer.allocate(resend.length + 4 + 2 * 128);
                request.put(resend).putInt(wanted);
                for (int part = 0; part < hole.parts && request.remaining() >= 2; part++)
                    if (!hole.seen[part]) request.putShort((short) part);
                try { socket.send(new DatagramPacket(request.array(), request.position())); asked++; }
                catch (IOException ignored) { }
            }
            if (now - reported >= 2_000_000_000L) {
                double seconds = (now - reported) / 1e9;
                Log.i(TAG, String.format("video: %.1f fps, %d lost, %d skipped, %d resends, %.0f kbit/s",
                        shown / seconds, lost, skipped, asked, bytes * 8 / 1000 / seconds));
                shown = lost = skipped = asked = 0;
                bytes = 0;
                reported = now;
            }
        }
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
