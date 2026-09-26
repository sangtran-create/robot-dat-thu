package vn.datthu.robot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.speech.tts.Voice;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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
import java.util.List;
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
    public static final String ACTION_IMAGE = "vn.datthu.robot.IMAGE";

    public interface Ui { void onRobotEvent(JSONObject ev); }

    public static volatile Ui ui;
    public static volatile String state = "idle";
    public static volatile String lastError = "";
    /** Việc chờ chuyển sang app AI khi màn hình đang tắt: {app, task}. */
    public static volatile String[] pendingDelegate = null;
    /** Thao tác app chờ thực hiện khi màn hình đang tắt (JSON). */
    public static volatile JSONObject pendingAction = null;
    private Actions.Action draft;
    private boolean awaitingConfirm, awaitingContent;

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
        } else if (ACTION_IMAGE.equals(a)) {
            stopListening();
            interruptReply();
            handleMedia(intent.getStringExtra("uri"), intent.getStringExtra("kind"), intent.getStringExtra("text"));
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
        if (cameraCommand(t) != null && t.length() <= 60) return true;
        if (delegateCommand(all) != null && (t.startsWith("mở ") && t.length() <= 20)) return true;
        if (awaitingConfirm) return true;
        if (t.startsWith("mở ") && t.length() <= 25 && Actions.parse(all, null) != null) return true;
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
        if (awaitingContent && draft != null) { awaitingContent = false; draft.content = Actions.cleanSentence(text); askConfirm(); return; }
        if (awaitingConfirm && draft != null) { handleConfirm(text); return; }
        String[] del = delegateCommand(text);
        if (del != null) {
            String name = appName(del[0]);
            setState("thinking");
            if (MainActivity.visible && ui != null) {
                speak("Dạ, em chuyển việc này sang " + name + ".");
                try {
                    JSONObject o = new JSONObject();
                    o.put("type", "delegate");
                    o.put("app", del[0]);
                    o.put("task", del[1]);
                    ui.onRobotEvent(o);
                } catch (JSONException ignored) { }
            } else {
                pendingDelegate = del;
                speak("Anh mở màn hình lên, em chuyển việc sang " + name + " ngay.");
            }
            ending = true;
            streamDone = true;
            if (pendingUtt == 0) afterReply();
            return;
        }
        if (ScreenContext.fresh() && screenCommand(normalize(text))) { askAboutScreen(text); return; }
        Actions.Action act = Actions.parse(text, prefs.contacts());
        if (act != null) { handleAction(act); return; }
        String cam = cameraCommand(normalize(text));
        if (cam != null) {
            if (MainActivity.visible && ui != null) {
                setState("camera");
                try {
                    JSONObject o = new JSONObject();
                    o.put("type", "camera");
                    o.put("mode", cam);
                    o.put("text", text);
                    ui.onRobotEvent(o);
                } catch (JSONException ignored) { }
            } else {
                setState("thinking");
                speak("Anh mở màn hình điện thoại lên giúp em để em dùng camera nhé.");
                streamDone = true;
                if (pendingUtt == 0) afterReply();
            }
            return;
        }
        if (isSleep(normalize(text))) ending = true;
        query(text);
    }

    private void sayGoodbyeAndSleep() {
        stopListening();
        ending = true;
        query("(Hệ thống: người dùng không nói gì một lúc. Hãy chào tạm biệt thật ngắn theo đúng phong cách của bạn trước khi nghỉ.)");
    }

    private void query(String q) { query(q, null); }

    private void query(String q, List<byte[]> images) {
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
        call = DifyClient.stream(prefs.base(), key, q, conv, prefs.userId(), images, new DifyClient.Listener() {
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

    private String appliedVoice = null;

    private void applyVoice() {
        String name = prefs.voice();
        if (name.equals(appliedVoice)) return;
        appliedVoice = name;
        if (!name.isEmpty()) {
            try {
                for (Voice v : tts.getVoices()) {
                    if (v.getName().equals(name)) { tts.setVoice(v); return; }
                }
            } catch (Exception ignored) { }
        }
        tts.setLanguage(VI);
    }

    private void speak(String text) {
        if (!ttsReady || tts == null) return;
        applyVoice();
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
        ScreenContext.clear();
        draft = null;
        awaitingConfirm = false;
        awaitingContent = false;
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

    // ---------------- Điều khiển app: Zalo, Messenger, Gmail, Google ----------------

    private void say(String msg) {
        setState("thinking");
        speak(msg);
        streamDone = true;
        if (pendingUtt == 0) afterReply();
    }

    private void handleAction(Actions.Action a) {
        String label = Actions.appLabel(a.app);
        if (a.needsConfirm()) {
            draft = a;
            if (a.content.isEmpty()) {
                awaitingContent = true;
                say("Anh muốn nhắn gì" + (a.who.isEmpty() ? "" : " cho " + a.who) + " qua " + label + "?");
            } else {
                askConfirm();
            }
            return;
        }
        String msg;
        switch (a.type) {
            case "calendar": msg = "Dạ, em mở " + label + " để anh xem lại rồi lưu lịch."; break;
            case "note": msg = "Dạ, em ghi vào " + label + "."; break;
            default: msg = "Dạ, em mở " + label + ".";
        }
        executeAction(a, msg);
    }

    private void askConfirm() {
        awaitingConfirm = true;
        Actions.Action a = draft;
        String label = Actions.appLabel(a.app);
        String to = a.who.isEmpty() ? "" : " gửi " + a.who;
        String msg = "email".equals(a.type)
                ? "Em soạn thư" + to + (a.subject.isEmpty() ? "" : ", tiêu đề " + a.subject) + ". Nội dung: " + a.content + " Gửi không ạ?"
                : "Em soạn tin " + label + to + ": " + a.content + " Gửi không ạ?";
        emit("info", "text", "Bản nháp " + label + (a.who.isEmpty() ? "" : " → " + a.who) + ": " + a.content
                + "  (nói \"gửi\", \"sửa lại…\" hoặc \"hủy\")");
        say(msg);
    }

    private void handleConfirm(String text) {
        String intent = Actions.confirmIntent(text);
        if ("yes".equals(intent)) {
            awaitingConfirm = false;
            Actions.Action a = draft;
            draft = null;
            String label = Actions.appLabel(a.app);
            String tail = a.contact != null && ("zalo".equals(a.app) ? !a.contact.zalo.isEmpty() : "messenger".equals(a.app) && !a.contact.messenger.isEmpty())
                    ? " Em mở khung chat, anh nhấn giữ rồi dán và bấm gửi nhé."
                    : " Anh chọn người nhận rồi bấm gửi nhé.";
            if ("email".equals(a.type)) tail = " Anh kiểm tra rồi bấm gửi nhé.";
            executeAction(a, "Dạ, em mở " + label + "." + tail);
        } else if ("no".equals(intent)) {
            awaitingConfirm = false;
            draft = null;
            say("Dạ, em hủy tin này.");
        } else if ("edit".equals(intent)) {
            String n = text.replaceAll("(?i)^.*?(sửa lại thành|sửa thành|đổi thành|đổi lại thành|sửa lại|đổi lại)\\s*[:,]?\\s*", "").trim();
            if (n.isEmpty() || n.equalsIgnoreCase(text.trim())) {
                awaitingConfirm = false;
                awaitingContent = true;
                say("Anh đọc lại nội dung mới giúp em.");
            } else {
                draft.content = Actions.cleanSentence(n);
                askConfirm();
            }
        } else {
            say("Anh nói gửi để gửi, sửa lại để sửa, hoặc hủy nhé.");
        }
    }

    private void executeAction(Actions.Action a, String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("type", "action");
            o.put("kind", a.type);
            o.put("app", a.app);
            o.put("content", a.content);
            o.put("subject", a.subject);
            o.put("startMs", a.startMs);
            o.put("who", a.who);
            if (a.contact != null) {
                o.put("zalo", a.contact.zalo);
                o.put("messenger", a.contact.messenger);
                o.put("email", a.contact.email);
            }
        } catch (JSONException ignored) { }
        if (MainActivity.visible && ui != null) {
            ui.onRobotEvent(o);
        } else {
            pendingAction = o;
            msg = "Anh mở màn hình lên, em mở " + Actions.appLabel(a.app) + " ngay.";
        }
        ending = true;
        say(msg);
    }

    // ---------------- Giao việc cho app AI ----------------

    static String appName(String app) {
        return "chatgpt".equals(app) ? "ChatGPT" : "gemini".equals(app) ? "Gemini" : "Claude";
    }

    /** Nhận câu kiểu "giao cho Claude soạn…" → {app, việc}. Không phải lệnh giao việc → null. */
    static String[] delegateCommand(String text) {
        String low = text.toLowerCase(VI);
        String[] verbs = {"giao cho", "chuyển cho", "chuyển sang", "gửi cho", "gửi sang", "nhờ", "hỏi", "mở"};
        String[][] apps = {
                {"claude", "claude", "cờ lốt", "clót", "cờ lau", "clau"},
                {"chatgpt", "chatgpt", "chat gpt", "chát gpt", "chát gi pi ti", "chat gi pi ti", "chát gờ pê tê"},
                {"gemini", "gemini", "gờ mi ni", "giê mi ni", "dem mi ni", "gem mi ni"}};
        for (String v : verbs) {
            for (String[] a : apps) {
                for (int k = 1; k < a.length; k++) {
                    String key = v + " " + a[k];
                    int i = low.indexOf(key);
                    if (i < 0) continue;
                    // Chỉ nhận khi lệnh nằm ở đầu câu (tránh câu kể chuyện có nhắc tên app)
                    if (i > 12) continue;
                    String task = text.substring(Math.min(text.length(), i + key.length())).trim();
                    task = task.replaceAll("^[,:.\\s]+", "");
                    task = task.replaceAll("(?i)[,.\\s]*(xong rồi|vậy đó em|vậy đó|hết ý|thế nhé)[.!\\s]*$", "").trim();
                    return new String[]{a[0], task};
                }
            }
        }
        return null;
    }

    // ---------------- Xem màn hình (khi gọi bằng nút nguồn) ----------------

    static boolean screenCommand(String t) {
        String[] w = {"xem màn hình", "nhìn màn hình", "màn hình này", "trên màn hình", "gợi ý trả lời", "trả lời giúp",
                "trả lời sao", "trả lời thế nào", "tóm tắt", "đọc giúp", "đọc hộ", "góp ý", "cái này là gì", "giải thích",
                "em thấy gì", "xem cái này", "nhìn cái này", "em xem", "xem giúp", "nhìn giúp", "xem hộ", "dịch giúp", "dịch cái này"};
        for (String x : w) if (t.contains(x)) return true;
        return false;
    }

    private void askAboutScreen(String ask) {
        String app = ScreenContext.appName(ScreenContext.pkg);
        String txt = ScreenContext.text;
        if (txt.length() > 3500) txt = txt.substring(0, 3500);
        StringBuilder q = new StringBuilder("(Hệ thống: kèm màn hình điện thoại lúc người dùng gọi robot");
        if (!app.isEmpty()) q.append(", đang mở app ").append(app);
        q.append(".");
        if (!ScreenContext.shotPath.isEmpty()) q.append(" Có ảnh chụp màn hình đính kèm.");
        if (!txt.isEmpty()) q.append(" Chữ đọc được trên màn hình:\n").append(txt).append("\n");
        q.append("Trả lời yêu cầu dựa trên màn hình này, ngắn gọn, đi thẳng vào việc. Nếu được nhờ gợi ý trả lời tin nhắn hay email, "
                + "đưa một đến hai câu trả lời mẫu tự nhiên theo giọng bình thường của người dùng (không dùng giọng quân sư trong câu mẫu). "
                + "Không đọc lại thông tin nhạy cảm như số tài khoản, mật khẩu, mã OTP.) ");
        q.append(ask);
        List<byte[]> imgs = null;
        if (!ScreenContext.shotPath.isEmpty()) {
            try {
                java.io.File f = new java.io.File(ScreenContext.shotPath);
                byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
                imgs = new ArrayList<>();
                imgs.add(b);
            } catch (Exception ignored) { }
        }
        emit("info", "text", "Robot đang xem màn hình" + (app.isEmpty() ? "" : " " + app) + "…");
        query(q.toString(), imgs);
    }

    // ---------------- Camera ----------------

    /** "photo" | "video" | null */
    static String cameraCommand(String t) {
        String[] video = {"quay video", "quay phim", "quay clip", "quay lại cảnh", "quay giúp"};
        for (String w : video) if (t.contains(w)) return "video";
        String[] photo = {"chụp ảnh", "chụp hình", "chụp lại", "nhìn này", "nhìn xem", "em nhìn", "nhìn cái này",
                "xem cái này", "xem giúp", "em xem", "nhìn giúp"};
        for (String w : photo) if (t.contains(w)) return "photo";
        return null;
    }

    private void handleMedia(String uriStr, String kind, String text) {
        if (uriStr == null) { afterCamera(); return; }
        setState("thinking");
        final boolean video = "video".equals(kind);
        final String said = text == null ? "" : text.trim();
        new Thread(() -> {
            List<byte[]> imgs = new ArrayList<>();
            try {
                Uri uri = Uri.parse(uriStr);
                if (video) imgs.addAll(videoFrames(uri));
                else { byte[] j = photoJpeg(uri); if (j != null) imgs.add(j); }
            } catch (Exception ignored) { }
            h.post(() -> {
                if (imgs.isEmpty()) {
                    emitError("Không đọc được " + (video ? "video" : "ảnh") + " vừa chụp");
                    afterCamera();
                    return;
                }
                String ask = said.isEmpty() ? "Hãy xem và nói điều em thấy, theo đúng phong cách của em." : said;
                String prefix = video
                        ? "(Kèm " + imgs.size() + " khung hình trích từ video người dùng vừa quay bằng camera điện thoại, xếp theo thứ tự thời gian. Video đã được lưu vào thư viện ảnh.) "
                        : "(Kèm ảnh người dùng vừa chụp bằng camera điện thoại. Ảnh đã được lưu vào thư viện ảnh.) ";
                query(prefix + ask, imgs);
            });
        }, "media").start();
    }

    private void afterCamera() {
        heard.setLength(0);
        partial = "";
        lastVoiceAt = System.currentTimeMillis();
        startRecognizer();
    }

    private byte[] photoJpeg(Uri uri) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
        int longest = Math.max(o.outWidth, o.outHeight);
        int sample = 1;
        while (longest / (sample * 2) >= 1280) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap bm;
        try (InputStream in = getContentResolver().openInputStream(uri)) { bm = BitmapFactory.decodeStream(in, null, o2); }
        if (bm == null) return null;
        return jpeg(scale(bm, 1280));
    }

    private List<byte[]> videoFrames(Uri uri) throws Exception {
        List<byte[]> out = new ArrayList<>();
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, uri);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durMs = d == null ? 0 : Long.parseLong(d);
            double[] at = durMs < 4000 ? new double[]{0.5} : new double[]{0.15, 0.5, 0.85};
            for (double f : at) {
                Bitmap bm = r.getScaledFrameAtTime((long) (durMs * f * 1000), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1024, 1024);
                if (bm != null) out.add(jpeg(bm));
            }
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
        return out;
    }

    private static Bitmap scale(Bitmap bm, int max) {
        int w = bm.getWidth(), hgt = bm.getHeight();
        int longest = Math.max(w, hgt);
        if (longest <= max) return bm;
        float k = (float) max / longest;
        return Bitmap.createScaledBitmap(bm, Math.round(w * k), Math.round(hgt * k), true);
    }

    private static byte[] jpeg(Bitmap bm) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.JPEG, 85, bo);
        return bo.toByteArray();
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
