package com.artemis.helpermin;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Safety constants and checks: danger-phrase lock and secret-field refusal. */
final class Guard {

    /** Sticky: once a danger phrase is seen, action endpoints refuse until POST /guard/reset. */
    static volatile boolean dangerLock;
    static volatile String lockPhrase;

    static final String[] DANGER = {"해킹 위험성", "해킹 위험", "서비스 이용이 제한", "위험성이 탐지",
            "security risk detected", "hacking risk"};

    /** Field hint/description/id/label keywords. Leading '^' = ASCII word match (no letter on either side). */
    static final String[] SECRET = {"password", "passwd", "비밀번호", "암호", "^pin", "인증", "^otp", "주민", "보안",
            "^card", "카드번호", "^cvc", "계좌"};

    private static final long SCAN_MS = 1500;
    private static final int SCAN_NODES_PER_WINDOW = 250;

    // ------------------------------------------------------------ danger phrases

    static String matchDanger(CharSequence c) {
        if (c == null || c.length() == 0) return null;
        String s = c.toString().toLowerCase(Locale.ROOT);
        for (String p : DANGER) if (s.contains(p.toLowerCase(Locale.ROOT))) return p;
        return null;
    }

    /** Capped scan of all visible window texts. Returns the matched phrase or null. */
    static String scan(AccessibilityService svc) {
        long t0 = SystemClock.elapsedRealtime();
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        List<AccessibilityWindowInfo> wins = svc.getWindows(); // front-most first
        if (wins != null) {
            for (AccessibilityWindowInfo w : wins) {
                String m = matchDanger(w.getTitle());
                if (m != null) return m;
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null) roots.add(r);
            }
        }
        if (roots.isEmpty()) {
            AccessibilityNodeInfo r = svc.getRootInActiveWindow();
            if (r != null) roots.add(r);
        }
        for (AccessibilityNodeInfo r : roots) {
            String m = walk(r, new int[]{SCAN_NODES_PER_WINDOW}, t0);
            if (m != null) return m;
            if (SystemClock.elapsedRealtime() - t0 > SCAN_MS) break;
        }
        return null;
    }

    private static String walk(AccessibilityNodeInfo n, int[] budget, long t0) {
        if (n == null || --budget[0] < 0 || Thread.currentThread().isInterrupted()
                || SystemClock.elapsedRealtime() - t0 > SCAN_MS || !n.isVisibleToUser()) return null;
        String m = matchDanger(n.getText());
        if (m == null) m = matchDanger(n.getContentDescription());
        if (m == null && Build.VERSION.SDK_INT >= 28) m = matchDanger(n.getPaneTitle());
        if (m != null) return m;
        for (int i = 0; i < n.getChildCount(); i++) {
            m = walk(n.getChild(i), budget, t0);
            if (m != null) return m;
        }
        return null;
    }

    /** Set the sticky lock and build the stop response. Never click anything on the popup. */
    static JSONObject trip(String phrase) {
        dangerLock = true;
        lockPhrase = phrase;
        JSONObject r = Server.error("danger_phrase");
        try {
            r.remove("error");
            r.put("stopped", "danger_phrase");
            r.put("phrase", phrase);
            r.put("hint", "do not press confirm; tell the user");
            r.put("danger_lock", true);
        } catch (Exception ignored) {}
        return r;
    }

    static JSONObject lockedError() {
        JSONObject r = Server.error("danger_lock");
        try {
            r.put("phrase", lockPhrase);
            r.put("hint", "tell the user; POST /guard/reset {confirm:'user-approved'} only after the user agrees");
        } catch (Exception ignored) {}
        return r;
    }

    static JSONObject status() throws Exception {
        JSONObject r = new JSONObject();
        r.put("success", true);
        r.put("danger_lock", dangerLock);
        r.put("phrase", dangerLock ? lockPhrase : JSONObject.NULL);
        r.put("phrases", toJson(DANGER));
        r.put("secret_keywords", toJson(SECRET));
        r.put("type_scope", "built-in demo input only");
        return r;
    }

    private static JSONArray toJson(String[] a) {
        JSONArray j = new JSONArray();
        for (String s : a) j.put(s);
        return j;
    }

    // ------------------------------------------------------------ secret fields

    private static String norm(CharSequence c) {
        if (c == null) return "";
        return c.toString().replaceAll("([a-z])([A-Z])", "$1 $2").replaceAll("[^\\p{L}\\p{N}]+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static boolean asciiLetter(char ch) {
        return ch >= 'a' && ch <= 'z';
    }

    /** Matched keyword in an already normalized string, or null. */
    private static String keyword(String s) {
        if (s.isEmpty()) return null;
        for (String k : SECRET) {
            boolean word = k.startsWith("^");
            String kw = word ? k.substring(1) : k;
            int from = 0, i;
            while ((i = s.indexOf(kw, from)) >= 0) {
                if (!word) return kw;
                boolean before = i == 0 || !asciiLetter(s.charAt(i - 1));
                boolean after = i + kw.length() >= s.length() || !asciiLetter(s.charAt(i + kw.length()));
                if (before && after) return kw;
                from = i + 1;
            }
        }
        return null;
    }

    private static boolean inputTypePassword(int it) {
        int cls = it & 0xf, var = it & 0xff0;
        if (cls == 1) return var == 0x80 || var == 0x90 || var == 0xe0; // text password variants
        return cls == 2 && var == 0x10;                                  // number password
    }

    /** Returns null when the field looks harmless, else a short reason (never contains field text). */
    static String secretReason(AccessibilityNodeInfo n) {
        if (n.isPassword()) return "isPassword";
        if (inputTypePassword(n.getInputType())) return "inputType";
        StringBuilder sb = new StringBuilder();
        sb.append(norm(n.getContentDescription())).append(' ').append(norm(n.getViewIdResourceName()));
        if (Build.VERSION.SDK_INT >= 26) sb.append(' ').append(norm(n.getHintText()));
        if (Build.VERSION.SDK_INT >= 28) sb.append(' ').append(norm(n.getTooltipText()));
        AccessibilityNodeInfo lb = n.getLabeledBy();
        if (lb != null) sb.append(' ').append(norm(lb.getText())).append(' ').append(norm(lb.getContentDescription()));
        AccessibilityNodeInfo p = n.getParent(); // e.g. TextInputLayout hint / wrapper description
        if (p != null) {
            sb.append(' ').append(norm(p.getContentDescription()));
            if (Build.VERSION.SDK_INT >= 26) sb.append(' ').append(norm(p.getHintText()));
        }
        String k = keyword(sb.toString());
        return k == null ? null : "keyword:" + k;
    }
}
