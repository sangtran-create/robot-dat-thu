package vn.datthu.robot;

import android.os.Handler;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.json.JSONArray;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/** Gọi API chat của Dify, nhận câu trả lời dạng stream. */
public class DifyClient {

    public interface Listener {
        void onDelta(String text);
        void onEnd(String conversationId);
        void onError(String message);
    }

    public static class Call {
        volatile boolean cancelled;
        volatile HttpURLConnection conn;

        public void cancel() {
            cancelled = true;
            HttpURLConnection c = conn;
            if (c != null) {
                new Thread(() -> { try { c.disconnect(); } catch (Exception ignored) { } }).start();
            }
        }
    }

    public static Call stream(String base, String key, String query, String convId, String user,
                              List<byte[]> images, Listener l, Handler main) {
        Call c = new Call();
        new Thread(() -> {
            JSONArray files = new JSONArray();
            if (images != null) {
                for (byte[] img : images) {
                    if (c.cancelled) return;
                    try {
                        String id = upload(base, key, user, img);
                        JSONObject f = new JSONObject();
                        f.put("type", "image");
                        f.put("transfer_method", "local_file");
                        f.put("upload_file_id", id);
                        files.put(f);
                    } catch (Exception e) {
                        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                        if (!c.cancelled) main.post(() -> l.onError("Không gửi được ảnh lên Dify: " + m));
                        return;
                    }
                }
            }
            run(c, base, key, query, convId, user, files, l, main, true);
        }, "dify").start();
        return c;
    }

    /** Tải một ảnh JPEG lên Dify, trả về id của file. */
    private static String upload(String base, String key, String user, byte[] jpeg) throws Exception {
        String b = trimBase(base);
        String boundary = "----robot" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(b + "/files/upload").openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("Authorization", "Bearer " + key.trim());
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"user\"\r\n\r\n" + user + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"robot.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n";
        bo.write(head.getBytes(StandardCharsets.UTF_8));
        bo.write(jpeg);
        bo.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        try (OutputStream os = conn.getOutputStream()) { bo.writeTo(os); }
        int code = conn.getResponseCode();
        if (code != 200 && code != 201) {
            String raw = readAll(conn.getErrorStream());
            conn.disconnect();
            throw new Exception(errorText(code, raw));
        }
        String raw = readAll(conn.getInputStream());
        conn.disconnect();
        return new JSONObject(raw).getString("id");
    }

    private static String trimBase(String base) {
        String b = base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    private static void run(Call c, String base, String key, String query, String convId, String user,
                            JSONArray files, Listener l, Handler main, boolean allowRetry) {
        HttpURLConnection conn = null;
        try {
            String b = trimBase(base);
            conn = (HttpURLConnection) new URL(b + "/chat-messages").openConnection();
            c.conn = conn;
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(180000);
            conn.setRequestProperty("Authorization", "Bearer " + key.trim());
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream");

            JSONObject body = new JSONObject();
            body.put("inputs", new JSONObject());
            body.put("query", query);
            body.put("response_mode", "streaming");
            body.put("user", user);
            if (convId != null && !convId.isEmpty()) body.put("conversation_id", convId);
            if (files != null && files.length() > 0) body.put("files", files);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                String raw = readAll(conn.getErrorStream());
                if (code == 404 && allowRetry && convId != null && !convId.isEmpty()) {
                    // Cuộc trò chuyện cũ không còn: bắt đầu cuộc mới
                    run(c, base, key, query, null, user, files, l, main, false);
                    return;
                }
                String msg = (files != null && files.length() > 0 && code == 400)
                        ? errorText(code, raw) + ". Nếu là lỗi ảnh: bật Thị giác (Vision) cho robot trong Dify rồi Xuất bản lại."
                        : errorText(code, raw);
                if (!c.cancelled) main.post(() -> l.onError(msg));
                return;
            }

            BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            String line;
            String conv = convId == null ? "" : convId;
            while (!c.cancelled && (line = r.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty()) continue;
                JSONObject ev;
                try { ev = new JSONObject(data); } catch (JSONException e) { continue; }
                String event = ev.optString("event");
                String cid = ev.optString("conversation_id", "");
                if (!cid.isEmpty()) conv = cid;
                if ("message".equals(event) || "agent_message".equals(event)) {
                    String a = ev.optString("answer", "");
                    if (!a.isEmpty() && !c.cancelled) main.post(() -> l.onDelta(a));
                } else if ("message_end".equals(event)) {
                    final String fc = conv;
                    if (!c.cancelled) main.post(() -> l.onEnd(fc));
                    return;
                } else if ("error".equals(event)) {
                    String msg = ev.optString("message", "Dify báo lỗi");
                    if (!c.cancelled) main.post(() -> l.onError(msg));
                    return;
                }
            }
            final String fc = conv;
            if (!c.cancelled) main.post(() -> l.onEnd(fc));
        } catch (UnknownHostException e) {
            if (!c.cancelled) main.post(() -> l.onError("Không có mạng hoặc sai địa chỉ Dify"));
        } catch (SocketTimeoutException e) {
            if (!c.cancelled) main.post(() -> l.onError("Dify trả lời quá lâu"));
        } catch (Exception e) {
            String m = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            if (!c.cancelled) main.post(() -> l.onError("Lỗi kết nối (" + m + ")"));
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception ignored) { }
        }
    }

    private static String errorText(int code, String raw) {
        String detail = "";
        try {
            JSONObject o = new JSONObject(raw);
            detail = o.optString("message", "");
        } catch (Exception ignored) { }
        if (code == 401) return "API key của robot sai hoặc đã bị xóa";
        if (code == 429) return "Hết lượt hoặc gửi quá nhanh (Dify 429)";
        return "Dify lỗi " + code + (detail.isEmpty() ? "" : ": " + detail);
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
