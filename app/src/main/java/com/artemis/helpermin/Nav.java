package com.artemis.helpermin;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** All endpoint logic. One instance per request; nothing here keeps state. */
class Nav {

    private static final int CUSTOM_ACTION_MIN_ID = 0x01000000;
    private static final long SETTLE_MS = 200;

    private final AccessibilityService svc;

    /** mode=tb: TalkBack-like candidate filter (see collectTb). Default plain keeps the old behavior. */
    private final boolean tb;
    private final java.util.Map<String, Integer> reasons = new java.util.LinkedHashMap<>();
    private int visits;

    Nav(AccessibilityService svc) {
        this(svc, false);
    }

    Nav(AccessibilityService svc, boolean tb) {
        this(svc, tb, false, false);
    }

    /**
     * strict (mode=tbx): tb filter plus stricter TalkBack-swipe rules (see collectTb).
     * swipe (mode=swipe): /focus/next|prev perform a real one-finger TalkBack swipe; first/last/kind use tbx lists.
     */
    Nav(AccessibilityService svc, boolean tb, boolean strict, boolean swipe) {
        this.svc = svc;
        this.tb = tb;
        this.strict = strict;
        this.swipeMode = swipe;
    }

    private final boolean strict;
    private final boolean swipeMode;

    private String modeName() {
        return swipeMode ? "swipe" : strict ? "tbx" : tb ? "tb" : "plain";
    }

    /** True when a TalkBack accessibility service is enabled (needed for mode=swipe). */
    private boolean talkBackOn() {
        try {
            String v = android.provider.Settings.Secure.getString(svc.getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return v != null && v.toLowerCase(Locale.ROOT).contains("talkback");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean imageLike(AccessibilityNodeInfo n) {
        String c = cls(n);
        return c.endsWith("ImageView") || c.endsWith("Image");
    }

    // ---------------------------------------------------------------- TalkBack-like filter (mode=tb)

    private void skip(String why) {
        Integer c = reasons.get(why);
        reasons.put(why, c == null ? 1 : c + 1);
    }

    private JSONObject reasonsJson() throws Exception {
        JSONObject j = new JSONObject();
        for (java.util.Map.Entry<String, Integer> e : reasons.entrySet()) j.put(e.getKey(), e.getValue());
        return j;
    }

    private static boolean has(CharSequence s) {
        return s != null && s.length() > 0;
    }

    private static boolean hasLabel(AccessibilityNodeInfo n) {
        if (has(n.getText()) || has(n.getContentDescription())) return true;
        if (Build.VERSION.SDK_INT >= 30 && has(n.getStateDescription())) return true;
        return Build.VERSION.SDK_INT >= 28 && has(n.getPaneTitle());
    }

    private static boolean scrollContainer(AccessibilityNodeInfo n) {
        String c = cls(n);
        return n.isScrollable() || c.contains("RecyclerView") || c.endsWith("ListView")
                || c.contains("ScrollView") || c.contains("ViewPager");
    }

    private static boolean a11yFocusable(AccessibilityNodeInfo n) {
        return n.isFocusable() || (Build.VERSION.SDK_INT >= 28 && n.isScreenReaderFocusable());
    }

    /**
     * Candidates: visible nodes that have a label or are focusable/clickable, minus (a) descendants of a
     * speaking ancestor (clickable/focusable with text, contentDescription or childText) unless they are
     * clickable themselves, (b) unlabeled non-focusable nodes, (c) pure containers. Scroll containers
     * only count when they carry their own label.
     */
    private void collectTb(AccessibilityNodeInfo n, boolean underSpeaking, List<AccessibilityNodeInfo> out) {
        collectTb(n, underSpeaking, out, 0);
    }

    private void collectTb(AccessibilityNodeInfo n, boolean underSpeaking, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > MAX_DEPTH || ++visits > MAX_NODES || out.size() >= MAX_NODES || Thread.currentThread().isInterrupted()) return;
        boolean childFlag = underSpeaking;
        int mine = -1; // index of n in out when it is a wrapper candidate (unlabeled, clickable, not focusable)
        if (usable(n)) {
            boolean label = hasLabel(n);
            boolean sc = scrollContainer(n) && !(strict && cls(n).contains("WebView") && n.isFocusable()); // real TalkBack stops on a focusable WebView
            boolean clk = !sc && n.isClickable();
            boolean foc = !sc && a11yFocusable(n);
            boolean web = strict && underWebView(n);
            boolean keepAlways = n.isCheckable() || isEditable(n);
            if (underSpeaking && !clk) skip("descendant_of_speaking");
            else if (strict && underSpeaking && !keepAlways && (!label || imageLike(n))) skip("tbx_speaking_descendant_unlabeled_or_image");
            else if (strict && !keepAlways && !label && imageLike(n)) skip("tbx_unlabeled_image");
            // measured with real TalkBack swipes: unlabeled, non-focusable web nodes (click-only overlays/wrappers) are not stops
            else if (strict && !keepAlways && web && !label && !a11yFocusable(n) && !cls(n).contains("Button")) skip("tbx_unlabeled_nonfocusable_web");
            else if (!label && !foc && !clk) skip(n.getChildCount() == 0 ? "unlabeled_non_focusable" : "pure_container");
            else {
                if (!label && !foc && n.getChildCount() > 0) mine = out.size();
                out.add(n);
            }
            if (strict ? (!sc && foc && !cls(n).contains("WebView") && (label || childText(n).length() > 0))
                    : (!sc && (clk || foc) && (label || childText(n).length() > 0))) childFlag = true;
        }
        for (int i = 0; i < n.getChildCount(); i++) collectTb(n.getChild(i), childFlag, out, depth + 1);
        // a clickable-only wrapper around candidates is not a stop of its own (its focusable child speaks)
        if (mine >= 0 && out.size() > mine + 1) {
            out.remove(mine);
            skip("wrapper_of_candidate");
        }
    }

    /** tb candidate list in reading order, with getTraversalBefore/After honored when both ends are listed. */
    private List<AccessibilityNodeInfo> tbList(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> list = new ArrayList<>();
        visits = 0;
        collectTb(root, false, list); // DFS = view tree order, which is what TalkBack follows (not top/left geometry)
        for (int pass = 0; pass < 2 && list.size() < 400; pass++) {
            for (int i = 0; i < list.size(); i++) {
                AccessibilityNodeInfo c = list.get(i);
                AccessibilityNodeInfo t = c.getTraversalBefore(); // c must come before t
                int j = t == null ? -1 : list.indexOf(t);
                if (j >= 0 && j < i) { list.remove(i); list.add(j, c); continue; }
                t = c.getTraversalAfter(); // c must come after t
                j = t == null ? -1 : list.indexOf(t);
                if (j > i) { list.remove(i); list.add(j, c); i--; }
            }
        }
        return list;
    }

    // ---------------------------------------------------------------- helpers

    private List<AccessibilityNodeInfo> roots() {
        List<AccessibilityNodeInfo> list = new ArrayList<>();
        List<AccessibilityWindowInfo> wins = svc.getWindows(); // front-most first
        if (wins != null) {
            for (AccessibilityWindowInfo w : wins) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null) list.add(r);
            }
        }
        if (list.isEmpty()) {
            AccessibilityNodeInfo r = svc.getRootInActiveWindow();
            if (r != null) list.add(r);
        }
        return list;
    }

    private AccessibilityNodeInfo findA11yFocus() {
        for (AccessibilityNodeInfo r : roots()) {
            AccessibilityNodeInfo f = r.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            if (f != null) return f;
        }
        // fallback: walk all windows for a node flagged accessibility-focused
        for (AccessibilityNodeInfo r : roots()) {
            AccessibilityNodeInfo f = walkFocused(r, new int[]{0});
            if (f != null) return f;
        }
        return null;
    }

    private static AccessibilityNodeInfo walkFocused(AccessibilityNodeInfo n, int[] budget) {
        if (n == null || ++budget[0] > MAX_NODES) return null;
        if (n.isAccessibilityFocused()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = walkFocused(n.getChild(i), budget);
            if (f != null) return f;
        }
        return null;
    }

    private static Rect bounds(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return r;
    }

    /**
     * Visible, non-empty-bounds node that is important for accessibility. WebView content nodes (virtual tree
     * built by Chromium) always report isImportantForAccessibility()==false, so they are accepted when an
     * ancestor is a WebView (checked only for the rare visible non-important node).
     */
    private static boolean usable(AccessibilityNodeInfo n) {
        if (n == null || !n.isVisibleToUser()) return false;
        if (bounds(n).isEmpty()) return false;
        return n.isImportantForAccessibility() || underWebView(n) || meaningfulNonImportant(n);
    }

    /**
     * v0.1.16: visible non-important node outside a WebView that is still a meaningful control (popup/menu items
     * marked importantForAccessibility=no): (clickable || checkable || click/expand/collapse action) AND
     * (text/contentDescription/stateDescription/paneTitle or childText). Plain layout containers and text-less
     * wrappers do not qualify.
     */
    private static boolean meaningfulNonImportant(AccessibilityNodeInfo n) {
        boolean act = n.isClickable() || n.isCheckable();
        if (!act) {
            for (AccessibilityAction a : n.getActionList()) {
                int id = a.getId();
                if (id == AccessibilityNodeInfo.ACTION_CLICK || id == AccessibilityNodeInfo.ACTION_EXPAND
                        || id == AccessibilityNodeInfo.ACTION_COLLAPSE) { act = true; break; }
            }
        }
        if (!act) return false;
        return hasLabel(n) || childText(n).length() > 0;
    }

    /** True when n was accepted only by the v0.1.16 non-important rule (not important, not under a WebView). */
    private static boolean addedNonImportant(AccessibilityNodeInfo n) {
        return !n.isImportantForAccessibility() && !underWebView(n);
    }

