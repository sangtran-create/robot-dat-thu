package vn.datthu.robot;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hiểu lệnh điều khiển app trên điện thoại: nhắn Zalo/Messenger, gửi Gmail,
 * tạo lịch Google Calendar, ghi chú Keep, mở các app Google Workspace.
 * Chỉ dùng thư viện Java thuần để dễ kiểm thử.
 */
public class Actions {

    public static class Contact {
        public String name = "", zalo = "", messenger = "", email = "";
    }

    public static class Action {
        public String type = "";      // message | email | calendar | note | open
        public String app = "";       // zalo | messenger | gmail | calendar | keep | docs | sheets | slides | drive | meet | tasks
        public String who = "";       // tên người nhận như anh nói
        public Contact contact;       // người nhận tìm thấy trong danh bạ robot (có thể null)
        public String content = "";   // nội dung tin / thư / tiêu đề sự kiện / ghi chú
        public String subject = "";   // tiêu đề thư
        public long startMs = 0;      // giờ bắt đầu sự kiện (0 = để anh tự chọn)
        public boolean needsConfirm() { return "message".equals(type) || "email".equals(type); }
    }

    private static final Locale VI = new Locale("vi", "VN");

    private static final Pattern MSG = Pattern.compile(
            "^(?:em\\s+)?(?:nhắn tin|nhắn|gửi tin nhắn|gửi tin|gửi)\\s+(?:qua\\s+)?"
                    + "(zalo|za lô|da lô|gia lô|messenger|mét xen giơ|mét sen giơ|mess|facebook|phây búc|phây)\\s*(?:cho\\s+)?(.*)$");
    private static final Pattern MAIL = Pattern.compile(
            "^(?:em\\s+)?(?:gửi|soạn|viết)\\s+(?:một\\s+)?(?:email|e mail|mail|meo|gmail|thư)\\s*(?:cho\\s+)?(.*)$");
    private static final Pattern CAL = Pattern.compile(
            "^(?:em\\s+)?(?:tạo|đặt|thêm|ghi|lên)\\s+(?:lịch hẹn|lịch họp|lịch|cuộc hẹn|cuộc họp|sự kiện)\\s*(.*)$");
    private static final Pattern NOTE = Pattern.compile(
            "^(?:em\\s+)?(?:ghi chú|ghi nhớ|ghi lại|lưu ghi chú|note)\\s*(?:giúp anh|giúp|lại)?\\s*[:,]?\\s*(.+)$");
    private static final Pattern OPEN = Pattern.compile("^(?:em\\s+)?mở\\s+(?:app\\s+|ứng dụng\\s+)?(.+)$");

    public static Action parse(String text, List<Contact> contacts) {
        if (text == null) return null;
        String orig = text.trim().replaceAll("[\\s,.!?]*(xong rồi|vậy đó em|vậy đó|hết ý|thế nhé)[.!\\s]*$", "").trim();
        String low = orig.toLowerCase(VI);
        Matcher m;

        if ((m = MSG.matcher(low)).find()) {
            Action a = new Action();
            a.type = "message";
            String app = m.group(1);
            a.app = (app.startsWith("z") || app.startsWith("d") || app.startsWith("g")) ? "zalo" : "messenger";
            splitWhoContent(a, orig.substring(m.start(2)), contacts);
            return a;
        }
        if ((m = MAIL.matcher(low)).find()) {
            Action a = new Action();
            a.type = "email";
            a.app = "gmail";
            splitWhoContent(a, orig.substring(m.start(1)), contacts);
            // "tiêu đề ... nội dung ..."
            Matcher t = Pattern.compile("(?i)tiêu đề\\s*[:,]?\\s*(.+?)\\s*[,.]?\\s*nội dung\\s*[:,]?\\s*(.+)$").matcher(a.content);
            if (t.find()) {
                a.subject = cap(t.group(1));
                a.content = t.group(2);
            }
            a.content = cleanSentence(a.content);
            return a;
        }
        if (low.contains("tạo cuộc họp meet") || low.contains("tạo phòng họp") || low.contains("mở cuộc họp meet")) {
            Action a = new Action();
            a.type = "open";
            a.app = "meet_new";
            return a;
        }
        if ((m = CAL.matcher(low)).find()) {
            Action a = new Action();
            a.type = "calendar";
            a.app = "calendar";
            String rest = orig.substring(m.start(1));
            a.startMs = parseTime(rest.toLowerCase(VI));
            a.content = cap(stripTime(rest));
            return a;
        }
        if ((m = NOTE.matcher(low)).find()) {
            Action a = new Action();
            a.type = "note";
            a.app = "keep";
            a.content = cleanSentence(orig.substring(m.start(1)));
            return a;
        }
        if ((m = OPEN.matcher(low)).find()) {
            String target = m.group(1).trim();
            String app = openTarget(target);
            if (app != null) {
                Action a = new Action();
                a.type = "open";
                a.app = app;
                return a;
            }
        }
        return null;
    }

