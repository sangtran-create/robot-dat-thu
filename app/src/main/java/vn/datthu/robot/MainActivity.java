package vn.datthu.robot;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
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

    private WebView web;
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
        handle(getIntent(), true);
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
        RobotService.ui = this;
        if (pageReady) push("state", "state", RobotService.state);
    }

    @Override
    protected void onDestroy() {
        if (RobotService.ui == this) RobotService.ui = null;
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
        @JavascriptInterface public String version() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Exception e) { return "?"; }
        }
    }
}
