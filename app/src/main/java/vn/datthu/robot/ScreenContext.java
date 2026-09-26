package vn.datthu.robot;

import java.io.File;

/**
 * Màn hình lúc anh giữ nút nguồn gọi robot: app đang mở, chữ trên màn hình, ảnh chụp.
 * Chỉ giữ trong bộ nhớ và thư mục tạm của app, tự xóa sau 3 phút hoặc khi robot nghỉ.
 */
public class ScreenContext {
    public static volatile String pkg = "";
    public static volatile String text = "";
    public static volatile String shotPath = "";
    public static volatile long timeMs = 0;

    public static synchronized void clear() {
        if (!shotPath.isEmpty()) {
            try { new File(shotPath).delete(); } catch (Exception ignored) { }
        }
        pkg = ""; text = ""; shotPath = ""; timeMs = 0;
    }

    public static boolean fresh() {
        boolean has = !text.isEmpty() || !shotPath.isEmpty();
        if (has && System.currentTimeMillis() - timeMs > 3 * 60 * 1000L) { clear(); return false; }
        return has;
    }

    public static String appName(String p) {
        if (p == null) return "";
        switch (p) {
            case "com.zing.zalo": return "Zalo";
            case "com.facebook.orca": return "Messenger";
            case "com.facebook.katana": return "Facebook";
            case "com.google.android.gm": return "Gmail";
            case "com.android.chrome": return "Chrome";
            case "com.google.android.calendar": return "Google Lịch";
            case "com.google.android.apps.docs.editors.docs": return "Google Docs";
            case "com.google.android.apps.docs.editors.sheets": return "Google Sheets";
            case "com.google.android.apps.docs.editors.slides": return "Google Slides";
            case "com.google.android.apps.docs": return "Google Drive";
            case "com.google.android.keep": return "Google Keep";
            case "com.anthropic.claude": return "Claude";
            case "com.openai.chatgpt": return "ChatGPT";
            case "com.google.android.youtube": return "YouTube";
            default: return p;
        }
    }
}