    static String openTarget(String t) {
        String[][] map = {
                {"docs", "google docs", "google tài liệu", "tài liệu", "docs", "đốc"},
                {"sheets", "google sheets", "google trang tính", "trang tính", "sheets", "sheet", "bảng tính"},
                {"slides", "google slides", "trang trình bày", "slides", "slide"},
                {"drive", "google drive", "drive", "đrai", "rai"},
                {"meet", "google meet", "meet", "mít"},
                {"calendar", "google calendar", "lịch", "calendar"},
                {"gmail", "gmail", "hộp thư", "mail", "email"},
                {"keep", "google keep", "keep", "ghi chú"},
                {"tasks", "google tasks", "tasks", "việc cần làm"},
                {"zalo", "zalo", "za lô", "da lô"},
                {"messenger", "messenger", "mét xen giơ", "mess"}};
        for (String[] row : map) {
            for (int i = 1; i < row.length; i++) if (t.equals(row[i]) || t.startsWith(row[i] + " ")) return row[0];
        }
        return null;
    }

    /** Tách "tên: nội dung" / "tên là …" / "tên rằng …", ưu tiên tên có trong danh bạ robot. */
    static void splitWhoContent(Action a, String rest, List<Contact> contacts) {
        String r = rest.trim();
        String rl = r.toLowerCase(VI);
        if (contacts != null) {
            Contact best = null;
            for (Contact c : contacts) {
                String n = c.name.trim().toLowerCase(VI);
                if (n.isEmpty()) continue;
                if (rl.startsWith(n) && (best == null || n.length() > best.name.length())) best = c;
            }
            if (best != null) {
                a.contact = best;
                a.who = best.name;
                a.content = cleanSentence(stripLead(r.substring(best.name.trim().length())));
                return;
            }
        }
        int colon = r.indexOf(':');
        if (colon > 0 && colon < 40) {
            a.who = r.substring(0, colon).trim();
            a.content = cleanSentence(r.substring(colon + 1));
            return;
        }
        String[] seps = {" là ", " rằng ", " nội dung ", " báo ", " nói ", " nhắn "};
        for (String s : seps) {
            int i = rl.indexOf(s);
            if (i > 0 && i < 40) {
                a.who = r.substring(0, i).trim();
                a.content = cleanSentence(r.substring(i + s.length()));
                return;
            }
        }
        a.content = cleanSentence(r);
    }

    static String stripLead(String s) {
        return s.replaceAll("^[\\s,:.]*(là|rằng|nội dung|báo|nói|nhắn)?[\\s,:.]*", "");
    }

    static String cleanSentence(String s) {
        String t = s == null ? "" : s.trim().replaceAll("\\s+", " ");
        t = t.replaceAll("^[,:.\\s]+", "");
        if (t.isEmpty()) return t;
        t = cap(t);
        char last = t.charAt(t.length() - 1);
        if (".!?…".indexOf(last) < 0) t = t + ".";
        return t;
    }

