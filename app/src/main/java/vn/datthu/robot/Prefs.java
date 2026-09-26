package vn.datthu.robot;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Cài đặt lưu trên máy. */
public class Prefs {
    private final SharedPreferences p;

    public Prefs(Context c) {
        p = c.getSharedPreferences("robot", Context.MODE_PRIVATE);
        if (!p.contains("userId")) {
            p.edit().putString("userId", "pixel-" + UUID.randomUUID().toString().substring(0, 8)).apply();
        }
    }

    public String base() { return p.getString("base", "https://api.dify.ai/v1"); }
    public String userId() { return p.getString("userId", "pixel"); }
    public String endMode() { return p.getString("endMode", "phrase"); } // phrase | pause
    public int autoSendSec() { return p.getInt("autoSendSec", 20); }
    public int idleTimeoutSec() { return p.getInt("idleTimeoutSec", 60); }
    public float rate() { return p.getFloat("rate", 0.96f); }
    public float pitch() { return p.getFloat("pitch", 0.86f); }
    public boolean vibrate() { return p.getBoolean("vibrate", true); }
    public boolean volumeKey() { return p.getBoolean("volumeKey", true); }
    public boolean autostart() { return p.getBoolean("autostart", true); }
    public int current() { return p.getInt("current", 0); }
    public String voice() { return p.getString("voice", ""); }
    public boolean screenAssist() { return p.getBoolean("screenAssist", true); }

    public JSONArray contactsJson() {
        try { return new JSONArray(p.getString("contacts", "[]")); } catch (JSONException e) { return new JSONArray(); }
    }

    public List<Actions.Contact> contacts() {
        List<Actions.Contact> out = new ArrayList<>();
        JSONArray a = contactsJson();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Actions.Contact c = new Actions.Contact();
            c.name = o.optString("name", "");
            c.zalo = o.optString("zalo", "");
            c.messenger = o.optString("messenger", "");
            c.email = o.optString("email", "");
            if (!c.name.trim().isEmpty()) out.add(c);
        }
        return out;
    }

    public List<String> endPhrases() {
        String raw = p.getString("endPhrases", "xong rồi, vậy đó em, vậy đó, em nghĩ sao, em thấy sao, hết ý, thế nhé");
        List<String> out = new ArrayList<>();
        for (String s : raw.split(",")) {
            String t = s.trim().toLowerCase(new Locale("vi"));
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public JSONArray robots() {
        try {
            return new JSONArray(p.getString("robots", "[{\"name\":\"Quân sư Khổng Minh\",\"key\":\"\"}]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    /** Robot đang chọn: {name, key}. */
    public JSONObject robot() {
        JSONArray r = robots();
        if (r.length() == 0) return new JSONObject();
        int i = Math.max(0, Math.min(current(), r.length() - 1));
        return r.optJSONObject(i) == null ? new JSONObject() : r.optJSONObject(i);
    }

    public String conversation(String key) { return p.getString("conv_" + key.hashCode(), ""); }
    public void setConversation(String key, String id) { p.edit().putString("conv_" + key.hashCode(), id == null ? "" : id).apply(); }
    public void setCurrent(int i) { p.edit().putInt("current", i).apply(); }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("base", base());
            o.put("robots", robots());
            o.put("current", current());
            o.put("endMode", endMode());
            o.put("endPhrases", p.getString("endPhrases", "xong rồi, vậy đó em, vậy đó, em nghĩ sao, em thấy sao, hết ý, thế nhé"));
            o.put("autoSendSec", autoSendSec());
            o.put("idleTimeoutSec", idleTimeoutSec());
            o.put("rate", (double) rate());
            o.put("pitch", (double) pitch());
            o.put("vibrate", vibrate());
            o.put("volumeKey", volumeKey());
            o.put("autostart", autostart());
            o.put("voice", voice());
            o.put("screenAssist", screenAssist());
            o.put("contacts", contactsJson());
        } catch (JSONException ignored) { }
        return o;
    }

    public void fromJson(JSONObject o) {
        SharedPreferences.Editor e = p.edit();
        if (o.has("base")) e.putString("base", o.optString("base").trim());
        if (o.has("robots")) {
            JSONArray r = o.optJSONArray("robots");
            if (r != null) e.putString("robots", r.toString());
        }
        if (o.has("current")) e.putInt("current", o.optInt("current"));
        if (o.has("endMode")) e.putString("endMode", o.optString("endMode"));
        if (o.has("endPhrases")) e.putString("endPhrases", o.optString("endPhrases"));
        if (o.has("autoSendSec")) e.putInt("autoSendSec", clamp(o.optInt("autoSendSec", 20), 5, 120));
        if (o.has("idleTimeoutSec")) e.putInt("idleTimeoutSec", clamp(o.optInt("idleTimeoutSec", 60), 15, 600));
        if (o.has("rate")) e.putFloat("rate", (float) o.optDouble("rate", 1.0));
        if (o.has("pitch")) e.putFloat("pitch", (float) o.optDouble("pitch", 1.0));
        if (o.has("vibrate")) e.putBoolean("vibrate", o.optBoolean("vibrate", true));
        if (o.has("volumeKey")) e.putBoolean("volumeKey", o.optBoolean("volumeKey", true));
        if (o.has("autostart")) e.putBoolean("autostart", o.optBoolean("autostart", true));
        if (o.has("voice")) e.putString("voice", o.optString("voice", ""));
        if (o.has("screenAssist")) e.putBoolean("screenAssist", o.optBoolean("screenAssist", true));
        if (o.has("contacts") && o.optJSONArray("contacts") != null) e.putString("contacts", o.optJSONArray("contacts").toString());
        e.apply();
    }

    public void resetConversation() {
        String key = robot().optString("key", "");
        setConversation(key, "");
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
