package vn.datthu.robot;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.speech.tts.Voice;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

/** Màn hình khuôn mặt robot (giao diện web trong app) + cầu nối tới RobotService. */
public class MainActivity extends Activity implements RobotService.Ui {

    public static volatile boolean visible;
    private static final int REQ_PHOTO = 21, REQ_VIDEO = 22;
    private WebView web;
    private TextToSpeech previewTts;
    private String voicesJson = "[]";
    private Uri pendingUri;
    private String pendingKind, pendingAsk;
    private Prefs prefs;
    private boolean pendingStart;
    private boolean pageReady;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        prefs = new Prefs(this);

        FrameLayout root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(0xFF0B1114);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0B1114);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        web.addJavascriptInterface(new Bridge(), "Robot");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                pageReady = true;
                push("state", "state", RobotService.state);
            }
        });
        web.loadUrl("file:///android_asset/index.html");
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        RobotService.ui = this;
        initVoices();
        handle(getIntent(), true);
    }

    // ---------- Giọng đọc ----------

    private void initVoices() {
        previewTts = new TextToSpeech(this, st -> {
            if (st != TextToSpeech.SUCCESS) return;
            try {
                List<Voice> list = new ArrayList<>();
                for (Voice v : previewTts.getVoices()) {
                    if (v.getLocale() != null && "vi".equals(v.getLocale().getLanguage())) list.add(v);
                }
                list.sort((a, b) -> a.getName().compareTo(b.getName()));
                JSONArray a = new JSONArray();
                int n = 0;
                for (Voice v : list) {
                    n++;
                    JSONObject o = new JSONObject();
                    o.put("name", v.getName());
                    String code = v.getName().replace("vi-vn-x-", "").replace("vi-VN-", "");
                    o.put("label", "Giọng " + n + (v.isNetworkConnectionRequired() ? " · cần mạng" : " · offline") + " (" + code + ")");
                    a.put(o);
                }
                voicesJson = a.toString();
                runOnUiThread(() -> { if (pageReady) web.evaluateJavascript("window.onVoices && onVoices(" + voicesJson + ")", null); });
            } catch (Exception ignored) { }
        });
    }

    private void previewVoice(String name, float rate, float pitch) {
        if (previewTts == null) return;
        try {
            boolean set = false;
            for (Voice v : previewTts.getVoices()) {
                if (v.getName().equals(name)) { previewTts.setVoice(v); set = true; break; }
            }
            if (!set) previewTts.setLanguage(new Locale("vi", "VN"));
            previewTts.setSpeechRate(rate);
            previewTts.setPitch(pitch);
            previewTts.speak("Dạ, em đây. Giọng này nghe có vừa tai không ạ?", TextToSpeech.QUEUE_FLUSH, null, "preview");
        } catch (Exception ignored) { }
    }

    // ---------- Giao việc cho app AI trả phí ----------

    private void delegate(String app, String task) {
        String pkg, web, name;
        String t = task == null ? "" : task.trim();
        String enc = Uri.encode(t);
        switch (app == null ? "" : app) {
            case "chatgpt":
                pkg = "com.openai.chatgpt"; name = "ChatGPT";
                web = t.isEmpty() ? "https://chatgpt.com/" : "https://chatgpt.com/?q=" + enc;
                break;
            case "gemini":
                pkg = "com.google.android.apps.bard"; name = "Gemini";
                web = "https://gemini.google.com/app";
                break;
            default:
                pkg = "com.anthropic.claude"; name = "Claude";
                web = t.isEmpty() ? "https://claude.ai/new" : "https://claude.ai/new?q=" + enc;
        }
        if (!t.isEmpty()) {
            try {
                ClipboardManager cm = getSystemService(ClipboardManager.class);
                cm.setPrimaryClip(ClipData.newPlainText("Việc giao cho " + name, t));
            } catch (Exception ignored) { }
        }
        boolean opened = false;
        if (!t.isEmpty()) {
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, t).setPackage(pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(send); opened = true; } catch (ActivityNotFoundException ignored) { }
        }
        if (!opened) {
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                try { startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); opened = true; } catch (Exception ignored) { }
            }
        }
        if (!opened) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(web))); opened = true; } catch (Exception ignored) { }
        }
        push("info", "text", opened
                ? "Đã chuyển sang " + name + (t.isEmpty() ? "" : ". Nội dung đã được copy: nếu app chưa tự điền, nhấn giữ ô nhập → Dán.")
                : "Không mở được " + name + ". Hãy cài app " + name + " từ Google Play.");
    }

    // ---------- Điều khiển app: Zalo, Messenger, Gmail, Google ----------

    static String pkgFor(String app) {
        switch (app) {
            case "zalo": return "com.zing.zalo";
            case "messenger": return "com.facebook.orca";
            case "gmail": return "com.google.android.gm";
            case "calendar": return "com.google.android.calendar";
            case "keep": return "com.google.android.keep";
            case "docs": return "com.google.android.apps.docs.editors.docs";
            case "sheets": return "com.google.android.apps.docs.editors.sheets";
            case "slides": return "com.google.android.apps.docs.editors.slides";
            case "drive": return "com.google.android.apps.docs";
            case "meet": return "com.google.android.apps.tachyon";
            case "tasks": return "com.google.android.apps.tasks";
            default: return "";
        }
    }

    private boolean tryStart(Intent i) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true; }
        catch (Exception e) { return false; }
    }

    private boolean launch(String pkg) {
        if (pkg.isEmpty()) return false;
        Intent l = getPackageManager().getLaunchIntentForPackage(pkg);
        return l != null && tryStart(l);
    }

    private boolean shareText(String text, String pkg) {
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        if (!pkg.isEmpty() && tryStart(new Intent(send).setPackage(pkg))) return true;
        if (launch(pkg)) return true;
        return tryStart(Intent.createChooser(send, "Gửi bằng"));
    }

    private void copy(String label, String text) {
        if (text == null || text.isEmpty()) return;
        try { getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(label, text)); }
        catch (Exception ignored) { }
    }

    private void perform(JSONObject a) {
        String kind = a.optString("kind"), app = a.optString("app"), content = a.optString("content");
        String label = Actions.appLabel(app);
        copy(label, content);
        boolean ok;
        switch (kind) {
            case "message": {
                String zalo = a.optString("zalo").replaceAll("[^0-9+]", "");
                String msgr = a.optString("messenger").trim();
                if ("zalo".equals(app) && !zalo.isEmpty()) ok = tryStart(new Intent(Intent.ACTION_VIEW, Uri.parse("https://zalo.me/" + zalo)));
                else if ("messenger".equals(app) && !msgr.isEmpty()) ok = tryStart(new Intent(Intent.ACTION_VIEW, Uri.parse("https://m.me/" + Uri.encode(msgr))));
                else ok = shareText(content, pkgFor(app));
                break;
            }
            case "email": {
                Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"));
                String to = a.optString("email").trim();
                if (!to.isEmpty()) i.putExtra(Intent.EXTRA_EMAIL, new String[]{to});
                if (!a.optString("subject").isEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, a.optString("subject"));
                i.putExtra(Intent.EXTRA_TEXT, content);
                ok = tryStart(new Intent(i).setPackage(pkgFor("gmail"))) || tryStart(i);
                break;
            }
            case "calendar": {
                Intent i = new Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.Events.TITLE, content);
                long start = a.optLong("startMs", 0);
                if (start > 0) {
                    i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start);
                    i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 3600_000L);
                }
                ok = tryStart(new Intent(i).setPackage(pkgFor("calendar"))) || tryStart(i);
                break;
            }
            case "note":
                ok = shareText(content, pkgFor("keep"));
                break;
            default: // open
                if ("meet_new".equals(app)) ok = tryStart(new Intent(Intent.ACTION_VIEW, Uri.parse("https://meet.google.com/new")));
                else ok = launch(pkgFor(app));
        }
        push("info", "text", ok
                ? "Đã mở " + label + (content.isEmpty() ? "" : ". Nội dung đã copy sẵn: nếu chưa tự điền, nhấn giữ ô nhập → Dán.")
                : "Không mở được " + label + ". Kiểm tra app đã cài chưa.");
    }

    // ---------- Camera ----------

    private void openCamera(String kind, String ask) {
        boolean video = "video".equals(kind);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, "Robot_" + stamp + (video ? ".mp4" : ".jpg"));
        cv.put(MediaStore.MediaColumns.MIME_TYPE, video ? "video/mp4" : "image/jpeg");
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, video ? "Movies/RobotDatThu" : "Pictures/RobotDatThu");
        Uri collection = video
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        try {
            pendingUri = getContentResolver().insert(collection, cv);
        } catch (Exception e) {
            pendingUri = null;
        }
        pendingKind = kind;
        pendingAsk = ask;
        Intent i = new Intent(video ? MediaStore.ACTION_VIDEO_CAPTURE : MediaStore.ACTION_IMAGE_CAPTURE);
        if (pendingUri != null) i.putExtra(MediaStore.EXTRA_OUTPUT, pendingUri);
        if (video) i.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1);
        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(i, video ? REQ_VIDEO : REQ_PHOTO);
        } catch (ActivityNotFoundException e) {
            deletePending();
            push("error", "text", "Không mở được camera của máy");
            resumeAfterCamera();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PHOTO && req != REQ_VIDEO) return;
        Uri uri = pendingUri;
        if (res == RESULT_OK && data != null && data.getData() != null && (uri == null || size(uri) <= 0)) {
            deletePending();
            uri = data.getData();
        }
        if (res != RESULT_OK || uri == null || size(uri) <= 0) {
            deletePending();
            push("info", "text", "Đã hủy camera");
            resumeAfterCamera();
            return;
        }
        pendingUri = null;
        push("info", "text", ("video".equals(pendingKind) ? "Đã lưu video" : "Đã lưu ảnh") + " vào thư viện (thư mục RobotDatThu). Robot đang xem…");
        Intent s = new Intent(this, RobotService.class).setAction(RobotService.ACTION_IMAGE);
        s.putExtra("uri", uri.toString());
        s.putExtra("kind", pendingKind);
        s.putExtra("text", pendingAsk == null ? "" : pendingAsk);
        s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startForegroundService(s); } catch (Exception e) { push("error", "text", "Không gửi được ảnh cho robot"); }
    }

    private void resumeAfterCamera() {
        if ("camera".equals(RobotService.state)) startRobot(RobotService.ACTION_START, null);
    }

    private void deletePending() {
        if (pendingUri != null) {
            try { getContentResolver().delete(pendingUri, null, null); } catch (Exception ignored) { }
            pendingUri = null;
        }
    }

    private long size(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) { }
        return -1;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent, false);
    }

    private void handle(Intent i, boolean fresh) {
        if (i == null) return;
        boolean fromAssist = i.getBooleanExtra("autostart", false);
        if (i.getBooleanExtra("fromAssist", false) && ScreenContext.fresh()) {
            String app = ScreenContext.appName(ScreenContext.pkg);
            web.postDelayed(() -> push("info", "text", "Em đã thấy màn hình" + (app.isEmpty() ? "" : " " + app)
                    + ". Nói \"gợi ý trả lời\", \"tóm tắt giúp\", \"cái này là gì\"… để em góp ý."), 900);
        }
        boolean fromLauncher = Intent.ACTION_MAIN.equals(i.getAction()) && fresh;
        if (fromAssist || (fromLauncher && prefs.autostart() && hasKey())) {
            startRobot(RobotService.ACTION_START, null);
        }
    }

    private boolean hasKey() {
        return !prefs.robot().optString("key", "").trim().isEmpty();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        RobotService.ui = this;
        JSONObject pa = RobotService.pendingAction;
        if (pa != null) {
            RobotService.pendingAction = null;
            web.postDelayed(() -> perform(pa), 800);
        }
        String[] pd = RobotService.pendingDelegate;
        if (pd != null) {
            RobotService.pendingDelegate = null;
            web.postDelayed(() -> delegate(pd[0], pd[1]), 800);
        }
        if (pageReady) push("state", "state", RobotService.state);
    }

    @Override
    protected void onPause() {
        visible = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (RobotService.ui == this) RobotService.ui = null;
        if (previewTts != null) previewTts.shutdown();
        super.onDestroy();
    }

    private String pendingAction;
    private String pendingText;

    private void startRobot(String action, String text) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = true;
            pendingAction = action;
            pendingText = text;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, 7);
            return;
        }
        Intent s = new Intent(this, RobotService.class).setAction(action);
        if (text != null) s.putExtra("text", text);
        try {
            startForegroundService(s);
        } catch (Exception e) {
            push("error", "text", "Không khởi động được robot: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 7 && pendingStart) {
            pendingStart = false;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startRobot(pendingAction, pendingText);
            } else {
                push("error", "text", "Robot cần quyền micro để nghe. Vào Cài đặt máy › Ứng dụng › Robot Đất Thủ › Quyền.");
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && prefs.volumeKey()) {
            if (event.getRepeatCount() == 0) startRobot(RobotService.ACTION_TAP, null);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------- Sự kiện từ RobotService ----------

    @Override
    public void onRobotEvent(JSONObject ev) {
        runOnUiThread(() -> {
            if ("action".equals(ev.optString("type"))) {
                web.postDelayed(() -> perform(ev), 1800);  // chờ robot nói xong
            }
            if ("delegate".equals(ev.optString("type"))) {
                final String app = ev.optString("app"), task = ev.optString("task");
                web.postDelayed(() -> delegate(app, task), 1600);  // chờ robot nói xong câu xác nhận
            }
            if ("camera".equals(ev.optString("type"))) {
                openCamera(ev.optString("mode"), ev.optString("text"));
            }
            if ("state".equals(ev.optString("type"))) {
                String s = ev.optString("state");
                if ("idle".equals(s)) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            if (web != null && pageReady) web.evaluateJavascript("window.onRobot && onRobot(" + ev + ")", null);
        });
    }

    private void push(String type, String k, String v) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put(k, v);
            onRobotEvent(o);
        } catch (Exception ignored) { }
    }

    // ---------- Cầu nối cho giao diện ----------

    class Bridge {
        @JavascriptInterface public void tap() { runOnUiThread(() -> startRobot(RobotService.ACTION_TAP, null)); }
        @JavascriptInterface public void start() { runOnUiThread(() -> startRobot(RobotService.ACTION_START, null)); }
        @JavascriptInterface public void stop() {
            runOnUiThread(() -> {
                if (!"idle".equals(RobotService.state)) startRobot(RobotService.ACTION_STOP, null);
            });
        }
        @JavascriptInterface public void sendText(String t) { runOnUiThread(() -> startRobot(RobotService.ACTION_TEXT, t)); }
        @JavascriptInterface public void newChat() {
            runOnUiThread(() -> {
                prefs.resetConversation();
                push("info", "text", "Đã bắt đầu cuộc trò chuyện mới");
            });
        }
        @JavascriptInterface public String getSettings() { return prefs.toJson().toString(); }
        @JavascriptInterface public String getState() { return RobotService.state; }
        @JavascriptInterface public void saveSettings(String json) {
            try { prefs.fromJson(new JSONObject(json)); } catch (Exception ignored) { }
        }
        @JavascriptInterface public void selectRobot(int i) {
            prefs.setCurrent(i);
            runOnUiThread(() -> {
                if (!"idle".equals(RobotService.state)) startRobot(RobotService.ACTION_STOP, null);
            });
        }
        @JavascriptInterface public void openAssistantSettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS));
                } catch (ActivityNotFoundException e) {
                    try { startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)); }
                    catch (ActivityNotFoundException ignored) { }
                }
            });
        }
        @JavascriptInterface public void openTtsSettings() {
            runOnUiThread(() -> {
                try { startActivity(new Intent("com.android.settings.TTS_SETTINGS")); }
                catch (ActivityNotFoundException e) {
                    try { startActivity(new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)); }
                    catch (ActivityNotFoundException ignored) { }
                }
            });
        }
        @JavascriptInterface public void delegate(String app, String task) {
            runOnUiThread(() -> MainActivity.this.delegate(app, task));
        }
        @JavascriptInterface public void shareTo(String app, String text) {
            runOnUiThread(() -> {
                try {
                    JSONObject o = new JSONObject();
                    o.put("kind", "gmail".equals(app) ? "email" : "keep".equals(app) ? "note" : "message");
                    o.put("app", app);
                    o.put("content", text == null ? "" : text);
                    perform(o);
                } catch (Exception ignored) { }
            });
        }
        @JavascriptInterface public void camera(String kind) { runOnUiThread(() -> openCamera(kind, "")); }
        @JavascriptInterface public String getVoices() { return voicesJson; }
        @JavascriptInterface public void previewVoice(String name, String rate, String pitch) {
            runOnUiThread(() -> {
                float r = 1f, p = 1f;
                try { r = Float.parseFloat(rate); p = Float.parseFloat(pitch); } catch (Exception ignored) { }
                MainActivity.this.previewVoice(name, r, p);
            });
        }
        @JavascriptInterface public String version() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Exception e) { return "?"; }
        }
    }
}