    static String cap(String s) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty()) return t;
        return t.substring(0, 1).toUpperCase(VI) + t.substring(1);
    }

    // ---------- Giờ giấc tiếng Việt ----------

    private static final Pattern HOUR = Pattern.compile("(\\d{1,2})\\s*(?:giờ|h|:)\\s*(\\d{1,2})?\\s*(?:phút)?\\s*(rưỡi)?\\s*(sáng|trưa|chiều|tối|đêm)?");
    private static final Pattern DAY_NUM = Pattern.compile("ngày\\s+(\\d{1,2})(?:\\s*(?:tháng|/)\\s*(\\d{1,2}))?");
    private static final String[] WEEKDAYS = {"chủ nhật", "thứ hai", "thứ ba", "thứ tư", "thứ năm", "thứ sáu", "thứ bảy"};

    static long parseTime(String low) {
        Calendar c = Calendar.getInstance();
        Calendar now = (Calendar) c.clone();
        boolean dayGiven = false;
        if (Pattern.compile("ngày kia|ngày mốt|(^|\\s)mốt(\\s|$)").matcher(low).find()) { c.add(Calendar.DAY_OF_MONTH, 2); dayGiven = true; }
        else if (Pattern.compile("(ngày|sáng|trưa|chiều|tối) mai|(^|\\s)mai\\s+(lúc|\\d)").matcher(low).find()) { c.add(Calendar.DAY_OF_MONTH, 1); dayGiven = true; }
        else if (Pattern.compile("hôm nay|(sáng|trưa|chiều|tối) nay").matcher(low).find()) { dayGiven = true; }
        for (int i = 0; i < WEEKDAYS.length && !dayGiven; i++) {
            if (low.contains(WEEKDAYS[i])) {
                int target = i + 1; // Calendar.SUNDAY = 1
                int diff = (target - c.get(Calendar.DAY_OF_WEEK) + 7) % 7;
                if (diff == 0) diff = 7;
                c.add(Calendar.DAY_OF_MONTH, diff);
                dayGiven = true;
            }
        }
        Matcher d = DAY_NUM.matcher(low);
        if (!dayGiven && d.find()) {
            c.set(Calendar.DAY_OF_MONTH, Integer.parseInt(d.group(1)));
            if (d.group(2) != null) c.set(Calendar.MONTH, Integer.parseInt(d.group(2)) - 1);
            if (c.before(now)) c.add(d.group(2) != null ? Calendar.YEAR : Calendar.MONTH, 1);
            dayGiven = true;
        }
        Matcher h = HOUR.matcher(low);
        if (!h.find()) return dayGiven ? atHour(c, 9, 0) : 0;
        int hour = Integer.parseInt(h.group(1));
        int min = h.group(2) != null ? Integer.parseInt(h.group(2)) : (h.group(3) != null ? 30 : 0);
        String part = h.group(4);
        if (part != null && (part.equals("chiều") || part.equals("tối") || part.equals("đêm")) && hour < 12) hour += 12;
        if (part != null && part.equals("trưa") && hour < 11) hour += 12;
        if (part == null && hour >= 1 && hour <= 6) hour += 12; // "3 giờ" thường là 3 giờ chiều
        long t = atHour(c, Math.min(hour, 23), Math.min(min, 59));
        if (!dayGiven && t < now.getTimeInMillis()) t += 24L * 3600 * 1000;
        return t;
    }

    private static long atHour(Calendar c, int h, int m) {
        Calendar x = (Calendar) c.clone();
        x.set(Calendar.HOUR_OF_DAY, h);
        x.set(Calendar.MINUTE, m);
        x.set(Calendar.SECOND, 0);
        x.set(Calendar.MILLISECOND, 0);
        return x.getTimeInMillis();
    }

    static String stripTime(String s) {
        String t = s.replaceAll("(?i)(lúc|vào)?\\s*\\d{1,2}\\s*(giờ|h|:)\\s*(\\d{1,2})?\\s*(phút)?\\s*(rưỡi)?\\s*(sáng|trưa|chiều|tối|đêm)?", " ");
        t = t.replaceAll("(?i)(vào\\s+)?(ngày mai|ngày kia|ngày mốt|hôm nay|tuần sau|tuần tới|chủ nhật|thứ (hai|ba|tư|năm|sáu|bảy))", " ");
        t = t.replaceAll("(?i)(vào\\s+)?ngày\\s+\\d{1,2}(\\s*(tháng|/)\\s*\\d{1,2})?", " ");
        t = t.replaceAll("(?i)(^|\\s)(mai|nay)(?=\\s|$)", " ");
        t = t.replaceAll("^[\\s,:.]*(là|về|để|:)?\\s*", "").replaceAll("\\s+", " ").replaceAll("[\\s,]+$", "").trim();
        return t;
    }

    /** Câu trả lời khi robot hỏi "gửi không?": "yes" | "no" | "edit" | null */
    public static String confirmIntent(String text) {
        String t = text.toLowerCase(VI).replaceAll("[\\p{Punct}…]+", " ").replaceAll("\\s+", " ").trim();
        if (t.startsWith("sửa") || t.contains("sửa lại") || t.contains("đổi lại") || t.contains("sửa thành")) return "edit";
        String[] no = {"không", "thôi", "hủy", "khỏi", "đừng", "chưa"};
        for (String w : no) if (t.equals(w) || t.startsWith(w + " ")) return "no";
        String[] yes = {"gửi", "gửi đi", "ok", "oke", "okay", "được", "đồng ý", "ừ", "ừm", "đúng rồi", "chuẩn", "có", "làm đi", "chốt", "yes", "vâng", "dạ"};
        for (String w : yes) if (t.equals(w) || t.startsWith(w + " ") || t.endsWith(" " + w)) return "yes";
        return null;
    }

    public static String appLabel(String app) {
        switch (app) {
            case "zalo": return "Zalo";
            case "messenger": return "Messenger";
            case "gmail": return "Gmail";
            case "calendar": return "Google Lịch";
            case "keep": return "Google Keep";
            case "docs": return "Google Docs";
            case "sheets": return "Google Sheets";
            case "slides": return "Google Slides";
            case "drive": return "Google Drive";
            case "meet": case "meet_new": return "Google Meet";
            case "tasks": return "Google Tasks";
            default: return app;
        }
    }

    public static List<Contact> emptyList() { return new ArrayList<>(); }
}
