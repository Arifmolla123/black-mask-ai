package com.blackmask.jarvis;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Gemini Live voice (v4.9.31): the MYRAA way, copied natively.
 *
 * One open audio line to Google's Live API: the mic streams 16kHz PCM
 * continuously and the model answers in streaming audio. No sessions,
 * no restarts, no start "ding" - this is what MYRAA does, and it is
 * the only way to get truly continuous voice on Android (the stock
 * SpeechRecognizer is one-shot by design).
 *
 * Needs the user's own free Gemini key (the same key box in Settings).
 * The key is used only to mint a single-use Live token
 * (MYRAA's GeminiAccess.createLiveToken, copied here); the durable key
 * never goes on the socket.
 *
 * No new dependencies: the WebSocket client is raw SSLSocket + frames,
 * audio is AudioRecord/AudioTrack, JSON is org.json (built into Android).
 */
public class GeminiLiveVoice {

    public interface Listener {
        void onStatus(String text);
        void onLog(String who, String text);
        void onEnded(String reason);
        /** Run a phone action the Live model asked for. Returns the
         * result text for the model, or null when not handled. */
        String onToolCall(String name, JSONObject args);
    }

    private static final String LIVE_MODEL = "gemini-3.1-flash-live-preview";
    private static final String LIVE_VOICE = "Aoede";
    private static final String WS_HOST = "generativelanguage.googleapis.com";
    private static final String WS_PATH =
            "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained";

    private final Context appCtx;
    private final Listener listener;
    private volatile boolean running = false;
    private volatile boolean micOn = false;
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private Ws ws;
    private AudioRecord rec;
    private AudioTrack track;
    private final StringBuilder inText = new StringBuilder();
    private final StringBuilder outText = new StringBuilder();

    public GeminiLiveVoice(Context ctx, Listener listener) {
        this.appCtx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    /** Start the Live call on a background thread. */
    public void start(String apiKey) {
        if (running) return;
        running = true;
        finished.set(false);
        new Thread(() -> {
            try {
                listener.onStatus(appCtx.getString(R.string.live_connecting));
                String token = mintToken(apiKey);
                String path = WS_PATH + "?access_token=" + URLEncoder.encode(token, "UTF-8");
                ws = new Ws(WS_HOST, path);
                ws.sendText(setupJson());
                startTrack();
                startMic();
                listener.onStatus(appCtx.getString(R.string.live_on));
                String msg;
                while (running && (msg = ws.readText()) != null) {
                    handleMessage(msg);
                }
                if (running) end(appCtx.getString(R.string.live_disconnected));
            } catch (Exception e) {
                if (running) end(appCtx.getString(R.string.live_failed, safeMsg(e)));
            }
        }, "gemini-live").start();
    }

    /** Stop the call. Safe to call twice. */
    public void stop() {
        end(appCtx.getString(R.string.live_off));
    }

    private void end(String reason) {
        if (!finished.compareAndSet(false, true)) return;
        running = false;
        stopMic();
        closeWs();
        releaseTrack();
        flushLogs();
        listener.onEnded(reason);
    }

    private static String safeMsg(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return "unknown error";
        return m.length() > 200 ? m.substring(0, 200) : m;
    }

    // ---------- token (MYRAA's GeminiAccess.createLiveToken) ----------

    private static String mintToken(String key) throws Exception {
        HttpsURLConnection c = null;
        try {
            c = (HttpsURLConnection) new URL("https://generativelanguage.googleapis.com/v1beta/auth_tokens").openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("POST");
            c.setConnectTimeout(10000);
            c.setReadTimeout(12000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("x-goog-api-key", key);
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            long now = System.currentTimeMillis();
            JSONObject body = new JSONObject();
            body.put("uses", 1);
            body.put("expireTime", iso.format(new Date(now + 30 * 60 * 1000)));
            body.put("newSessionExpireTime", iso.format(new Date(now + 60 * 1000)));
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = c.getResponseCode();
            String payload = readAll(status < 300 ? c.getInputStream() : c.getErrorStream(), 65536);
            if (status < 200 || status >= 300) throw new Exception("Google " + status + ": " + shortError(payload));
            String token = new JSONObject(payload).optString("name", "");
            if (!token.startsWith("auth_tokens/")) throw new Exception("Invalid Live token response.");
            return token;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String shortError(String payload) {
        try {
            JSONObject e = new JSONObject(payload).optJSONObject("error");
            if (e != null) {
                String m = e.optString("message", "");
                if (!m.isEmpty()) return m;
            }
        } catch (Exception ignored) {}
        return payload.length() > 200 ? payload.substring(0, 200) : payload;
    }

    private static String readAll(InputStream input, int limit) throws Exception {
        if (input == null) return "";
        try (InputStream source = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = source.read(buffer)) != -1) {
                if (out.size() + count > limit) throw new Exception("Response too large.");
                out.write(buffer, 0, count);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // ---------- Live setup (MYRAA's LiveTransport setup) ----------

    private String setupJson() {
        return "{\"setup\":{"
                + "\"model\":\"models/" + LIVE_MODEL + "\","
                + "\"generationConfig\":{\"responseModalities\":[\"AUDIO\"],\"speechConfig\":{\"voiceConfig\":{\"prebuiltVoiceConfig\":{\"voiceName\":\"" + LIVE_VOICE + "\"}}}},"
                + "\"systemInstruction\":{\"parts\":[{\"text\":\"You are Black Mask AI, a friendly voice assistant on the user's Android phone. Speak naturally in the language the user speaks - Bengali, Hindi or English. Keep replies short, one or two sentences. Use the tools to open apps or search when the user asks.\"}]},"
                + "\"inputAudioTranscription\":{},\"outputAudioTranscription\":{},"
                + "\"tools\":[{\"functionDeclarations\":["
                + "{\"name\":\"open_app\",\"description\":\"Open an app on the phone by name, for example YouTube, WhatsApp, Telegram, Settings, Camera.\",\"parameters\":{\"type\":\"OBJECT\",\"properties\":{\"app\":{\"type\":\"STRING\",\"description\":\"The app name the user said.\"}},\"required\":[\"app\"]}},"
                + "{\"name\":\"search\",\"description\":\"Search YouTube or Google for something.\",\"parameters\":{\"type\":\"OBJECT\",\"properties\":{\"query\":{\"type\":\"STRING\"},\"target\":{\"type\":\"STRING\",\"description\":\"youtube or google\"}},\"required\":[\"query\"]}}"
                + "]}]}}";
    }

    // ---------- incoming messages ----------

    private void handleMessage(String raw) throws Exception {
        JSONObject data = new JSONObject(raw);
        if (data.has("error")) {
            JSONObject err = data.optJSONObject("error");
            String msg = err != null ? err.optString("message", "Live error") : "Live error";
            end(appCtx.getString(R.string.live_failed, msg));
            return;
        }
        if (data.optBoolean("setupComplete", false)) {
            listener.onStatus(appCtx.getString(R.string.live_on));
            return;
        }
        if (data.optBoolean("goAway", false)) {
            end(appCtx.getString(R.string.live_disconnected));
            return;
        }
        JSONObject tc = data.optJSONObject("toolCall");
        if (tc != null) {
            JSONArray calls = tc.optJSONArray("functionCalls");
            if (calls != null) {
                for (int i = 0; i < calls.length(); i++) {
                    JSONObject call = calls.optJSONObject(i);
                    if (call == null) continue;
                    String id = call.optString("id", "");
                    String name = call.optString("name", "");
                    JSONObject args = call.optJSONObject("args");
                    if (args == null) args = new JSONObject();
                    String result = listener.onToolCall(name, args);
                    if (result == null) result = "not available";
                    JSONObject fr = new JSONObject();
                    if (!id.isEmpty()) fr.put("id", id);
                    fr.put("name", name);
                    JSONObject r = new JSONObject();
                    r.put("result", result);
                    fr.put("response", r);
                    JSONArray arr = new JSONArray();
                    arr.put(fr);
                    JSONObject resp = new JSONObject();
                    resp.put("functionResponses", arr);
                    JSONObject msg = new JSONObject();
                    msg.put("toolResponse", resp);
                    ws.sendText(msg.toString());
                }
            }
            return;
        }
        JSONObject content = data.optJSONObject("serverContent");
        if (content == null) return;
        if (content.optBoolean("interrupted", false)) {
            if (track != null) {
                try { track.flush(); } catch (Exception ignored) {}
            }
            flushOutLog();
            return;
        }
        JSONObject inTr = content.optJSONObject("inputTranscription");
        if (inTr != null) {
            String t = inTr.optString("text", "");
            if (!t.isEmpty()) {
                synchronized (inText) { inText.append(t); }
            }
        }
        JSONObject outTr = content.optJSONObject("outputTranscription");
        if (outTr != null) {
            String t = outTr.optString("text", "");
            if (!t.isEmpty()) {
                synchronized (outText) { outText.append(t); }
            }
        }
        JSONObject turn = content.optJSONObject("modelTurn");
        if (turn != null) {
            JSONArray parts = turn.optJSONArray("parts");
            if (parts != null) {
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject part = parts.optJSONObject(i);
                    if (part == null) continue;
                    JSONObject inline = part.optJSONObject("inlineData");
                    if (inline == null) continue;
                    String mime = inline.optString("mimeType", "");
                    String b64 = inline.optString("data", "");
                    if (mime.startsWith("audio/pcm") && !b64.isEmpty() && track != null) {
                        byte[] pcm = Base64.decode(b64, Base64.DEFAULT);
                        track.write(pcm, 0, pcm.length);
                    }
                }
            }
        }
        if (content.optBoolean("turnComplete", false)) flushLogs();
    }

    private void flushLogs() {
        String in;
        synchronized (inText) { in = inText.toString().trim(); inText.setLength(0); }
        if (!in.isEmpty()) listener.onLog("You (live)", in);
        flushOutLog();
    }

    private void flushOutLog() {
        String out;
        synchronized (outText) { out = outText.toString().trim(); outText.setLength(0); }
        if (!out.isEmpty()) listener.onLog("BLACK MASK (live)", out);
    }

    // ---------- mic (16kHz PCM out) ----------

    private void startMic() {
        int minBuf = AudioRecord.getMinBufferSize(16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBuf * 2, 4096));
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            rec.release();
            rec = null;
            end(appCtx.getString(R.string.live_no_mic));
            return;
        }
        rec.startRecording();
        micOn = true;
        new Thread(() -> {
            byte[] buf = new byte[640]; // 20ms at 16kHz
            while (micOn && running) {
                int n = rec.read(buf, 0, buf.length);
                if (n > 0 && ws != null) {
                    try {
                        String b64 = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP);
                        JSONObject audio = new JSONObject();
                        audio.put("mimeType", "audio/pcm;rate=16000");
                        audio.put("data", b64);
                        JSONObject ri = new JSONObject();
                        ri.put("audio", audio);
                        JSONObject msg = new JSONObject();
                        msg.put("realtimeInput", ri);
                        ws.sendText(msg.toString());
                    } catch (Exception ignored) {}
                }
            }
        }, "gemini-live-mic").start();
    }

    private void stopMic() {
        micOn = false;
        if (rec != null) {
            try { rec.stop(); } catch (Exception ignored) {}
            try { rec.release(); } catch (Exception ignored) {}
            rec = null;
        }
    }

    // ---------- speaker (24kHz PCM in) ----------

    private void startTrack() {
        int minBuf = AudioTrack.getMinBufferSize(24000,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack(AudioManager.STREAM_MUSIC, 24000,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBuf * 2, 8192), AudioTrack.MODE_STREAM);
        track.play();
    }

    private void releaseTrack() {
        if (track != null) {
            try { track.stop(); } catch (Exception ignored) {}
            try { track.release(); } catch (Exception ignored) {}
            track = null;
        }
    }

    private void closeWs() {
        if (ws != null) {
            try { ws.close(); } catch (Exception ignored) {}
            ws = null;
        }
    }

    // ---------- minimal WebSocket client (no dependency) ----------

    private static final class Ws {
        private final SSLSocket sock;
        private final InputStream in;
        private final OutputStream out;

        Ws(String host, String path) throws Exception {
            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            sock = (SSLSocket) f.createSocket(host, 443);
            sock.setSoTimeout(0);
            in = sock.getInputStream();
            out = sock.getOutputStream();
            byte[] keyBytes = new byte[16];
            new SecureRandom().nextBytes(keyBytes);
            String key = Base64.encodeToString(keyBytes, Base64.NO_WRAP);
            String req = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.UTF_8));
            out.flush();
            boolean ok = false;
            String line;
            while ((line = readLine()) != null) {
                if (line.isEmpty()) break;
                if (line.startsWith("HTTP/") && line.contains("101")) ok = true;
            }
            if (!ok) throw new Exception("WebSocket handshake failed.");
        }

        private String readLine() throws Exception {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') break;
                if (c != '\r') b.write(c);
            }
            if (c == -1 && b.size() == 0) return null;
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }

        void sendText(String s) throws Exception {
            sendFrame(0x1, s.getBytes(StandardCharsets.UTF_8));
        }

        private void sendFrame(int opcode, byte[] payload) throws Exception {
            ByteArrayOutputStream h = new ByteArrayOutputStream();
            h.write(0x80 | opcode);
            byte[] mask = new byte[4];
            new SecureRandom().nextBytes(mask);
            int len = payload.length;
            if (len < 126) {
                h.write(0x80 | len);
            } else if (len < 65536) {
                h.write(0x80 | 126);
                h.write((len >> 8) & 0xFF);
                h.write(len & 0xFF);
            } else {
                h.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) h.write((len >> (8 * i)) & 0xFF);
            }
            h.write(mask);
            byte[] masked = new byte[len];
            for (int i = 0; i < len; i++) masked[i] = (byte) (payload[i] ^ mask[i % 4]);
            synchronized (out) {
                out.write(h.toByteArray());
                out.write(masked);
                out.flush();
            }
        }

        /** One complete text message, reassembling fragments. Null on close/EOF. */
        String readText() throws Exception {
            ByteArrayOutputStream msg = new ByteArrayOutputStream();
            while (true) {
                int b0 = in.read();
                if (b0 == -1) return null;
                int b1 = in.read();
                if (b1 == -1) return null;
                boolean fin = (b0 & 0x80) != 0;
                int op = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) {
                    len = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xFF);
                }
                byte[] mask = null;
                if (masked) {
                    mask = new byte[4];
                    readFully(mask);
                }
                if (len > 64L * 1024 * 1024) throw new Exception("WebSocket frame too large.");
                byte[] payload = new byte[(int) len];
                readFully(payload);
                if (masked) {
                    for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                }
                if (op == 0x9) { // ping -> pong
                    sendFrame(0xA, payload);
                    continue;
                }
                if (op == 0x8) return null; // close
                if (op == 0x1 || op == 0x0) {
                    msg.write(payload);
                    if (fin) return new String(msg.toByteArray(), StandardCharsets.UTF_8);
                }
            }
        }

        private void readFully(byte[] b) throws Exception {
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n == -1) throw new Exception("WebSocket closed.");
                off += n;
            }
        }

        void close() {
            try { sendFrame(0x8, new byte[0]); } catch (Exception ignored) {}
            try { sock.close(); } catch (Exception ignored) {}
        }
    }
}
