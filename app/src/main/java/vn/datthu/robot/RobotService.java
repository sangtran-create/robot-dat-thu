package vn.datthu.robot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Vòng hội thoại: nghe → gửi Dify → đọc câu trả lời → nghe tiếp.
 * Chạy nền (foreground) nên vẫn hoạt động khi tắt màn hình.
 */
public class RobotService extends Service {

    public static final String ACTION_START = "vn.datthu.robot.START";
    public static final String ACTION_TAP = "vn.datthu.robot.TAP";
    public static final String ACTION_STOP = "vn.datthu.robot.STOP";
    public static final String ACTION_TEXT = "vn.datthu.robot.TEXT";
    public static final String ACTION_NEW_CHAT = "vn.datthu.robot.NEW_CHAT";

    public interface Ui { void onRobotEvent(JSONObject ev); }

    public static volatile Ui ui;
    public static volatile String state = "idle";
    public static volatile String lastError = "";

    private static final String CHANNEL = "robot";
    private static final Locale VI = new Locale("vi", "VN");

    private final Handler h = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private SpeechRecognizer sr;
    private TextToSpeech tts;
    private boolean ttsReady;
    private DifyClient.Call call;
    private PowerManager.WakeLock wakeLock;
    private boolean foreground;

    private final StringBuilder heard = new StringBuilder();
    private String partial = "";
    private long lastVoiceAt;
    private boolean forceSend;
    private boolean ending;
    private boolean listeningActive;

