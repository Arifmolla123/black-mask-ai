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

    // Live models to try, in order (v4.9.39): gemini-3.1 first -
    // MYRAA's own video on this phone proves it connects. Then
    // Google's Native Audio replacement, then the GA native audio
    // name. (v4.9.36) Google shut down gemini-2.0-flash-live-001 on
    // 9 Dec 2025 - the server itself said "not found", so it is out.
    private static final String[] LIVE_MODELS = {
            "gemini-3.1-flash-live-preview",
            "gemini-2.5-flash-native-audio-preview-09-2025",
            "gemini-live-2.5-flash-native-audio",
    };
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
    private volatile boolean micLogged = false;
    private volatile boolean heardLogged = false;
    private volatile boolean trackWarned = false;

    public GeminiLiveVoice(Context ctx, Listener listener) {
        this.appCtx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    /** Start the Live call on a background thread. Tries the Live
     * models in order: if the server rejects the first one, the
     * session retries with the fallback model automatically. Every
     * stage is logged to the Chat page, so a silent failure always
     * leaves a last line to read. */
    public void start(String apiKey) {
        if (running) return;
        running = true;
        finished.set(false);
        micLogged = false;
        heardLogged = false;
        trackWarned = false;
        new Thread(() -> {
            String[] models = LIVE_MODELS;
            String lastErr = null;
            for (int mi = 0; mi < models.length; mi++) {
                String model = models[mi];
                if (!running) return;
                try {
                    listener.onLog("Live", appCtx.getString(R.string.live_diag_model, model));
                    String token = mintToken(apiKey);
                    listener.onLog("Live", appCtx.getString(R.string.live_diag_token));
                    String path = WS_PATH + "?access_token=" + URLEncoder.encode(token, "UTF-8");
                    ws = new Ws(WS_HOST, path);
                    // (v4.9.34) 45s, not 20s: on a slow connection the
                    // setup answer can take a while to come back.
                    ws.setTimeout(45000);
                    ws.sendText(setupJson(model));
                    // Wait for setupComplete (or an error) before
                    // starting the mic - no point streaming into a
                    // session the server never accepted.
                    boolean ready = false;
                    String first;
                    while (running && (first = ws.readText()) != null) {
                        if (handleSetupMessage(first)) { ready = true; break; }
                    }
                    if (!ready) {
                        if (!running) return;
                        // (v4.9.34) Say WHY the server hung up - the
                        // close code/reason is the real diagnosis.
                        throw new Exception(appCtx.getString(R.string.live_disconnected)
                                + " (" + ws.closeInfo() + ")");
                    }
                    ws.setTimeout(0);
                    listener.onStatus(appCtx.getString(R.string.live_on));
                    listener.onLog("Live", appCtx.getString(R.string.live_diag_ready));
                    startTrack();
                    startMic();
                    String msg;
                    while (running && (msg = ws.readText()) != null) {
                        handleMessage(msg);
                    }
                    if (running) end(appCtx.getString(R.string.live_disconnected)
                            + " (" + ws.closeInfo() + ")");
                    return;
                } catch (Exception e) {
                    lastErr = safeMsg(e);
                    closeWs();
                    if (!running) return;
                    if (mi + 1 < models.length) {
                        // (v4.9.36) Show WHY this model failed, not
                        // just that it did - the reason decides the
                        // next fix. Capped so the line stays readable.
                        String why = lastErr.length() > 120
                                ? lastErr.substring(0, 120) : lastErr;
                        listener.onLog("Live", appCtx.getString(
                                R.string.live_diag_retry, model, models[mi + 1], why));
                    }
                }
            }
            if (running) end(appCtx.getString(R.string.live_failed,
                    lastErr != null ? lastErr : "?"));
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
            // (v4.9.34) 5 min to start the session, not 60s: on a slow
            // connection (the phone showed 0.16 KB/s) TLS + the WS
            // handshake alone can take over a minute after minting.
            body.put("newSessionExpireTime", iso.format(new Date(now + 300 * 1000)));
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

    private String setupJson(String model) {
        return "{\"setup\":{"
                + "\"model\":\"models/" + model + "\","
                + "\"generationConfig\":{\"responseModalities\":[\"AUDIO\"],\"speechConfig\":{\"voiceConfig\":{\"prebuiltVoiceConfig\":{\"voiceName\":\"" + LIVE_VOICE + "\"}}}},"
                + "\"systemInstruction\":{\"parts\":[{\"text\":\"You are Black Mask AI, a friendly voice assistant on the user's Android phone. Speak naturally in the language the user speaks - Bengali, Hindi or English. Keep replies short, one or two sentences. To do things on the phone - call someone, send a message, open an app, turn the flashlight on or off, set a reminder, check the weather or the time - use the run_command tool with the exact command, for example 'call Arif', 'open youtube', 'flashlight on'. Use open_app only to open apps and search only to search. Never say you cannot do a phone action - run it with run_command.\"}]},"
                + "\"inputAudioTranscription\":{},\"outputAudioTranscription\":{},"
                + "\"tools\":[{\"functionDeclarations\":["
                + "{\"name\":\"open_app\",\"description\":\"Open an app on the phone by name, for example YouTube, WhatsApp, Telegram, Settings, Camera.\",\"parameters\":{\"type\":\"OBJECT\",\"properties\":{\"app\":{\"type\":\"STRING\",\"description\":\"The app name the user said.\"}},\"required\":[\"app\"]}},"
                + "{\"name\":\"search\",\"description\":\"Search YouTube or Google for something.\",\"parameters\":{\"type\":\"OBJECT\",\"properties\":{\"query\":{\"type\":\"STRING\"},\"target\":{\"type\":\"STRING\",\"description\":\"youtube or google\"}},\"required\":[\"query\"]}},"
                + "{\"name\":\"run_command\",\"description\":\"Do anything on the phone the user asked for: call someone, send an SMS or WhatsApp message, turn the flashlight on or off, set a reminder or alarm, check weather, time or battery, or any other phone action. Pass the exact command in plain words.\",\"parameters\":{\"type\":\"OBJECT\",\"properties\":{\"command\":{\"type\":\"STRING\",\"description\":\"The exact command, for example 'call Arif', 'send sms to Maa I am coming home', 'flashlight on', 'set reminder 8pm call Baba'.\"}},\"required\":[\"command\"]}}"
                + "]}]}}";
    }

    // ---------- incoming messages ----------

    /** Messages that arrive while waiting for setupComplete.
     * Returns true once the server accepted the session. Throws on
     * a server error or a go-away, so the caller can retry the next
     * model. (setupComplete/goAway are objects, not booleans - an
     * optBoolean() check would never see them.) */
    /** Full server error text: message + status + code, so the Chat
     * page shows the real reason instead of a bare "Live error". */
    private static String errText(JSONObject err) {
        if (err == null) return "Live error";
        String msg = err.optString("message", "Live error");
        String st = err.optString("status", "");
        int code = err.optInt("code", 0);
        if (!st.isEmpty() || code != 0) msg = msg + " [" + st + " " + code + "]";
        return msg;
    }

    private boolean handleSetupMessage(String raw) throws Exception {
        JSONObject data = new JSONObject(raw);
        if (data.has("error")) {
            throw new Exception(errText(data.optJSONObject("error")));
        }
        if (data.has("setupComplete")) return true;
        if (data.has("goAway")) {
            JSONObject g = data.optJSONObject("goAway");
            String left = g != null ? g.optString("timeLeft", "?") : "?";
            throw new Exception(appCtx.getString(R.string.live_disconnected)
                    + " (goAway, " + left + " left)");
        }
        return false;
    }

    private void handleMessage(String raw) throws Exception {
        JSONObject data = new JSONObject(raw);
        if (data.has("error")) {
            end(appCtx.getString(R.string.live_failed, errText(data.optJSONObject("error"))));
            return;
        }
        if (data.has("setupComplete")) {
            listener.onStatus(appCtx.getString(R.string.live_on));
            return;
        }
        if (data.has("goAway")) {
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
                if (!heardLogged) {
                    heardLogged = true;
                    listener.onLog("Live", appCtx.getString(R.string.live_diag_heard));
                }
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
                        int written = track.write(pcm, 0, pcm.length);
                        if (written < 0 && !trackWarned) {
                            trackWarned = true;
                            listener.onLog("Live", appCtx.getString(R.string.live_diag_speaker));
                        }
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
            try {
                while (micOn && running) {
                    int n = rec.read(buf, 0, buf.length);
                    if (n < 0) {
                        end(appCtx.getString(R.string.live_failed, "mic read " + n));
                        return;
                    }
                    if (n > 0 && ws != null) {
                        if (!micLogged) {
                            micLogged = true;
                            listener.onLog("Live", appCtx.getString(R.string.live_diag_mic));
                        }
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
            } catch (Exception e) {
                // A dead mic thread used to kill the call silently
                // (the server heard nothing, the user heard nothing).
                if (running) end(appCtx.getString(R.string.live_failed, safeMsg(e)));
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
        // (v4.9.34) Why the server hung up, when it sends a close
        // frame - the code/reason is the real diagnosis.
        private int closeCode = -1;
        private String closeReason = "";

        String closeInfo() {
            if (closeCode < 0) return "closed, no reason given";
            return "close " + closeCode
                    + (closeReason.isEmpty() ? "" : ": " + closeReason);
        }

        Ws(String host, String path) throws Exception {
            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            // (v4.9.40) Cap the TCP connect at 15s: on a weak
            // network a bare createSocket(host, port) can hang for
            // minutes before the OS gives up, and Live looks frozen.
            SSLSocket s = (SSLSocket) f.createSocket();
            s.connect(new java.net.InetSocketAddress(host, 443), 15000);
            sock = s;
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
                    + "Origin: https://generativelanguage.googleapis.com\r\n"
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

        void setTimeout(int ms) throws Exception {
            sock.setSoTimeout(ms);
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
                if (op == 0x8) { // close - remember why
                    if (payload.length >= 2) {
                        closeCode = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
                        closeReason = new String(payload, 2, payload.length - 2,
                                StandardCharsets.UTF_8);
                    }
                    return null;
                }
                if (op == 0x1 || op == 0x0 || op == 0x2) {
                    // (v4.9.39) Binary frames too: MYRAA sets
                    // binaryType='blob' and reads Blob messages as
                    // text - the server can send JSON in binary
                    // frames, and ignoring them meant setupComplete
                    // could arrive without us ever seeing it.
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
