package vn.datthu.robot;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tách ký hiệu cảm xúc (lệnh robot) ra khỏi lời nói. */
public class Emotion {
    // Dài trước ngắn sau để ">:(" không bị đọc thành ":("
    private static final Pattern CUE = Pattern.compile(
            "=\\)\\)|:\\)\\^|:\\(~|>:\\(|:\\?!|:\\?|:\\)|:\\(|~\\*~|<-|->|\\^\\^|__|<>|><");

    public static class Result {
        public final List<String> cues = new ArrayList<>();
        public String display = "";   // hiện trên màn hình (giữ "...")
        public String speech = "";    // đưa cho TTS
    }

    public static Result parse(String s) {
        Result r = new Result();
        if (s == null) return r;
        if (s.contains("...") || s.contains("…")) r.cues.add("...");
        Matcher m = CUE.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            r.cues.add(m.group());
            m.appendReplacement(sb, " ");
        }
        m.appendTail(sb);
        String clean = sb.toString().replaceAll("[\\*#`_]+", " ").replaceAll("\\s+", " ").trim();
        r.display = clean;
        r.speech = clean.replace("…", "...");
        return r;
    }

    /** Có chữ để đọc không (không chỉ toàn dấu câu). */
    public static boolean hasWords(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetterOrDigit(s.charAt(i))) return true;
        }
        return false;
    }
}