    private final StringBuilder pendingText = new StringBuilder();
    private boolean streamDone = true;
    private int pendingUtt;
    private int uttSeq;
    private int netErrors;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Robot đang nghe", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        initTts();
    }

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) { ttsReady = false; emitError("Không khởi động được giọng đọc"); return; }
            int r = tts.setLanguage(VI);
            ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
            if (!ttsReady) emitError("Máy chưa có giọng đọc tiếng Việt. Vào Cài đặt › Hệ thống › Ngôn ngữ › Chuyển văn bản thành lời nói để tải.");
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { h.post(() -> setState("speaking")); }
                @Override public void onDone(String id) { h.post(RobotService.this::onUttDone); }
                @Override public void onError(String id) { h.post(RobotService.this::onUttDone); }
                @Override public void onStop(String id, boolean interrupted) { }
            });
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!ensureForeground()) return START_NOT_STICKY;
        String a = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(a)) {
            endSession();
        } else if (ACTION_TAP.equals(a)) {
            tap();
        } else if (ACTION_TEXT.equals(a)) {
            String t = intent.getStringExtra("text");
            if (t != null && !t.trim().isEmpty()) { stopListening(); interruptReply(); send(t.trim()); }
        } else if (ACTION_NEW_CHAT.equals(a)) {
            prefs.resetConversation();
            emit("info", "text", "Đã bắt đầu cuộc trò chuyện mới");
            if ("idle".equals(state)) endSession();
        } else {
            beginListening();
        }
        return START_NOT_STICKY;
    }

    private boolean ensureForeground() {
        if (foreground) return true;
        try {
            startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            foreground = true;
            PowerManager pm = getSystemService(PowerManager.class);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "robot:session");
            wakeLock.acquire(30 * 60 * 1000L);
            return true;
        } catch (Exception e) {
            emitError("Không chạy nền được: mở app Robot rồi thử lại (" + e.getClass().getSimpleName() + ")");
            stopSelf();
            return false;
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pOpen = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, RobotService.class).setAction(ACTION_STOP);
        PendingIntent pStop = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE);
        String name = prefs.robot().optString("name", "Robot");
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(name + " đang trò chuyện")
                .setContentText("Nói \"tạm biệt\" hoặc bấm Dừng để robot nghỉ")
                .setContentIntent(pOpen)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Dừng", pStop).build())
                .build();
    }

    // ---------------- Nghe ----------------

    private void beginListening() {
        interruptReply();
        ending = false;
        heard.setLength(0);
        partial = "";
        lastVoiceAt = System.currentTimeMillis();
        startRecognizer();
    }

    private SpeechRecognizer createRecognizer() {
        ComponentName cn = RecognizerPick.find(this);
        SpeechRecognizer r = null;
        if (cn != null) r = SpeechRecognizer.createSpeechRecognizer(this, cn);
        else if (SpeechRecognizer.isRecognitionAvailable(this)) r = SpeechRecognizer.createSpeechRecognizer(this);
        if (r != null) r.setRecognitionListener(listener);
        return r;
    }

    private void startRecognizer() {
        if (sr == null) sr = createRecognizer();
        if (sr == null) {
            emitError("Không tìm thấy dịch vụ nhận giọng nói của Google. Cập nhật app Google rồi thử lại.");
            setState("idle");
            return;
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        setState("listening");
        listeningActive = true;
        try {
            sr.startListening(i);
        } catch (Exception e) {
            resetRecognizer();
            h.postDelayed(this::startRecognizer, 500);
        }
    }

    private void stopListening() {
        listeningActive = false;
        if (sr != null) try { sr.cancel(); } catch (Exception ignored) { }
    }

    private void resetRecognizer() {
        if (sr != null) try { sr.destroy(); } catch (Exception ignored) { }
        sr = null;
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle b) { }
        @Override public void onBeginningOfSpeech() { lastVoiceAt = System.currentTimeMillis(); }
        @Override public void onRmsChanged(float v) { }
        @Override public void onBufferReceived(byte[] b) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int t, Bundle b) { }

        @Override
        public void onPartialResults(Bundle b) {
            if (!listeningActive) return;
            String p = first(b);
            if (p.isEmpty()) return;
            partial = p;
            lastVoiceAt = System.currentTimeMillis();
            emit("heard", "text", join(heard.toString(), p));
        }

        @Override
        public void onResults(Bundle b) {
            if (!listeningActive) return;
            listeningActive = false;
            netErrors = 0;
            String t = first(b);
            if (t.isEmpty()) t = partial;
            partial = "";
            if (!t.isEmpty()) {
                if (heard.length() > 0) heard.append(' ');
                heard.append(t.trim());
                lastVoiceAt = System.currentTimeMillis();
                emit("heard", "text", heard.toString());
            }
            decide(!t.isEmpty());
        }

        @Override
        public void onError(int code) {
            if (!listeningActive) return;
            listeningActive = false;
            switch (code) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    if (!partial.isEmpty()) {
                        if (heard.length() > 0) heard.append(' ');
                        heard.append(partial);
                        partial = "";
                    }
                    decide(false);
                    break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                case SpeechRecognizer.ERROR_CLIENT:
                    resetRecognizer();
                    h.postDelayed(RobotService.this::startRecognizer, 400);
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    emitError("Chưa cấp quyền micro cho Robot");
                    endSession();
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                case SpeechRecognizer.ERROR_SERVER:
                case SpeechRecognizer.ERROR_SERVER_DISCONNECTED:
                case SpeechRecognizer.ERROR_TOO_MANY_REQUESTS:
                    netErrors++;
                    if (netErrors >= 5) { emitError("Nhận giọng nói bị lỗi mạng nhiều lần, robot tạm nghỉ"); endSession(); break; }
                    resetRecognizer();
                    h.postDelayed(RobotService.this::startRecognizer, 1200);
                    break;
                case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
                case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                    emitError("Máy chưa hỗ trợ nhận giọng tiếng Việt. Mở app Google › Cài đặt › Giọng nói › thêm Tiếng Việt.");
                    endSession();
                    break;
                default:
                    resetRecognizer();
                    h.postDelayed(RobotService.this::startRecognizer, 800);
            }
        }
    };

    /** Sau mỗi đoạn nghe: gửi đi hay nghe tiếp. */
    private void decide(boolean gotSpeech) {
        String all = heard.toString().trim();
        long silent = System.currentTimeMillis() - lastVoiceAt;
        if (forceSend) {
            forceSend = false;
            if (!all.isEmpty()) { send(all); return; }
        }
        if (!all.isEmpty()) {
            if ("pause".equals(prefs.endMode()) || endsWithSignal(all) || isCommand(all)
                    || silent >= prefs.autoSendSec() * 1000L) {
                send(all);
                return;
            }
            startRecognizer();
            return;
        }
        if (silent >= prefs.idleTimeoutSec() * 1000L) {
            sayGoodbyeAndSleep();
            return;
        }
        startRecognizer();
    }

    private boolean endsWithSignal(String all) {
        String t = normalize(all);
        for (String p : prefs.endPhrases()) {
            if (t.equals(p) || t.endsWith(" " + p)) return true;
        }
        return false;
    }

    /** Câu gọi, câu chào nghỉ: gửi ngay, không chờ câu ra hiệu. */
    private boolean isCommand(String all) {
        String t = normalize(all);
        String[] wake = {"hi telly", "hi teli", "hai telly", "hai teli", "hey telly", "hi tely", "hai tê li"};
        for (String w : wake) if (t.contains(w) && t.length() <= w.length() + 12) return true;
        return isSleep(t);
    }

    private boolean isSleep(String normalized) {
        String[] sleep = {"đi ngủ", "tạm biệt", "bye", "bai bai", "nghỉ đi", "nghỉ ngơi đi", "thôi nghỉ", "dừng lại đi"};
        for (String s : sleep) if (normalized.contains(s)) return true;
        return false;
    }

    // ---------------- Gửi Dify & đọc ----------------

    private void send(String text) {
        stopListening();
        heard.setLength(0);
        partial = "";
        emit("user", "text", text);
        if (isSleep(normalize(text))) ending = true;
        query(text);
    }

    private void sayGoodbyeAndSleep() {
        stopListening();
        ending = true;
        query("(Hệ thống: người dùng không nói gì một lúc. Hãy chào tạm biệt thật ngắn theo đúng phong cách của bạn trước khi nghỉ.)");
    }

    private void query(String q) {
        JSONObject robot = prefs.robot();
        String key = robot.optString("key", "").trim();
        if (key.isEmpty()) {
            emitError("Robot \"" + robot.optString("name", "") + "\" chưa có API key. Mở Cài đặt để dán key từ Dify.");
            endSession();
            return;
        }
        setState("thinking");
        pendingText.setLength(0);
        streamDone = false;
        pendingUtt = 0;
        emit("botStart", "name", robot.optString("name", ""));
        final String conv = prefs.conversation(key);
        final DifyClient.Call[] self = new DifyClient.Call[1];
        call = DifyClient.stream(prefs.base(), key, q, conv, prefs.userId(), new DifyClient.Listener() {
            @Override public void onDelta(String text) {
                if (call != self[0]) return;
                pendingText.append(text);
                flushSentences(false);
            }
            @Override public void onEnd(String conversationId) {
                if (call != self[0]) return;
                if (conversationId != null && !conversationId.isEmpty()) prefs.setConversation(key, conversationId);
                streamDone = true;
                flushSentences(true);
                if (pendingUtt == 0) afterReply();
            }
            @Override public void onError(String message) {
                if (call != self[0]) return;
                streamDone = true;
                emitError(message);
                speak("Em chưa kết nối được, nói lại giúp em nhé.");
                if (pendingUtt == 0) afterReply();
            }
        }, h);
        self[0] = call;
    }

    /** Cắt câu hoàn chỉnh ra khỏi phần đang nhận để đọc ngay, không chờ hết câu trả lời. */
    private void flushSentences(boolean fin) {
        String s = pendingText.toString();
        int start = 0;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\n' || c == '.' || c == '!' || c == '?' || c == '…') {
                int j = i;
                while (j + 1 < s.length() && ".!?…".indexOf(s.charAt(j + 1)) >= 0) j++;
                boolean boundary = c == '\n' || (j + 1 < s.length() && Character.isWhitespace(s.charAt(j + 1)));
                if (boundary) {
                    speakChunk(s.substring(start, j + 1));
                    start = j + 1;
                }
                i = j + 1;
            } else {
                i++;
            }
        }
        String rest = s.substring(start);
        pendingText.setLength(0);
        if (fin) {
            if (!rest.trim().isEmpty()) speakChunk(rest);
        } else {
            pendingText.append(rest);
        }
    }

    private void speakChunk(String chunk) {
        if (chunk.trim().isEmpty()) return;
        Emotion.Result r = Emotion.parse(chunk);
        for (String cue : r.cues) {
            emit("cue", "cue", cue);
            vibrateFor(cue);
        }
        if (!r.display.isEmpty()) emit("bot", "text", r.display);
        if (Emotion.hasWords(r.speech)) speak(r.speech);
    }

    private void speak(String text) {
        if (!ttsReady || tts == null) return;
        tts.setSpeechRate(prefs.rate());
        tts.setPitch(prefs.pitch());
        pendingUtt++;
        int res = tts.speak(text, TextToSpeech.QUEUE_ADD, null, "u" + (++uttSeq));
        if (res != TextToSpeech.SUCCESS) pendingUtt--;
    }

    private void onUttDone() {
        if (pendingUtt > 0) pendingUtt--;
        if (pendingUtt == 0 && streamDone) afterReply();
    }

    private void afterReply() {
        if (!foreground) return;
        if (!"speaking".equals(state) && !"thinking".equals(state)) return;
        if (ending) { endSession(); return; }
        heard.setLength(0);
        partial = "";
        lastVoiceAt = System.currentTimeMillis();
        startRecognizer();
    }

    private void interruptReply() {
        if (call != null) { call.cancel(); call = null; }
        if (tts != null) tts.stop();
        pendingText.setLength(0);
        pendingUtt = 0;
        streamDone = true;
    }

    /** Chạm màn hình / bấm nút: ngắt lời, gửi ngay, hoặc bắt đầu nghe. */
    private void tap() {
        switch (state) {
            case "speaking":
            case "thinking":
                beginListening();
                break;
            case "listening":
                if (heard.length() > 0 || !partial.isEmpty()) {
                    forceSend = true;
                    try { sr.stopListening(); } catch (Exception e) { send(join(heard.toString(), partial)); }
                }
                break;
            default:
                beginListening();
        }
    }

    private void endSession() {
        interruptReply();
        stopListening();
        resetRecognizer();
        ending = false;
        setState("idle");
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        interruptReply();
        resetRecognizer();
        if (tts != null) tts.shutdown();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        state = "idle";
        super.onDestroy();
    }

    // ---------------- Tiện ích ----------------

    private void vibrateFor(String cue) {
        if (!prefs.vibrate()) return;
        long[] pattern;
        switch (cue) {
            case ":)^": pattern = new long[]{0, 60, 90, 60}; break;
            case ":(~": pattern = new long[]{0, 35, 60, 35, 60, 35}; break;
            case "~*~": pattern = new long[]{0, 50, 60, 50, 60, 140}; break;
            case "=))": pattern = new long[]{0, 25, 40, 25}; break;
            default: return;
        }
        try {
            VibratorManager vm = getSystemService(VibratorManager.class);
            Vibrator v = vm.getDefaultVibrator();
            v.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } catch (Exception ignored) { }
    }

    private void setState(String s) {
        state = s;
        emit("state", "state", s);
    }

    private void emitError(String msg) {
        lastError = msg;
        emit("error", "text", msg);
    }

    private void emit(String type, String k, String v) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put(k, v);
            Ui u = ui;
            if (u != null) u.onRobotEvent(o);
        } catch (JSONException ignored) { }
    }

    private static String first(Bundle b) {
        if (b == null) return "";
        ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return (l == null || l.isEmpty() || l.get(0) == null) ? "" : l.get(0).trim();
    }

    private static String join(String a, String b) {
        if (a == null || a.isEmpty()) return b == null ? "" : b;
        if (b == null || b.isEmpty()) return a;
        return a + " " + b;
    }

    static String normalize(String s) {
        String t = s.toLowerCase(VI).replaceAll("[\\p{Punct}…“”‘’]+", " ").replaceAll("\\s+", " ").trim();
        return t;
    }

}