    /** Counts (and reports as a reason counter) the candidates that came in through the non-important rule. */
    private int countAdded(List<AccessibilityNodeInfo> list) {
        int c = 0;
        for (AccessibilityNodeInfo n : list) if (addedNonImportant(n)) c++;
        if (c > 0) { Integer o = reasons.get("added_non_important"); reasons.put("added_non_important", (o == null ? 0 : o) + c); }
        return c;
    }

    private static final int MAX_DEPTH = 80; // recursion cap for tree walks (webview trees can be deep)

    /** True when a proper ancestor (up to 40 levels) is a WebView. */
    private static boolean underWebView(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n.getParent();
        for (int i = 0; cur != null && i < 40; i++) {
            if (cls(cur).endsWith("WebView")) return true;
            cur = cur.getParent();
        }
        return false;
    }

    private static AccessibilityNodeInfo rootOf(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; i < 100; i++) {
            AccessibilityNodeInfo p = cur.getParent();
            if (p == null) break;
            cur = p;
        }
        return cur;
    }

    private static final int MAX_NODES = 1500; // bound the per-request tree walk (1 binder call per node)

    private static void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out) {
        collect(n, out, 0);
    }

    private static void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > MAX_DEPTH || out.size() >= MAX_NODES || Thread.currentThread().isInterrupted()) return;
        if (usable(n)) out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out, depth + 1);
    }

    /** Top-to-bottom, then left-to-right. */
    private static final Comparator<AccessibilityNodeInfo> READING_ORDER = new Comparator<AccessibilityNodeInfo>() {
        @Override
        public int compare(AccessibilityNodeInfo a, AccessibilityNodeInfo b) {
            Rect ra = bounds(a), rb = bounds(b);
            if (ra.top != rb.top) return ra.top < rb.top ? -1 : 1;
            if (ra.left != rb.left) return ra.left < rb.left ? -1 : 1;
            return 0;
        }
    };

    private static JSONObject nodeJson(AccessibilityNodeInfo n) {
        if (n == null) return null;
        JSONObject j = new JSONObject();
        try {
            Rect b = bounds(n);
            j.put("text", n.getText() == null ? JSONObject.NULL : n.getText().toString());
            j.put("contentDescription", n.getContentDescription() == null
                    ? JSONObject.NULL : n.getContentDescription().toString());
            j.put("class", n.getClassName() == null ? JSONObject.NULL : n.getClassName().toString());
            j.put("package", n.getPackageName() == null ? JSONObject.NULL : n.getPackageName().toString());
            j.put("viewId", n.getViewIdResourceName() == null ? JSONObject.NULL : n.getViewIdResourceName());
            j.put("bounds", new JSONArray().put(b.left).put(b.top).put(b.right).put(b.bottom));
            j.put("clickable", n.isClickable());
            j.put("a11yFocused", n.isAccessibilityFocused());
            JSONObject st = new JSONObject();
            st.put("checked", n.isChecked());
            st.put("selected", n.isSelected());
            st.put("enabled", n.isEnabled());
            st.put("focusable", n.isFocusable());
            j.put("states", st);
            if (Build.VERSION.SDK_INT >= 28) j.put("headingFlag", n.isHeading());
            String ct = childText(n);
            if (ct.length() > 0) j.put("childText", ct);
            boolean canExp = false, canCol = false;
            for (AccessibilityAction a : n.getActionList()) {
                if (a.getId() == AccessibilityNodeInfo.ACTION_EXPAND) canExp = true;
                else if (a.getId() == AccessibilityNodeInfo.ACTION_COLLAPSE) canCol = true;
            }
            if (canCol) { j.put("expandable", true); j.put("expanded", true); }
            else if (canExp) { j.put("expandable", true); j.put("expanded", false); }
            CharSequence rd = n.getExtras() == null ? null
                    : n.getExtras().getCharSequence("AccessibilityNodeInfo.roleDescription");
            if (rd != null && rd.length() > 0) j.put("roleDescription", rd.toString());
            if (Build.VERSION.SDK_INT >= 30) {
                CharSequence sd = n.getStateDescription();
                if (sd != null && sd.length() > 0) j.put("stateDescription", sd.toString());
            }
            if (Build.VERSION.SDK_INT >= 28) {
                CharSequence pt = n.getPaneTitle();
                if (pt != null && pt.length() > 0) j.put("paneTitle", pt.toString());
            }
            if (inWebView(n)) {
                j.put("webview", true);
                if (Build.VERSION.SDK_INT >= 28 && n.isHeading()) j.put("heading", true);
                int links = 0;
                CharSequence tx = n.getText();
                if (tx instanceof Spanned) {
                    Spanned sp = (Spanned) tx;
                    links = sp.getSpans(0, sp.length(), ClickableSpan.class).length;
                }
                if (links > 0) j.put("links", links);
                if (cls(n).contains("Link")) j.put("link", true);
                if (rd != null && rd.length() > 0) j.put("landmark", rd.toString());
                else if (Build.VERSION.SDK_INT >= 28 && n.getPaneTitle() != null && n.getPaneTitle().length() > 0) {
                    j.put("landmark", n.getPaneTitle().toString());
                }
            }
            else if (inWebContent(n)) j.put("inWebContent", true);
            if (isEditable(n)) {
                CharSequence er = n.getError();
                if (er != null && er.length() > 0) j.put("error", er.toString());
            }
        } catch (Exception ignored) {}
        return j;
    }

    /**
     * Best-effort hint for web content rendered as plain View/TextView: node is android.view.View/TextView and an
     * ancestor within 6 levels is a WebView, or a node/ancestor pane title contains 'web'.
     */
    private static boolean inWebContent(AccessibilityNodeInfo n) {
        String c = cls(n);
        if (!c.equals("android.view.View") && !c.equals("android.widget.TextView")) return false;
        AccessibilityNodeInfo cur = n;
        for (int i = 0; cur != null && i <= 6; i++) {
            if (i > 0 && cls(cur).contains("WebView")) return true;
            if (Build.VERSION.SDK_INT >= 28) {
                CharSequence pt = cur.getPaneTitle();
                if (pt != null && pt.toString().toLowerCase(Locale.ROOT).contains("web")) return true;
            }
            cur = cur.getParent();
        }
        return false;
    }

    /** True when the node's class contains WebView or one of its ancestors (up to 12 levels) does. */
    private static boolean inWebView(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; cur != null && i < 12; i++) {
            if (cls(cur).contains("WebView")) return true;
            cur = cur.getParent();
        }
        return false;
    }

    /** Text/contentDescription of descendants (depth <= 3), joined by " | ", max 200 chars. */
    private static String childText(AccessibilityNodeInfo n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.getChildCount() && sb.length() < 200; i++) {
            childText(n.getChild(i), 1, sb);
        }
        return sb.length() > 200 ? sb.substring(0, 200) : sb.toString();
    }

    private static void childText(AccessibilityNodeInfo c, int depth, StringBuilder sb) {
        if (c == null || depth > 3 || sb.length() >= 200) return;
        CharSequence t = c.getText();
        if (t == null || t.length() == 0) t = c.getContentDescription();
        if (t != null && t.length() > 0) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(t);
        }
        for (int i = 0; i < c.getChildCount(); i++) childText(c.getChild(i), depth + 1, sb);
    }

    // ---------------------------------------------------------------- node kinds

    static boolean isHeading(AccessibilityNodeInfo n) {
        return Build.VERSION.SDK_INT >= 28 && n.isHeading();
    }

    private static String cls(AccessibilityNodeInfo n) {
        return n.getClassName() == null ? "" : n.getClassName().toString();
    }

    private static boolean isEditable(AccessibilityNodeInfo n) {
        return n.isEditable() || cls(n).contains("EditText");
    }

    private static boolean isLink(AccessibilityNodeInfo n) {
        if (cls(n).contains("Link")) return true;
        CharSequence t = n.getText();
        if (t instanceof Spanned) {
            Spanned s = (Spanned) t;
            return s.getSpans(0, s.length(), ClickableSpan.class).length > 0;
        }
        return false;
    }

    private static boolean isButton(AccessibilityNodeInfo n) {
        String c = cls(n);
        if (c.contains("RadioButton")) return false;
        if (c.contains("Button")) return true;
        if (!n.isClickable() || n.isCheckable() || isEditable(n) || c.contains("Switch") || c.contains("CheckBox")) {
            return false;
        }
        Bundle ex = n.getExtras();
        CharSequence role = ex == null ? null : ex.getCharSequence("AccessibilityNodeInfo.roleDescription");
        if (role != null && role.toString().toLowerCase(Locale.ROOT).contains("button")) return true;
        // clickable leaf with a label (image/text/view button without a Button class)
        return n.getChildCount() == 0 && (c.endsWith("ImageView") || c.endsWith("TextView") || c.equals("android.view.View"));
    }

    private static boolean isControl(AccessibilityNodeInfo n) {
        return n.isClickable() || n.isCheckable() || isEditable(n);
    }

    private static boolean matchesKind(AccessibilityNodeInfo n, String kind) {
        switch (kind) {
            case "heading": return isHeading(n);
            case "button": return isButton(n);
            case "link": return isLink(n);
            case "control": return isControl(n);
            default: return true;
        }
    }

    private static JSONObject ok() {
        JSONObject r = new JSONObject();
        try { r.put("success", true); } catch (Exception ignored) {}
        return r;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Try ACTION_ACCESSIBILITY_FOCUS on the node, then on its ancestors. */
    private static boolean focusNodeOrAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; cur != null && i < 6; i++) {
            if (i == 0 && cur.isAccessibilityFocused()) return true;
            if (cur.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)) return true;
            cur = cur.getParent();
        }
        return false;
    }

    private JSONObject focusResult(boolean performed, JSONObject extra) throws Exception {
        return focusResult(performed, extra, null);
    }

    /** focusTarget (optional): the node we asked to focus; response `held` says whether focus really sits on/around it. */
    private JSONObject focusResult(boolean performed, JSONObject extra, AccessibilityNodeInfo focusTarget) throws Exception {
        sleep(SETTLE_MS);
        JSONObject r = ok();
        r.put("performed", performed);
        AccessibilityNodeInfo now = findA11yFocus();
        r.put("node", nodeJson(now));
        r.put("held", performed && now != null && (focusTarget == null
                || isAncestorOrSelf(now, focusTarget) || isAncestorOrSelf(focusTarget, now)));
        if (extra != null) {
            java.util.Iterator<String> it = extra.keys();
            while (it.hasNext()) {
                String k = it.next();
                r.put(k, extra.get(k));
            }
        }
        return r;
    }

    // ---------------------------------------------------------------- 1. focus

    JSONObject current() throws Exception {
        AccessibilityNodeInfo f = findA11yFocus();
        if (f == null) return Server.error("no accessibility focus");
        JSONObject r = ok();
        r.put("node", nodeJson(f));
        return r;
    }

    JSONObject step(boolean next) throws Exception {
        AccessibilityNodeInfo cur = findA11yFocus();
        JSONObject info = new JSONObject();
        info.put("previous", nodeJson(cur));
        if (swipeMode) {
            if (!talkBackOn()) return Server.error("mode=swipe needs TalkBack on (use mode=tbx instead)");
            JSONObject p = new JSONObject();
            p.put("dir", next ? "right" : "left");
            JSONObject r = swipe(p);
            r.put("mode", "swipe");
            r.put("previous", info.get("previous"));
            return r;
        }

        // a) the framework traversal chain (only set when the app declares traversal order)
        if (cur != null) {
            AccessibilityNodeInfo n = cur;
            for (int i = 0; i < 30 && !Thread.currentThread().isInterrupted(); i++) {
                n = next ? n.getTraversalAfter() : n.getTraversalBefore();
                if (n == null) break;
                if (usable(n) && !n.equals(cur) && n.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)) {
                    info.put("method", "traversal");
                    return focusResult(true, info);
                }
            }
        }

        // b) fallback: reading order over visible, important nodes
        List<AccessibilityNodeInfo> list = new ArrayList<>();
        AccessibilityNodeInfo sr = cur != null ? rootOf(cur) : svc.getRootInActiveWindow();
        if (tb) {
            if (sr != null) list = tbList(sr);
            info.put("skipped_reasons", reasonsJson());
        } else {
            if (sr != null) collect(sr, list);
            Collections.sort(list, READING_ORDER);
        }
        info.put("added_non_important", countAdded(list));
        info.put("skipped_reasons", reasonsJson());
        info.put("mode", modeName());
        info.put("method", "reading_order");
        info.put("candidates", list.size());
        if (list.isEmpty()) {
            info.put("error", "no visible important nodes");
            return focusResult(false, info);
        }

        int start; // first index to try
        if (cur == null) {
            start = next ? 0 : list.size() - 1;
        } else {
            int idx = -1;
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).equals(cur)) { idx = i; break; }
            }
            if (idx < 0) { // focused node not in list (e.g. not "important"): use position
                Rect cb = bounds(cur);
                idx = next ? -1 : list.size();
                for (int i = 0; i < list.size(); i++) {
                    Rect b = bounds(list.get(i));
                    boolean after = b.top > cb.top || (b.top == cb.top && b.left > cb.left);
                    if (next && after) { idx = i - 1; break; }
                    if (!next && after) { idx = i; break; }
                }
            }
            start = next ? idx + 1 : idx - 1;
        }
        int step = next ? 1 : -1;
        for (int i = start, tries = 0; i >= 0 && i < list.size() && tries < 8; i += step, tries++) {
            AccessibilityNodeInfo cand = list.get(i);
            if (cand.equals(cur)) continue;
            if (cand.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)) {
                return focusResult(true, info);
            }
        }
        info.put("error", "no further node in that direction (or focus action refused)");
        return focusResult(false, info);
    }

    // ---------------------------------------------------------------- 1b. first/last, kind jumps

    private List<AccessibilityNodeInfo> readingList(AccessibilityNodeInfo cur) {
        List<AccessibilityNodeInfo> list = new ArrayList<>();
        AccessibilityNodeInfo r = svc.getRootInActiveWindow();
        if (r == null && cur != null) r = rootOf(cur);
        if (tb) return r == null ? list : tbList(r);
        if (r != null) collect(r, list);
        Collections.sort(list, READING_ORDER);
        return list;
    }

    /** Index of the first node to try when moving from cur in the given direction. */
    private static int startIndex(List<AccessibilityNodeInfo> list, AccessibilityNodeInfo cur, boolean next) {
        if (cur == null) return next ? 0 : list.size() - 1;
        int idx = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).equals(cur)) { idx = i; break; }
        }
        if (idx < 0) {
            Rect cb = bounds(cur);
            idx = next ? -1 : list.size();
            for (int i = 0; i < list.size(); i++) {
                Rect b = bounds(list.get(i));
                boolean after = b.top > cb.top || (b.top == cb.top && b.left > cb.left);
                if (next && after) { idx = i - 1; break; }
                if (!next && after) { idx = i; break; }
            }
        }
        return next ? idx + 1 : idx - 1;
    }

    /** Drop pure layout containers (no label, not interactive) so first/last/sweep land on real content. */
    private List<AccessibilityNodeInfo> meaningfulOnly(List<AccessibilityNodeInfo> in) {
        if (tb) return in; // already filtered by collectTb
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        for (AccessibilityNodeInfo n : in) {
            boolean label = (n.getText() != null && n.getText().length() > 0)
                    || (n.getContentDescription() != null && n.getContentDescription().length() > 0);
            boolean inter = n.isClickable() || n.isCheckable() || n.isFocusable() || n.isLongClickable()
                    || isEditable(n) || isHeading(n);
            boolean leaf = n.getChildCount() == 0;
            if (label || inter || (leaf && cls(n).endsWith("ImageView"))) out.add(n);
        }
        return out;
    }

    JSONObject edge(boolean first) throws Exception {
        List<AccessibilityNodeInfo> list = meaningfulOnly(readingList(findA11yFocus()));
        JSONObject info = new JSONObject();
        info.put("method", first ? "first" : "last");
        info.put("mode", modeName());
        info.put("candidates", list.size());
        info.put("added_non_important", countAdded(list));
        if (list.isEmpty()) return Server.error("no visible important nodes");
        int step = first ? 1 : -1;
        for (int i = first ? 0 : list.size() - 1, tries = 0; i >= 0 && i < list.size() && tries < 8; i += step, tries++) {
            if (list.get(i).isAccessibilityFocused() || list.get(i).performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)) {
                return focusResult(true, info);
            }
        }
        return Server.error("focus action refused");
    }

    JSONObject stepKind(boolean next, String kind) throws Exception {
        AccessibilityNodeInfo cur = findA11yFocus();
        List<AccessibilityNodeInfo> list = readingList(cur);
        JSONObject info = new JSONObject();
        info.put("previous", nodeJson(cur));
        info.put("kind", kind);
        info.put("method", "kind_scan");
        info.put("mode", modeName());
        info.put("candidates", list.size());
        int step = next ? 1 : -1;
        int refused = 0;
        for (int i = startIndex(list, cur, next); i >= 0 && i < list.size(); i += step) {
            if (Thread.currentThread().isInterrupted()) break;
            AccessibilityNodeInfo cand = list.get(i);
            if (cur != null && cand.equals(cur)) continue;
            if (!matchesKind(cand, kind)) continue;
            if (cand.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)) {
                return focusResult(true, info);
            }
            if (++refused >= 8) break;
        }
        JSONObject e = Server.error("none found");
        e.put("kind", kind);
        e.put("candidates", list.size());
        return e;
    }

    // ---------------------------------------------------------------- 1c. sweep

    static volatile JSONObject lastSweep;
    static volatile boolean sweeping;
    static volatile boolean sweepStop;

    private static String nodeKey(AccessibilityNodeInfo n) {
        Rect b = bounds(n);
        return b.flattenToString() + "|" + cls(n) + "|" + n.getViewIdResourceName() + "|" + n.getText()
                + "|" + n.getContentDescription();
    }

    static boolean isTrue(JSONObject p, String k) {
        String v = p.optString(k, "false").toLowerCase(Locale.ROOT);
        return v.equals("true") || v.equals("1");
    }

    /** Scroll-stable identity of a node (no bounds, they change when scrolling). */
    private static String scrollKey(AccessibilityNodeInfo n) {
        return n.getViewIdResourceName() + "|" + cls(n) + "|" + n.getText() + "|" + n.getContentDescription()
                + "|" + childText(n);
    }

    /** Nearest ancestor (or self) that reports isScrollable(). */
    private static AccessibilityNodeInfo scrollableAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; cur != null && i < 40; i++) {
            if (cur.isScrollable()) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    private static AccessibilityNodeInfo firstScrollable(AccessibilityNodeInfo n, int[] budget) {
        if (n == null || ++budget[0] > MAX_NODES || Thread.currentThread().isInterrupted()) return null;
        if (n.isScrollable() && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = firstScrollable(n.getChild(i), budget);
            if (f != null) return f;
        }
        return null;
    }

    /** First visible node that can scroll in the given direction. */
    private static AccessibilityNodeInfo firstScrollable(AccessibilityNodeInfo n, int[] budget, boolean forward) {
        if (n == null || ++budget[0] > MAX_NODES || Thread.currentThread().isInterrupted()) return null;
        if (n.isVisibleToUser() && canScroll(n, forward)) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = firstScrollable(n.getChild(i), budget, forward);
            if (f != null) return f;
        }
        return null;
    }

    private static boolean canScroll(AccessibilityNodeInfo n, boolean forward) {
        if (!n.isScrollable()) return false;
        int id = forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        for (AccessibilityAction a : n.getActionList()) if (a.getId() == id) return true;
        return false;
    }

    /** POST /scroll {dir:'forward'|'backward'|'top', times:n}. */
    JSONObject scroll(JSONObject p) throws Exception {
        String dir = p.optString("dir", "forward").toLowerCase(Locale.ROOT);
        if (!dir.equals("forward") && !dir.equals("backward") && !dir.equals("top")) {
            return Server.error("bad dir: " + dir + " (forward|backward|top)");
        }
        boolean top = dir.equals("top");
        boolean fwd = dir.equals("forward");
        int times = top ? 20 : Math.max(1, Math.min(20, p.optInt("times", 1)));
        AccessibilityNodeInfo f = findA11yFocus();
        boolean wantFwd = fwd && !top;
        AccessibilityNodeInfo sc = null;
        String via = "focus_ancestor";
        AccessibilityNodeInfo cur = f;
        for (int i = 0; cur != null && i < 40; i++) { // nearest ancestor that can move in this direction
            if (canScroll(cur, wantFwd)) { sc = cur; break; }
            cur = cur.getParent();
        }
        if (sc == null) {
            AccessibilityNodeInfo rt = svc.getRootInActiveWindow();
            sc = rt == null ? null : firstScrollable(rt, new int[]{0}, wantFwd);
            via = "first_scrollable";
            if (sc == null) sc = rt == null ? null : firstScrollable(rt, new int[]{0}, !wantFwd); // at an edge
        }
        if (sc == null) return Server.error("no scrollable node");
        int done = 0;
        boolean atEdge = false;
        String sig = signature();
        int same = 0;
        for (int k = 0; k < times && !Thread.currentThread().isInterrupted(); k++) {
            sc.refresh();
            if (!canScroll(sc, fwd && !top)) { atEdge = true; break; }
            if (!sc.performAction(fwd && !top ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) { atEdge = true; break; }
            done++;
            sleep(500);
            if (top) {
                String s2 = signature();
                if (s2.equals(sig)) {
                    if (++same >= 2) { atEdge = true; break; } // unchanged twice in a row
                } else {
                    same = 0;
                }
                sig = s2;
            }
        }
        JSONObject r = ok();
        r.put("dir", dir);
        r.put("requested", times);
        r.put("scroll_actions", done);
        r.put("at_edge", atEdge);
        r.put("via", via);
        List<AccessibilityNodeInfo> l = meaningfulOnly(readingList(findA11yFocus()));
        r.put("first_visible", l.isEmpty() ? JSONObject.NULL : nodeJson(l.get(0)));
        return r;
    }

    /** Budget the Server should allow for a sweep with these params (capped at 60s). */
    static long sweepBudgetMs(JSONObject p) {
        long delay = Math.max(0, p.optLong("delay_ms", 150));
        long max = Math.max(1, p.optLong("max", 200));
        long need = max * (delay + 120) + 3000;
        if (isTrue(p, "scroll")) {
            long pages = Math.max(1, p.optLong("max_pages", 8));
            need += pages * (Math.max(0, Math.min(5000, p.optLong("scroll_wait_ms", 700))) + 600);
        }
        return Math.max(8000, Math.min(60000, need));
    }

    /** Walk accessibility focus through the reading order. Must finish within maxMs (then stopped=timeout). */
    JSONObject sweep(JSONObject p, long maxMs) throws Exception {
        final long t0 = SystemClock.elapsedRealtime();
        final long wall0 = System.currentTimeMillis();
        long delay = Math.max(0, p.optLong("delay_ms", 150));
        int max = Math.max(1, p.optInt("max", 200));
        String actStr = p.optString("actions", "true").toLowerCase(Locale.ROOT);
        boolean withActions = !(actStr.equals("false") || actStr.equals("0"));
        boolean fromCurrent = "current".equalsIgnoreCase(p.optString("from", "first"));

        sweeping = true;
        sweepStop = false;
        try {
            String dph = Guard.scan(svc); // hack-detect before the first step
            if (dph != null) return Guard.trip(dph).put("visited", 0);
            int seqSeen = HelperService.winSeq, stepNo = 0;
            boolean scroll = isTrue(p, "scroll");
            int maxPages = Math.max(1, p.optInt("max_pages", 8));
            long scrollWait = Math.max(0, Math.min(5000, p.optLong("scroll_wait_ms", 700)));
            AccessibilityNodeInfo cur = findA11yFocus();
            List<AccessibilityNodeInfo> list = meaningfulOnly(readingList(cur));
            int addedCands = countAdded(list), addedVisited = 0;
            JSONArray nodes = new JSONArray();
            JSONArray pageCounts = new JSONArray();
            String stopped = "end";
            int skipped = 0, dups = 0, page = 0, scrollActions = 0, totalCands = 0;
            AccessibilityNodeInfo lastNode = null;
            Set<String> seen = new HashSet<>();

            while (true) {
                totalCands += list.size();
                int i = 0;
                boolean startedOnCurrent = false;
                if (page == 0 && fromCurrent && cur != null) {
                    i = startIndex(list, cur, true);
                    // include the current node itself as node 0 when it is part of the list
                    for (int k = 0; k < list.size(); k++) {
                        if (list.get(k).equals(cur)) { i = k; startedOnCurrent = true; break; }
                    }
                }
                int newInPage = 0;
                java.util.Map<String, Integer> occ = new java.util.HashMap<>();
                for (; i < list.size(); i++) {
                    if (Thread.currentThread().isInterrupted() || sweepStop) { stopped = "timeout"; break; }
                    if (SystemClock.elapsedRealtime() - t0 > maxMs) { stopped = "timeout"; break; }
                    if (nodes.length() >= max) { stopped = "max"; break; }
                    AccessibilityNodeInfo cand = list.get(i);
                    // hack-detect: full window scan on window change or every 4th step (cost cap), plus own text each step
                    if (stepNo++ % 4 == 0 || HelperService.winSeq != seqSeen) {
                        seqSeen = HelperService.winSeq;
                        dph = Guard.scan(svc);
                        if (dph != null) return abortDanger(dph, nodes.length());
                    }
                    String skey = null;
                    if (scroll) { // scroll-stable identity: viewId+class+text+desc+childText + occurrence on this page
                        String base = scrollKey(cand);
                        Integer o = occ.get(base);
                        o = o == null ? 1 : o + 1;
                        occ.put(base, o);
                        skey = base + "@" + o;
                        if (!seen.add(skey)) continue; // already visited on an earlier page
                    }
                    boolean already = startedOnCurrent && nodes.length() == 0 && cand.equals(cur);
                    if (!already) {
                        if (!cand.refresh() || !cand.isVisibleToUser()) { skipped++; skip("not_visible"); continue; }
                        if (!cand.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)) {
                            // false is expected when the node already holds accessibility focus
                            cand.refresh();
                            if (!cand.isAccessibilityFocused()) { skipped++; skip("focus_refused"); continue; }
                        }
                    }
                    long applied = SystemClock.elapsedRealtime();
                    cand.refresh();
                    dph = Guard.matchDanger(cand.getText());
                    if (dph == null) dph = Guard.matchDanger(cand.getContentDescription());
                    if (dph != null) return abortDanger(dph, nodes.length());
                    if (!scroll) {
                        String key = nodeKey(cand);
                        if (!seen.add(key)) {
                            if (++dups >= 3) { stopped = "repeat"; break; }
                            skipped++;
                            skip("duplicate");
                            continue;
                        }
                    }
                    JSONObject j = nodeJson(cand);
                    j.put("i", nodes.length());
                    j.put("headingFlag", isHeading(cand));
                    j.put("checkable", cand.isCheckable());
                    j.put("checked", cand.isChecked());
                    if (addedNonImportant(cand)) { j.put("nonImportant", true); addedVisited++; }
                    if (scroll) {
                        j.put("page", page);
                        if (page > 0) j.put("scrolled", true);
                    }
                    if (withActions) {
                        JSONArray acts = new JSONArray();
                        for (AccessibilityAction a : cand.getActionList()) {
                            if (!isCustomAction(a.getId())) continue;
                            JSONObject aj = new JSONObject();
                            aj.put("id", a.getId());
                            aj.put("label", a.getLabel() == null ? JSONObject.NULL : a.getLabel().toString());
                            acts.put(aj);
                        }
                        j.put("actions", acts);
                    }
                    j.put("t_ms", applied - t0);
                    j.put("t_epoch_ms", wall0 + (applied - t0));
                    nodes.put(j);
                    newInPage++;
                    lastNode = cand;
                    long wait = delay - (SystemClock.elapsedRealtime() - applied);
                    if (wait > 0 && i + 1 < list.size()) sleep(wait);
                }
                pageCounts.put(newInPage);
                if (!stopped.equals("end") || !scroll) break;
                if (page > 0 && newInPage == 0) { stopped = "end"; break; } // nothing new after scrolling
                if (page + 1 >= maxPages) { stopped = "max_pages"; break; }
                if (SystemClock.elapsedRealtime() - t0 > maxMs) { stopped = "timeout"; break; }
                // nearest ancestor of the last visited node that can scroll forward, else any such node in the window
                AccessibilityNodeInfo sc = null;
                AccessibilityNodeInfo anc = lastNode;
                boolean anyScrollable = false;
                for (int k = 0; anc != null && k < 40; k++) {
                    if (anc.isScrollable()) anyScrollable = true;
                    if (canScroll(anc, true)) { sc = anc; break; }
                    anc = anc.getParent();
                }
                if (sc == null) {
                    AccessibilityNodeInfo rt = svc.getRootInActiveWindow();
                    if (rt != null) {
                        sc = firstScrollable(rt, new int[]{0}, true);
                        if (sc == null && !anyScrollable) anyScrollable = firstScrollable(rt, new int[]{0}) != null;
                    }
                }
                if (sc == null) { stopped = anyScrollable ? "end" : "no_scroll"; break; }
                if (!sc.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    stopped = "end";
                    break;
                }
                scrollActions++;
                for (long w = 0; w < scrollWait && !sweepStop && !Thread.currentThread().isInterrupted(); w += 50) {
                    sleep(Math.min(50, scrollWait - w));
                }
                page++;
                list = meaningfulOnly(readingList(findA11yFocus()));
                addedCands += countAdded(list);
            }
            // leave focus on the last visited node
            if (lastNode != null && lastNode.refresh() && !lastNode.isAccessibilityFocused()) {
                lastNode.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
            }
            JSONObject r = ok();
            r.put("count", nodes.length());
            r.put("elapsed_ms", SystemClock.elapsedRealtime() - t0);
            r.put("nodes", nodes);
            r.put("stopped", stopped);
            r.put("skipped", skipped);
            r.put("mode", modeName());
            r.put("skipped_reasons", reasonsJson());
            r.put("added_non_important", addedVisited);
            r.put("added_non_important_candidates", addedCands);
            r.put("candidates", totalCands);
            r.put("scroll", scroll);
            r.put("pages", page + 1);
            r.put("page_counts", pageCounts);
            r.put("scroll_actions", scrollActions);
            r.put("from", fromCurrent ? "current" : "first");
            r.put("delay_ms", delay);
            lastSweep = r;
            return r;
        } finally {
            sweeping = false;
        }
    }

    // ---------------------------------------------------------------- 1d. inspect (diagnostics: why TalkBack skips nodes)

    private static final String[] WTYPE = {"?", "APPLICATION", "INPUT_METHOD", "SYSTEM", "ACCESSIBILITY_OVERLAY", "SPLIT_SCREEN_DIVIDER", "MAGNIFICATION_OVERLAY"};

    private static String s(CharSequence c) {
        return c == null ? null : c.toString();
    }

    private static Object nz(Object o) {
        return o == null ? JSONObject.NULL : o;
    }

    private void inspectWalk(AccessibilityNodeInfo n, int depth, int parent, int speaking, JSONObject win,
                             boolean activeRoot, List<AccessibilityNodeInfo> nodes, JSONArray out) throws Exception {
        if (n == null || depth > MAX_DEPTH || nodes.size() >= MAX_NODES || Thread.currentThread().isInterrupted()) return;
        int me = nodes.size();
        nodes.add(n);
        JSONObject j = new JSONObject();
        j.put("i", me);
        j.put("depth", depth);
        j.put("parentIndex", parent < 0 ? JSONObject.NULL : parent);
        j.put("text", nz(s(n.getText())));
        j.put("contentDescription", nz(s(n.getContentDescription())));
        j.put("class", nz(s(n.getClassName())));
        j.put("viewId", nz(n.getViewIdResourceName()));
        Rect b = bounds(n);
        j.put("bounds", new JSONArray().put(b.left).put(b.top).put(b.right).put(b.bottom));
        j.put("window", win);
        boolean important = n.isImportantForAccessibility();
        boolean foc = n.isFocusable();
        boolean srf = Build.VERSION.SDK_INT >= 28 && n.isScreenReaderFocusable();
        boolean clk = n.isClickable();
        j.put("importantForAccessibility", important);
        j.put("visibleToUser", n.isVisibleToUser());
        j.put("focusable", foc);
        j.put("screenReaderFocusable", Build.VERSION.SDK_INT >= 28 ? (Object) srf : JSONObject.NULL);
        j.put("accessibilityFocusable", foc || srf);
        j.put("clickable", clk);
        j.put("longClickable", n.isLongClickable());
        j.put("enabled", n.isEnabled());
        j.put("contentInvalid", n.isContentInvalid());
        String ct = childText(n);
        boolean hasText = has(n.getText()) || has(n.getContentDescription());
        j.put("hasText", hasText);
        j.put("childText", ct.length() > 0 ? ct : JSONObject.NULL);
        if (Build.VERSION.SDK_INT >= 28) j.put("paneTitle", nz(s(n.getPaneTitle())));
        j.put("isAccessibilityFocused", n.isAccessibilityFocused());
        JSONArray acts = new JSONArray();
        for (AccessibilityAction a : n.getActionList()) acts.put(a.getId());
        j.put("actionIds", acts);
        j.put("speakingAncestor", speaking < 0 ? JSONObject.NULL : speaking);
        j.put("hideDescendantsHint", JSONObject.NULL); // not readable from AccessibilityNodeInfo
        j.put("plainCandidate", activeRoot && usable(n) && (hasText || clk || foc || n.isCheckable()
                || n.isLongClickable() || isEditable(n) || isHeading(n)
                || (n.getChildCount() == 0 && cls(n).endsWith("ImageView"))));
        j.put("_tb", n.getTraversalBefore() != null);
        j.put("_ta", n.getTraversalAfter() != null);
        out.put(j);
        int childSpeaking = speaking;
        if ((clk || foc || srf) && (hasText || ct.length() > 0)) childSpeaking = me;
        for (int i = 0; i < n.getChildCount(); i++) {
            inspectWalk(n.getChild(i), depth + 1, me, childSpeaking, win, activeRoot, nodes, out);
        }
    }

    JSONObject inspect(JSONObject p) throws Exception {
        JSONArray out = new JSONArray();
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        JSONArray wl = new JSONArray();
        AccessibilityNodeInfo active = svc.getRootInActiveWindow();
        List<AccessibilityWindowInfo> wins = svc.getWindows();
        if (wins != null) {
            for (AccessibilityWindowInfo w : wins) {
                AccessibilityNodeInfo r = w.getRoot();
                JSONObject wj = new JSONObject();
                wj.put("id", w.getId());
                int t = w.getType();
                wj.put("type", t >= 0 && t < WTYPE.length ? WTYPE[t] : String.valueOf(t));
                wj.put("layer", w.getLayer());
                wj.put("title", nz(s(w.getTitle())));
                wj.put("focused", w.isFocused());
                wj.put("active", w.isActive());
                wj.put("pkg", r == null ? JSONObject.NULL : nz(s(r.getPackageName())));
                wj.put("hasRoot", r != null);
                wl.put(wj);
                if (r != null) {
                    inspectWalk(r, 0, -1, -1, wj, active != null && r.equals(active), nodes, out);
                }
            }
        } else if (active != null) {
            JSONObject wj = new JSONObject();
            wj.put("id", -1);
            wj.put("pkg", nz(s(active.getPackageName())));
            inspectWalk(active, 0, -1, -1, wj, true, nodes, out);
        }
        // traversal before/after as indexes (null when not set or target not in the walked tree)
        for (int i = 0; i < out.length(); i++) {
            JSONObject j = out.getJSONObject(i);
            AccessibilityNodeInfo n = nodes.get(i);
            Object tb2 = JSONObject.NULL, ta = JSONObject.NULL;
            if (j.optBoolean("_tb")) {
                int k = nodes.indexOf(n.getTraversalBefore());
                tb2 = k >= 0 ? (Object) k : "unlisted";
            }
            if (j.optBoolean("_ta")) {
                int k = nodes.indexOf(n.getTraversalAfter());
                ta = k >= 0 ? (Object) k : "unlisted";
            }
            j.remove("_tb");
            j.remove("_ta");
            j.put("traversalBefore", tb2);
            j.put("traversalAfter", ta);
        }
        int max = Math.max(1, p.optInt("max", MAX_NODES));
        JSONArray cut = new JSONArray();
        for (int i = 0; i < out.length() && i < max; i++) cut.put(out.get(i));
        JSONObject r = ok();
        r.put("count", out.length());
        r.put("returned", cut.length());
        r.put("nodes", cut);
        r.put("windows", wl);
        android.accessibilityservice.AccessibilityServiceInfo si = svc.getServiceInfo();
        int fl = si == null ? 0 : si.flags;
        JSONObject sj = new JSONObject();
        sj.put("flags", fl);
        sj.put("FLAG_INCLUDE_NOT_IMPORTANT_VIEWS", (fl & android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS) != 0);
        sj.put("FLAG_RETRIEVE_INTERACTIVE_WINDOWS", (fl & android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) != 0);
        sj.put("FLAG_REPORT_VIEW_IDS", (fl & android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS) != 0);
        sj.put("FLAG_REQUEST_TOUCH_EXPLORATION_MODE", (fl & android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0);
        r.put("serviceInfo", sj);
        return r;
    }

    // ---------------------------------------------------------------- 3b. activate + read back

    private String signature() {
        AccessibilityNodeInfo r = svc.getRootInActiveWindow();
        StringBuilder sb = new StringBuilder();
        if (r != null) {
            sb.append(r.getPackageName()).append('|');
            List<AccessibilityNodeInfo> l = new ArrayList<>();
            collect(r, l);
            int cnt = 0;
            for (AccessibilityNodeInfo n : l) {
                if (++cnt > 60) break;
                sb.append(n.getText()).append('/').append(n.getContentDescription()).append('/')
                        .append(n.isChecked()).append(n.isSelected()).append(bounds(n).flattenToString()).append(';');
            }
        }
        return Integer.toHexString(sb.toString().hashCode()) + ":" + sb.length();
    }

    JSONObject activateRead(JSONObject p) throws Exception {
        long settle = Math.max(0, Math.min(5000, p.optLong("settle_ms", 600)));
        int seqBefore = HelperService.winSeq;
        String sigBefore = signature();
        JSONObject a = activate();
        if (!a.optBoolean("success")) return a;
        long start = SystemClock.elapsedRealtime();
        boolean changed = false;
        while (SystemClock.elapsedRealtime() - start < settle && !Thread.currentThread().isInterrupted()) {
            sleep(80);
            if (HelperService.winSeq != seqBefore || !signature().equals(sigBefore)) {
                changed = true;
                sleep(150); // let the new content finish laying out
                break;
            }
        }
        JSONObject r = ok();
        r.put("clicked", a.opt("clicked"));
        r.put("focused", a.opt("focused"));
        r.put("clickedAncestor", a.opt("clickedAncestor"));
        r.put("performed", a.opt("performed"));
        r.put("changed", changed);
        r.put("waited_ms", SystemClock.elapsedRealtime() - start);
        JSONObject after = new JSONObject();
        JSONObject af = nodeJson(findA11yFocus());
        after.put("focus", af == null ? JSONObject.NULL : af);
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        String pkg = root == null || root.getPackageName() == null ? null : root.getPackageName().toString();
        after.put("package", pkg == null ? JSONObject.NULL : pkg);
        String actPkg = HelperService.lastWinPkg;
        after.put("activity", actPkg != null && actPkg.equals(pkg) && HelperService.lastWinCls != null
                ? HelperService.lastWinCls : JSONObject.NULL);
        after.put("window_events", HelperService.winSeq - seqBefore);
        r.put("after", after);
        return r;
    }

    JSONObject swipe(JSONObject params) throws Exception {
        // v0.1.13: no package-based gesture refusal (allow_gesture accepted for compatibility); the danger-phrase
        // lock is still enforced for action endpoints by the server.
        String dir = params.optString("dir", "right");
        boolean right = !"left".equalsIgnoreCase(dir);
        DisplayMetrics dm = svc.getResources().getDisplayMetrics();
        float y = dm.heightPixels * 0.6f;
        float x1 = dm.widthPixels * (right ? 0.2f : 0.8f);
        float x2 = dm.widthPixels * (right ? 0.8f : 0.2f);
        Path p = new Path();
        p.moveTo(x1, y);
        p.lineTo(x2, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 150))
                .build();
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] completed = {false};
        boolean dispatched = svc.dispatchGesture(g, new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription d) {
                completed[0] = true;
                latch.countDown();
            }

            @Override
            public void onCancelled(GestureDescription d) {
                latch.countDown();
            }
        }, null);
        if (dispatched) latch.await(3, TimeUnit.SECONDS);
        JSONObject info = new JSONObject();
        info.put("dir", right ? "right" : "left");
        info.put("dispatched", dispatched);
        info.put("completed", completed[0]);
        info.put("method", "dispatchGesture");
        sleep(300); // let TalkBack move focus
        return focusResult(dispatched && completed[0], info);
    }

    // ---------------------------------------------------------------- 2. focus/to

    JSONObject focusTo(JSONObject p) throws Exception {
        AccessibilityNodeInfo target = null;
        TextHit textHit = null;
        String mode;
        if (p.has("x") && p.has("y")) {
            mode = "xy";
            int x = (int) p.optDouble("x"), y = (int) p.optDouble("y");
            for (AccessibilityNodeInfo root : roots()) { // front-most window first
                List<AccessibilityNodeInfo> all = new ArrayList<>();
                collect(root, all);
                long best = Long.MAX_VALUE;
                for (AccessibilityNodeInfo n : all) {
                    Rect b = bounds(n);
                    if (!b.contains(x, y)) continue;
                    long area = (long) b.width() * b.height();
                    if (area <= best) { best = area; target = n; } // later = deeper on ties
                }
                if (target != null) break;
            }
        } else if (p.has("viewId")) {
            mode = "viewId";
            String id = p.optString("viewId");
            for (AccessibilityNodeInfo root : roots()) {
                for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(id)) {
                    if (n.isVisibleToUser()) { target = n; break; }
                    if (target == null) target = n;
                }
                if (target != null) break;
            }
        } else if (p.has("text")) {
            mode = "text";
            textHit = findByText(p.optString("text"), isTrue(p, "exact"), Math.max(0, p.optInt("index", 0)), false);
            target = textHit.node;
        } else {
            return Server.error("need one of: {x,y} | {text} | {viewId}");
        }
        if (target == null) {
            JSONObject e = Server.error("no node found");
            e.put("mode", mode);
            if (textHit != null) {
                e.put("matches", textHit.matches);
                e.put("lookup", textHit.how);
            }
            return e;
        }
        JSONObject info = new JSONObject();
        info.put("mode", mode);
        if (textHit != null) {
            info.put("matches", textHit.matches);
            info.put("index", textHit.index);
            info.put("lookup", textHit.how);
            info.put("matchKind", textHit.kind);
        }
        info.put("target", nodeJson(target));
        target.refresh();
        if (target.isAccessibilityFocused()) { // never re-issue: parent fallback would steal focus
            info.put("alreadyFocused", true);
            return focusResult(false, info);
        }
        return focusResult(focusNodeOrAncestor(target), info, target);
    }

    private static boolean equalsIgnoreCase(CharSequence a, String b) {
        return a != null && a.toString().trim().equalsIgnoreCase(b.trim());
    }

    /** Result of a text lookup: chosen node, total match count, chosen index, lookup path and match kind. */
    private static final class TextHit {
        AccessibilityNodeInfo node;
        int matches;
        int index;
        String how = "none";
        String kind = "none";
    }

    private static final class Cand {
        AccessibilityNodeInfo n;
        int tier; // 0 exact text/contentDescription, 1 exact childText, 2 contains
        boolean visible;
        int top;
    }

    /** 0..2 match tier of node against query (lowercase trimmed), or -1. */
    private static int textTier(AccessibilityNodeInfo n, String q) {
        CharSequence t = n.getText(), d = n.getContentDescription();
        String ts = t == null ? "" : t.toString().trim().toLowerCase(Locale.ROOT);
        String ds = d == null ? "" : d.toString().trim().toLowerCase(Locale.ROOT);
        if (ts.equals(q) || ds.equals(q)) return 0;
        String cs = "";
        if (n.getChildCount() > 0 && (n.isClickable() || n.isFocusable()
                || (Build.VERSION.SDK_INT >= 28 && n.isScreenReaderFocusable()))) {
            cs = childText(n).trim().toLowerCase(Locale.ROOT);
            if (cs.equals(q)) return 1;
        }
        if (ts.contains(q) || ds.contains(q) || cs.contains(q)) return 2;
        return -1;
    }

    private static void walkText(AccessibilityNodeInfo n, String q, int[] budget, List<Cand> out) {
        if (n == null || ++budget[0] > MAX_NODES || Thread.currentThread().isInterrupted()) return;
        int tier = textTier(n, q);
        if (tier >= 0) {
            Cand c = new Cand();
            c.n = n;
            c.tier = tier;
            c.visible = n.isVisibleToUser();
            c.top = bounds(n).top;
            out.add(c);
        }
        for (int i = 0; i < n.getChildCount(); i++) walkText(n.getChild(i), q, budget, out);
    }

    /**
     * Text lookup shared by /focus/to and /type target_text. First findAccessibilityNodeInfosByText; if that yields
     * nothing usable, a capped full walk of all windows (text, contentDescription, childText, then contains).
     * Ranking: exact tier, visible, nearest the top. exact:true drops the contains tier. editableOnly keeps only
     * visible editable nodes.
     */
    private TextHit findByText(String text, boolean exact, int index, boolean editableOnly) {
        TextHit hit = new TextHit();
        String q = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        if (q.length() == 0) return hit;
        List<Cand> cands = new ArrayList<>();
        List<AccessibilityNodeInfo> rootList = roots();
        for (AccessibilityNodeInfo root : rootList) {
            List<AccessibilityNodeInfo> res = root.findAccessibilityNodeInfosByText(text.trim());
            if (res == null) continue;
            for (AccessibilityNodeInfo n : res) {
                int tier = textTier(n, q);
                if (tier < 0) tier = 2; // API matched by its own rules
                Cand c = new Cand();
                c.n = n;
                c.tier = tier;
                c.visible = n.isVisibleToUser();
                c.top = bounds(n).top;
                cands.add(c);
            }
        }
        hit.how = "api";
        filterCands(cands, exact, editableOnly);
        if (cands.isEmpty()) {
            hit.how = "walk";
            int[] budget = {0};
            for (AccessibilityNodeInfo root : rootList) walkText(root, q, budget, cands);
            filterCands(cands, exact, editableOnly);
        }
        if (cands.isEmpty()) return hit;
        Collections.sort(cands, new Comparator<Cand>() {
            @Override
            public int compare(Cand a, Cand b) {
                if (a.tier != b.tier) return a.tier < b.tier ? -1 : 1;
                if (a.visible != b.visible) return a.visible ? -1 : 1;
                if (a.top != b.top) return a.top < b.top ? -1 : 1;
                return 0;
            }
        });
        hit.matches = cands.size();
        if (index >= cands.size()) return hit;
        Cand c = cands.get(index);
        hit.node = c.n;
        hit.index = index;
        hit.kind = c.tier == 0 ? "exact" : c.tier == 1 ? "exactChildText" : "contains";
        return hit;
    }

    private static void filterCands(List<Cand> cands, boolean exact, boolean editableOnly) {
        for (int i = cands.size() - 1; i >= 0; i--) {
            Cand c = cands.get(i);
            if ((exact && c.tier > 1) || (editableOnly && !(isEditable(c.n) && c.visible))) cands.remove(i);
        }
    }

    // ---------------------------------------------------------------- 3. activate

    /** Labels of a node: text, contentDescription, childText and each childText piece. */
    private static List<String> labelsOf(AccessibilityNodeInfo n) {
        List<String> l = new ArrayList<>();
        if (n == null) return l;
        if (has(n.getText())) l.add(n.getText().toString().trim());
        if (has(n.getContentDescription())) l.add(n.getContentDescription().toString().trim());
        String ct = childText(n).trim();
        if (ct.length() > 0) {
            l.add(ct);
            for (String piece : ct.split(" \\| ")) if (piece.trim().length() > 0) l.add(piece.trim());
        }
        return l;
    }

    private static boolean labelMatch(List<String> labels, String q, boolean exact) {
        String qq = q.trim().toLowerCase(Locale.ROOT);
        for (String s : labels) {
            String ls = s.toLowerCase(Locale.ROOT);
            if (ls.equals(qq) || (!exact && ls.contains(qq))) return true;
        }
        return false;
    }

    private static JSONObject labelSummary(AccessibilityNodeInfo n) throws Exception {
        JSONObject j = new JSONObject();
        if (n == null) return j;
        j.put("text", n.getText() == null ? JSONObject.NULL : n.getText().toString());
        j.put("contentDescription", n.getContentDescription() == null ? JSONObject.NULL : n.getContentDescription().toString());
        String ct = childText(n);
        j.put("childText", ct.length() > 0 ? ct : JSONObject.NULL);
        j.put("viewId", n.getViewIdResourceName() == null ? JSONObject.NULL : n.getViewIdResourceName());
        Rect b = bounds(n);
        j.put("bounds", new JSONArray().put(b.left).put(b.top).put(b.right).put(b.bottom));
        j.put("class", n.getClassName() == null ? JSONObject.NULL : n.getClassName().toString());
        return j;
    }

    /** Null when ok, else a refusal reason. f = focused/target node, c = the node that would be clicked. */
    private static String expectCheck(JSONObject p, String expect, AccessibilityNodeInfo f, AccessibilityNodeInfo c) {
        List<String> labels = labelsOf(f);
        if (c != null && !c.equals(f)) labels.addAll(labelsOf(c));
        if (expect != null && expect.trim().length() > 0
                && !labelMatch(labels, expect, isTrue(p, "expect_exact"))) return "expect mismatch";
        JSONArray bad = p.optJSONArray("expect_not");
        if (bad == null && p.has("expect_not") && p.optString("expect_not").length() > 0) {
            bad = new JSONArray().put(p.optString("expect_not"));
        }
        if (bad != null) {
            for (int i = 0; i < bad.length(); i++) {
                String b = bad.optString(i, "");
                if (b.trim().length() > 0 && labelMatch(labels, b, false)) return "expect_not matched: " + b;
            }
        }
        return null;
    }

    JSONObject activate() throws Exception {
        return activate(new JSONObject());
    }

    JSONObject activate(JSONObject p) throws Exception {
        AccessibilityNodeInfo f = findA11yFocus();
        if (f == null) return Server.error("no accessibility-focused node");
        AccessibilityNodeInfo n = f;
        for (int i = 0; n != null && !n.isClickable() && i < 20; i++) n = n.getParent();
        if (n == null || !n.isClickable()) {
            JSONObject e = Server.error("no clickable node at or above the focused node");
            e.put("focused", nodeJson(f));
            return e;
        }
        if (p.has("expect") || p.has("expect_not")) {
            String why = expectCheck(p, p.has("expect") ? p.optString("expect", "") : null, f, n);
            if (why != null) {
                JSONObject e = Server.error(why);
                e.put("focused", labelSummary(f));
                e.put("clicked_candidate", labelSummary(n));
                e.put("clicked", false);
                return e;
            }
        }
        boolean res = n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        JSONObject r = ok();
        r.put("focused", nodeJson(f));
        r.put("clicked", nodeJson(n));
        r.put("clickedAncestor", !n.equals(f));
        r.put("performed", res);
        return r;
    }

    private static boolean isAncestorOrSelf(AccessibilityNodeInfo anc, AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; cur != null && i < 30; i++) {
            if (cur.equals(anc)) return true;
            cur = cur.getParent();
        }
        return false;
    }

    private String screenSig() {
        StringBuilder sb = new StringBuilder();
        sb.append(activePkg());
        AccessibilityNodeInfo r = svc.getRootInActiveWindow();
        if (r != null) {
            List<AccessibilityNodeInfo> all = new ArrayList<>();
            collect(r, all);
            for (AccessibilityNodeInfo x : all) {
                if (has(x.getText())) sb.append('|').append(x.getText());
                else if (has(x.getContentDescription())) sb.append('|').append(x.getContentDescription());
            }
        }
        return sb.toString();
    }

    /** POST /act/at-text: find, focus, verify, click the clickable ancestor of that same node. */
    JSONObject actAtText(JSONObject p) throws Exception {
        String text = p.optString("text", "");
        if (text.trim().length() == 0) return Server.error("need {text}");
        TextHit hit = findByText(text, isTrue(p, "exact"), Math.max(0, p.optInt("index", 0)), false);
        AccessibilityNodeInfo t = hit.node;
        if (t == null) {
            JSONObject e = Server.error("no node found");
            e.put("matches", hit.matches);
            e.put("lookup", hit.how);
            return e;
        }
        JSONObject target = nodeJson(t);
        target.put("matches", hit.matches);
        target.put("matchKind", hit.kind);
        AccessibilityNodeInfo c = t;
        for (int i = 0; c != null && !c.isClickable() && i < 20; i++) c = c.getParent();
        if (c == null || !c.isClickable()) {
            JSONObject e = Server.error("no clickable node at or above the target");
            e.put("target", target);
            e.put("clicked", false);
            return e;
        }
        JSONObject cand = labelSummary(c);
        Rect tb2 = bounds(t), cb = bounds(c);
        if (!cb.contains(tb2)) return refuseAt("clickable ancestor bounds do not contain target", target, cand, null);
        String cid = c.getViewIdResourceName() == null ? "" : c.getViewIdResourceName();
        String tid = t.getViewIdResourceName() == null ? "" : t.getViewIdResourceName();
        boolean sys = cid.contains("search_button") || cid.contains("nav_");
        if (sys && !c.equals(t) && !(tid.contains("search_button") || tid.contains("nav_"))) {
            return refuseAt("clickable ancestor is a top-bar/system button", target, cand, null);
        }
        String expect = p.has("expect") ? p.optString("expect", text) : text;
        String why = expectCheck(p, expect, t, c);
        if (why != null) return refuseAt(why, target, cand, null);
        // direct:true (opt-in): Chromium virtual nodes under a WebView can accept ACTION_ACCESSIBILITY_FOCUS (returns true)
        // yet never hold accessibility focus. In that case the click is sent
        // straight to the same, already label-checked node. All guards above (expect, bounds, top-bar) still apply.
        String via = p.optString("via", "");
        if (via.length() > 0 && underWebView(t)) { // via: direct | focus_click | input_focus (webview nodes only, no focus check)
            if (via.equals("focus_click")) t.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
            if (via.equals("input_focus")) {
                AccessibilityNodeInfo w = t.getParent();
                for (int i = 0; w != null && i < 40 && !cls(w).endsWith("WebView"); i++) w = w.getParent();
                if (w != null) w.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            }
            sleep(250);
            String before0 = screenSig();
            boolean res0 = c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            sleep(Math.max(600, p.optLong("settle_ms", 600)));
            JSONObject r0 = ok();
            r0.put("method", "via_" + via);
            r0.put("target", target);
            r0.put("clicked", cand);
            r0.put("performed", res0);
            r0.put("changed", !before0.equals(screenSig()));
            return r0;
        }
        boolean direct = isTrue(p, "direct") && underWebView(t);
        t.refresh();
        long settle = Math.max(0, Math.min(3000, p.optLong("settle_ms", SETTLE_MS)));
        boolean focusHeld = true;
        if (!t.isAccessibilityFocused()) {
            boolean focused = focusNodeOrAncestor(t);
            if (!focused && !direct) return refuseAt("focus action failed", target, cand, null);
            sleep(settle);
            AccessibilityNodeInfo now0 = findA11yFocus();
            focusHeld = focused && now0 != null && (isAncestorOrSelf(now0, t) || isAncestorOrSelf(t, now0));
            if (!focusHeld && !direct) {
                return refuseAt("focus moved away from target", target, cand, now0);
            }
        } else {
            sleep(settle);
        }
        String before = screenSig();
        boolean res = c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        sleep(Math.max(400, settle));
        JSONObject r = ok();
        if (!focusHeld) { r.put("method", "direct_click"); r.put("focus_held", false); }
        r.put("target", target);
        r.put("clicked", cand);
        r.put("performed", res);
        r.put("changed", !before.equals(screenSig()));
        JSONObject after = new JSONObject();
        AccessibilityNodeInfo af = findA11yFocus();
        after.put("focus", af == null ? JSONObject.NULL : labelSummary(af));
        after.put("package", activePkg() == null ? JSONObject.NULL : activePkg());
        r.put("after", after);
        return r;
    }

    private JSONObject refuseAt(String why, JSONObject target, JSONObject cand, AccessibilityNodeInfo focus) throws Exception {
        JSONObject e = Server.error(why);
        e.put("target", target);
        e.put("clicked_candidate", cand);
        e.put("clicked", false);
        if (focus != null) e.put("focused", labelSummary(focus));
        return e;
    }

    // ---------------------------------------------------------------- 3c. guards, global, type, popup

    private JSONObject abortDanger(String phrase, int visited) throws Exception {
        JSONObject r = Guard.trip(phrase);
        r.put("visited", visited);
        lastSweep = r;
        return r;
    }

    /** Capped scan of all visible windows; returns the stop response (and sets the lock) or null. */
    JSONObject checkDanger() {
        String ph = Guard.scan(svc);
        return ph == null ? null : Guard.trip(ph);
    }

    private String activePkg() {
        AccessibilityNodeInfo r = svc.getRootInActiveWindow();
        return r == null || r.getPackageName() == null ? null : r.getPackageName().toString();
    }

    private static boolean flag(JSONObject p, String k, boolean def) {
        String v = p.optString(k, def ? "true" : "false").toLowerCase(Locale.ROOT);
        return v.equals("true") || v.equals("1");
    }

    /** POST /global {action:'back'|'home'|'recents'|'notifications'}. No lock-screen / power dialog on purpose. */
    JSONObject global(JSONObject p) throws Exception {
        String a = p.optString("action", "").toLowerCase(Locale.ROOT);
        int id;
        switch (a) {
            case "back": id = AccessibilityService.GLOBAL_ACTION_BACK; break;
            case "home": id = AccessibilityService.GLOBAL_ACTION_HOME; break;
            case "recents": id = AccessibilityService.GLOBAL_ACTION_RECENTS; break;
            case "notifications": id = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS; break;
            default: return Server.error("bad action: " + a + " (back|home|recents|notifications)");
        }
        boolean done = svc.performGlobalAction(id);
        sleep(400);
        JSONObject r = new JSONObject();
        r.put("success", done);
        r.put("action", a);
        return r;
    }

    private static String textOf(AccessibilityNodeInfo n) {
        if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText()) return "";
        CharSequence t = n.getText();
        return t == null ? "" : t.toString();
    }

    private AccessibilityNodeInfo findEditable(JSONObject p, StringBuilder via) {
        if (p.has("x") && p.has("y")) {
            via.append("xy");
            int x = (int) p.optDouble("x"), y = (int) p.optDouble("y");
            AccessibilityNodeInfo best = null;
            long area = Long.MAX_VALUE;
            for (AccessibilityNodeInfo root : roots()) {
                List<AccessibilityNodeInfo> all = new ArrayList<>();
                collect(root, all);
                for (AccessibilityNodeInfo n : all) {
                    Rect b = bounds(n);
                    long a = (long) b.width() * b.height();
                    if (isEditable(n) && b.contains(x, y) && a <= area) { area = a; best = n; }
                }
                if (best != null) break;
            }
            return best;
        }
        if (p.has("viewId")) {
            via.append("viewId");
            for (AccessibilityNodeInfo root : roots()) {
                for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(p.optString("viewId"))) {
                    if (isEditable(n) && n.isVisibleToUser()) return n;
                }
            }
            return null;
        }
        if (p.has("target_text")) {
            via.append("target_text");
            return findByText(p.optString("target_text"), isTrue(p, "exact"),
                    Math.max(0, p.optInt("index", 0)), true).node;
        }
        via.append("focus");
        for (AccessibilityNodeInfo root : roots()) {
            AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f != null && isEditable(f)) return f;
        }
        AccessibilityNodeInfo a11y = findA11yFocus();
        return a11y != null && isEditable(a11y) ? a11y : null;
    }

    /**
     * POST /type {text, clear:true, append:false, [x,y | viewId | target_text]}. Replaces the content (clear:true,
     * default) or appends (append:true or clear:false). Limited to the built-in demo input.
     */
    JSONObject type(JSONObject p) throws Exception {
        if (!p.has("text")) return Server.error("need {text}");
        String typed = p.optString("text");
        String own = svc.getPackageName();
        if (!own.equals(activePkg())) return Server.error("text input is limited to the built-in demo");
        StringBuilder via = new StringBuilder();
        AccessibilityNodeInfo n = findEditable(p, via);
        if (n == null) {
            JSONObject e = Server.error("no editable node (focus a field or pass x,y | viewId | target_text)");
            e.put("via", via.toString());
            return e;
        }
        if (!own.contentEquals(n.getPackageName() == null ? "" : n.getPackageName())
                || !(own + ":id/demo_input").equals(n.getViewIdResourceName())
                || !own.equals(activePkg())) {
            return Server.error("text input is limited to the built-in demo");
        }
        String why = Guard.secretReason(n);
        if (why != null) { // never echo anything about the field's content
            JSONObject e = Server.error("password or secure field refused");
            e.put("reason", why);
            return e;
        }
        String cur = textOf(n);
        boolean append = isTrue(p, "append") || !flag(p, "clear", true);
        String want = append ? cur + typed : typed;
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, want);
        boolean set = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
        sleep(150);
        n.refresh();
        String after = textOf(n);
        String method = "set_text";
        if (!set || !after.equals(want)) { // clipboard paste fallback
            method = "paste";
            try {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        svc.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("t", typed));
                Bundle sel = new Bundle();
                sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, append ? after.length() : 0);
                sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, after.length());
                n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
                n.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                sleep(250);
                n.refresh();
                after = textOf(n);
                if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip(); // do not leave typed text on the clipboard
            } catch (Throwable t) {
                method = "paste_failed";
            }
        }
        boolean okText = after.equals(want);
        JSONObject r = new JSONObject();
        r.put("success", okText);
        if (!okText) r.put("error", "text did not change as requested");
        r.put("method", method);
        r.put("via", via.toString());
        r.put("typed_length", typed.length());
        r.put("length", after.length());
        r.put("changed", !after.equals(cur));
        r.put("field", n.getViewIdResourceName() == null ? JSONObject.NULL : n.getViewIdResourceName());
        return r;
    }

    private static boolean dialogClass(String c) {
        return c.contains("AlertDialog") || c.contains("Dialog") || c.contains("BottomSheet") || c.contains("Popup");
    }

    private static boolean dialogWord(CharSequence c) {
        if (c == null) return false;
        String s = c.toString().toLowerCase(Locale.ROOT);
        return s.contains("dialog") || s.contains("popup") || s.contains("bottomsheet") || s.contains("bottom sheet")
                || s.contains("팝업") || s.contains("대화상자");
    }

    /** Walks one window (cap 150 nodes, visible nodes only): dialog-looking class / paneTitle / role; collects texts. */
    private boolean popupWalk(AccessibilityNodeInfo n, int[] budget, List<String> texts) {
        if (n == null || --budget[0] < 0 || Thread.currentThread().isInterrupted() || !n.isVisibleToUser()) return false;
        boolean hit = dialogClass(cls(n));
        if (!hit && Build.VERSION.SDK_INT >= 28) hit = dialogWord(n.getPaneTitle());
        if (!hit) {
            Bundle ex = n.getExtras();
            hit = ex != null && dialogWord(ex.getCharSequence("AccessibilityNodeInfo.roleDescription"));
        }
        if (texts.size() < 10) {
            CharSequence t = n.getText();
            if (t == null || t.length() == 0) t = n.getContentDescription();
            if (t != null && t.length() > 0) texts.add(t.length() > 120 ? t.subSequence(0, 120).toString() : t.toString());
        }
        for (int i = 0; i < n.getChildCount(); i++) hit |= popupWalk(n.getChild(i), budget, texts);
        return hit;
    }

    /**
     * GET /popup: non-main windows plus dialog detection. A Dialog is its own application-type window that is
     * smaller than the screen (or above another app window); the activity window below it may not be listed.
     */
    JSONObject popup() throws Exception {
        List<AccessibilityWindowInfo> wins = svc.getWindows();
        DisplayMetrics dm = svc.getResources().getDisplayMetrics();
        long screen = (long) dm.widthPixels * dm.heightPixels;
        int baseLayer = Integer.MAX_VALUE;
        if (wins != null) {
            for (AccessibilityWindowInfo w : wins) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) baseLayer = Math.min(baseLayer, w.getLayer());
            }
        }
        JSONArray wl = new JSONArray();
        JSONArray texts = new JSONArray();
        boolean any = false;
        if (wins != null) {
            for (AccessibilityWindowInfo w : wins) {
                int t = w.getType();
                boolean app = t == AccessibilityWindowInfo.TYPE_APPLICATION;
                Rect wb = new Rect();
                w.getBoundsInScreen(wb);
                boolean small = app && (long) wb.width() * wb.height() < screen * 0.85;
                boolean main = app && w.getLayer() == baseLayer && !small;
                AccessibilityNodeInfo r = w.getRoot();
                List<String> tx = new ArrayList<>();
                boolean dlg = dialogWord(w.getTitle());
                if (r != null) dlg |= popupWalk(r, new int[]{150}, tx);
                boolean isPopup = dlg || (app && !main);
                if (main && !dlg) continue; // plain main application window
                JSONObject wj = new JSONObject();
                wj.put("type", t >= 0 && t < WTYPE.length ? WTYPE[t] : String.valueOf(t));
                wj.put("layer", w.getLayer());
                wj.put("title", nz(s(w.getTitle())));
                wj.put("pkg", r == null ? JSONObject.NULL : nz(s(r.getPackageName())));
                wj.put("popup", isPopup);
                wl.put(wj);
                if (isPopup) {
                    any = true;
                    for (String s : tx) if (texts.length() < 10) texts.put(s);
                }
            }
        }
        JSONObject res = ok();
        res.put("popup", any);
        res.put("windows", wl);
        res.put("texts", texts);
        return res;
    }

    // ---------------------------------------------------------------- 4. actions

    static boolean isCustomAction(int id) {
        if (id < CUSTOM_ACTION_MIN_ID) return false;
        // Framework standard actions live in 0x01020000..0x0102FFFF (e.g. SHOW_ON_SCREEN).
        return !(id >= 0x01020000 && id <= 0x0102FFFF);
    }

    JSONObject listActions(JSONObject p) throws Exception {
        boolean all = p != null && "1".equals(p.optString("all", ""));
        AccessibilityNodeInfo f = findA11yFocus();
        if (f == null) return Server.error("no accessibility-focused node");
        JSONArray arr = new JSONArray();
        for (AccessibilityAction a : f.getActionList()) {
            if (!all && !isCustomAction(a.getId())) continue;
            JSONObject j = new JSONObject();
            j.put("id", a.getId());
            j.put("idHex", "0x" + Integer.toHexString(a.getId()));
            j.put("label", a.getLabel() == null ? JSONObject.NULL : a.getLabel().toString());
            arr.put(j);
        }
        JSONObject r = ok();
        r.put("node", nodeJson(f));
        r.put("actions", arr);
        return r;
    }

    JSONObject runAction(JSONObject p) throws Exception {
        AccessibilityNodeInfo f = findA11yFocus();
        if (f == null) return Server.error("no accessibility-focused node");
        AccessibilityAction match = null;
        String wantLabel = p.has("label") ? p.optString("label") : null;
        Integer wantId = null;
        if (p.has("id")) {
            String s = p.optString("id").trim().toLowerCase(Locale.ROOT);
            try {
                wantId = s.startsWith("0x") ? (int) Long.parseLong(s.substring(2), 16) : (int) Long.parseLong(s);
            } catch (Exception e) {
                return Server.error("bad id: " + s);
            }
        }
        if (wantId == null && wantLabel == null) return Server.error("need {id} or {label}");
        for (AccessibilityAction a : f.getActionList()) {
            if (!isCustomAction(a.getId())) continue;
            if (wantId != null && a.getId() == wantId) { match = a; break; }
            if (wantId == null && a.getLabel() != null && a.getLabel().toString().equalsIgnoreCase(wantLabel)) {
                match = a;
                break;
            }
        }
        if (match == null) {
            JSONObject e = Server.error("action not found on focused node");
            e.put("node", nodeJson(f));
            return e;
        }
        boolean res = f.performAction(match.getId());
        JSONObject r = ok();
        r.put("node", nodeJson(f));
        r.put("id", match.getId());
        r.put("label", match.getLabel() == null ? JSONObject.NULL : match.getLabel().toString());
        r.put("performed", res);
        return r;
    }
}
