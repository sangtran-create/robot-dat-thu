package vn.datthu.robot;

import android.app.assist.AssistStructure;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

import java.io.File;
import java.io.FileOutputStream;

public class AssistSessionService extends VoiceInteractionSessionService {
    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        return new Session(this);
    }

    /**
     * Giữ nút nguồn: Android đưa cho trợ lý chữ trên màn hình và ảnh chụp màn hình
     * (nếu anh bật trong cài đặt Trợ lý). Robot lưu lại rồi mở màn hình robot.
     */
    static class Session extends VoiceInteractionSession {
        private final Handler h = new Handler(Looper.getMainLooper());
        private boolean launched, wantAssist, wantShot, gotAssist, gotShot, ownApp;

        Session(Context c) { super(c); }

        @Override
        public void onShow(Bundle args, int showFlags) {
            super.onShow(args, showFlags);
            launched = false;
            boolean enabled = new Prefs(getContext()).screenAssist();
            wantAssist = enabled && (showFlags & SHOW_WITH_ASSIST) != 0;
            wantShot = enabled && (showFlags & SHOW_WITH_SCREENSHOT) != 0;
            if (!gotAssist && !gotShot) ScreenContext.clear();
            ScreenContext.timeMs = System.currentTimeMillis();
            if (!wantAssist && !wantShot) launch();
            else { maybeLaunch(); h.postDelayed(this::launch, 1500); }
        }

        @Override
        public void onHandleAssist(AssistState state) {
            try {
                AssistStructure st = state.getAssistStructure();
                if (st != null && new Prefs(getContext()).screenAssist()) {
                    ComponentName cn = st.getActivityComponent();
                    String pkg = cn == null ? "" : cn.getPackageName();
                    ownApp = getContext().getPackageName().equals(pkg);
                    if (!ownApp) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < st.getWindowNodeCount(); i++) {
                            collect(st.getWindowNodeAt(i).getRootViewNode(), sb, 0);
                        }
                        ScreenContext.pkg = pkg;
                        String t = sb.toString().trim();
                        ScreenContext.text = t.length() > 4000 ? t.substring(0, 4000) : t;
                        ScreenContext.timeMs = System.currentTimeMillis();
                    }
                }
            } catch (Exception ignored) { }
            gotAssist = true;
            maybeLaunch();
        }

        private static void collect(AssistStructure.ViewNode n, StringBuilder sb, int depth) {
            if (n == null || depth > 60 || sb.length() > 6000) return;
            if (n.getVisibility() == android.view.View.VISIBLE) {
                CharSequence t = n.getText();
                if (t != null && t.length() > 0) sb.append(t).append('\n');
                else if (n.getContentDescription() != null && n.getContentDescription().length() > 0)
                    sb.append(n.getContentDescription()).append('\n');
            }
            for (int i = 0; i < n.getChildCount(); i++) collect(n.getChildAt(i), sb, depth + 1);
        }

        @Override
        public void onHandleScreenshot(Bitmap shot) {
            try {
                if (shot != null && new Prefs(getContext()).screenAssist()
                        && !ownApp) {
                    int w = shot.getWidth(), hh = shot.getHeight();
                    float k = Math.min(1f, 1280f / Math.max(w, hh));
                    Bitmap b = k < 1f ? Bitmap.createScaledBitmap(shot, Math.round(w * k), Math.round(hh * k), true) : shot;
                    File f = new File(getContext().getCacheDir(), "screen.jpg");
                    try (FileOutputStream out = new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.JPEG, 85, out); }
                    ScreenContext.shotPath = f.getAbsolutePath();
                    ScreenContext.timeMs = System.currentTimeMillis();
                }
            } catch (Exception ignored) { }
            gotShot = true;
            maybeLaunch();
        }

        private void maybeLaunch() {
            if ((!wantAssist || gotAssist) && (!wantShot || gotShot)) h.post(this::launch);
        }

        private void launch() {
            if (launched) return;
            launched = true;
            gotAssist = false;
            gotShot = false;
            ownApp = false;
            Intent i = new Intent(getContext(), MainActivity.class);
            i.putExtra("autostart", true);
            i.putExtra("fromAssist", true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startAssistantActivity(i);
            hide();
        }
    }
}
