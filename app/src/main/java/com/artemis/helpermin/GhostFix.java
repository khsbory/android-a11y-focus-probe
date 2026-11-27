package com.artemis.helpermin;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.SystemClock;
import android.util.DisplayMetrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * POST /fix/ghost-pointers : best-effort attempt to clear ghost pressed pointers with dispatchGesture.
 * EFFECT IS UNCERTAIN. The helper cannot read `dumpsys input`; verify from the PC (see info()).
 * Never touches accessibility settings. Bounded: at most GhostPlan.MAX_ATTEMPTS attempts, each hold <= 300 ms.
 */
final class GhostFix {

    static final String VERIFY_CMD = "adb shell \"dumpsys input | grep -A1 'Filter verifier'\"";

    private GhostFix() {}

    static JSONObject info() throws Exception {
        JSONObject r = new JSONObject();
        r.put("success", true);
        r.put("endpoint", "POST /fix/ghost-pointers");
        r.put("status", "experimental; effect uncertain; written before the symptom could be reproduced");
        JSONObject p = new JSONObject();
        p.put("ids", "optional, e.g. \"4,1,3,2\" or [4,1,3,2]: the ghost pointer ids printed by dumpsys (0..9)");
        p.put("attempts", "1.." + GhostPlan.MAX_ATTEMPTS + " (default " + GhostPlan.MAX_ATTEMPTS + ")");
        p.put("hold_ms", GhostPlan.MIN_HOLD_MS + ".." + GhostPlan.MAX_HOLD_MS + " per attempt (default "
                + GhostPlan.DEFAULT_HOLD_MS + ")");
        p.put("x", "screen fraction " + GhostPlan.MIN_FRAC + ".." + GhostPlan.MAX_FRAC + " (default 0.5)");
        p.put("y", "screen fraction " + GhostPlan.MIN_FRAC + ".." + GhostPlan.MAX_FRAC + " (default 0.5)");
        p.put("chain", "true (default): each attempt pre-empts the previous one after " + GhostPlan.PREEMPT_AFTER_MS
                + " ms so the previous one ends in onCancelled (framework injects ACTION_CANCEL); "
                + "false: wait for each attempt to finish first");
        r.put("params", p);
        r.put("verify_on_pc", VERIFY_CMD + "  (healthy output contains: Touching pointer IDs by device: {})");
        JSONArray limits = new JSONArray();
        limits.put("dispatchGesture events pass through the accessibility input filter; the pointer ids and the "
                + "device id are assigned by the framework, not by the app (estimate: pointer id = stroke index).");
        limits.put("The verifier keeps state per device id. If gesture events use a different device id than the "
                + "ghost pointers (ghost was device -1), this cannot clear them. Unverified.");
        limits.put("A gesture sends DOWN. If the verifier still holds a pressed pointer for the same device, a "
                + "DOWN is rejected as inconsistent and dropped, so the attempt may be a no-op.");
        limits.put("The last attempt completes normally and is a short press at (x,y) with 1..10 fingers; it may "
                + "tap whatever is under that point. Choose an empty spot, or check /inspect first.");
        limits.put("If the symptom is real, fall back to PC-side injection: see README (app_process Inj).");
        r.put("limits", limits);
        return r;
    }

    static JSONObject run(AccessibilityService svc, JSONObject params) throws Exception {
        int[] ids;
        try {
            Object raw = params.opt("ids");
            if (raw instanceof JSONArray) {
                JSONArray a = (JSONArray) raw;
                ids = new int[a.length()];
                for (int i = 0; i < a.length(); i++) ids[i] = a.getInt(i);
                if (ids.length == 0) ids = null;
            } else {
                ids = GhostPlan.parseIds(raw == null || raw == JSONObject.NULL ? null : String.valueOf(raw));
            }
        } catch (Exception e) {
            return Server.error("bad ids: " + e.getMessage());
        }
        int attempts = params.optInt("attempts", GhostPlan.MAX_ATTEMPTS);
        long hold = params.optLong("hold_ms", GhostPlan.DEFAULT_HOLD_MS);
        double fx = params.optDouble("x", 0.5);
        double fy = params.optDouble("y", 0.5);
        boolean chain = !"false".equalsIgnoreCase(params.optString("chain", "true"));
        String bad = GhostPlan.validate(ids, attempts, hold, fx, fy);
        if (bad != null) return Server.error(bad);

        DisplayMetrics dm = svc.getResources().getDisplayMetrics();
        float bx = (float) (dm.widthPixels * fx);
        float by = (float) (dm.heightPixels * fy);
        List<GhostPlan.Attempt> plan = GhostPlan.build(ids, attempts, hold);

        final long t0 = SystemClock.elapsedRealtime();
        final int n = plan.size();
        final String[] outcome = new String[n];
        final long[] doneAt = new long[n];
        final long[] sentAt = new long[n];
        final boolean[] dispatched = new boolean[n];
        final CountDownLatch[] latches = new CountDownLatch[n];

        for (int i = 0; i < n; i++) {
            GhostPlan.Attempt a = plan.get(i);
            final int idx = i;
            latches[i] = new CountDownLatch(1);
            outcome[i] = "not-sent";
            GestureDescription.Builder b = new GestureDescription.Builder();
            for (GhostPlan.Stroke s : a.strokes) {
                Path p = new Path();
                p.moveTo(clamp(bx + s.dx, 1f, dm.widthPixels - 1f), by);
                b.addStroke(new GestureDescription.StrokeDescription(p, s.startMs, s.durationMs));
            }
            sentAt[i] = SystemClock.elapsedRealtime() - t0;
            dispatched[i] = svc.dispatchGesture(b.build(), new AccessibilityService.GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription d) {
                    outcome[idx] = "completed";
                    doneAt[idx] = SystemClock.elapsedRealtime() - t0;
                    latches[idx].countDown();
                }

                @Override
                public void onCancelled(GestureDescription d) {
                    outcome[idx] = "cancelled";
                    doneAt[idx] = SystemClock.elapsedRealtime() - t0;
                    latches[idx].countDown();
                }
            }, null);
            if (!dispatched[i]) {
                outcome[i] = "rejected";
                latches[i].countDown();
            }
            if (i < n - 1) {
                if (chain) {
                    Thread.sleep(Math.min(GhostPlan.PREEMPT_AFTER_MS, a.holdMs / 2)); // next dispatch pre-empts this one
                } else {
                    latches[i].await(a.holdMs + 700, TimeUnit.MILLISECONDS);
                    Thread.sleep(150);
                }
            }
        }
        boolean allDone = true;
        for (int i = 0; i < n; i++) {
            if (!latches[i].await(plan.get(i).holdMs + 700, TimeUnit.MILLISECONDS)) {
                outcome[i] = "timeout";
                allDone = false;
            }
        }

        JSONArray arr = new JSONArray();
        for (int i = 0; i < n; i++) {
            GhostPlan.Attempt a = plan.get(i);
            JSONObject o = new JSONObject();
            o.put("attempt", a.number);
            o.put("kind", a.kind);
            o.put("strokes", a.strokes.size());
            o.put("hold_ms", a.holdMs);
            o.put("dispatched", dispatched[i]);
            o.put("callback", outcome[i]);
            o.put("sent_at_ms", sentAt[i]);
            o.put("elapsed_ms", doneAt[i] > 0 ? doneAt[i] - sentAt[i] : JSONObject.NULL);
            arr.put(o);
        }
        JSONObject r = new JSONObject();
        r.put("success", allDone);
        r.put("mode", chain ? "chain" : "sequential");
        r.put("ids", ids == null ? JSONObject.NULL : new JSONArray(toList(ids)));
        r.put("x_px", Math.round(bx));
        r.put("y_px", Math.round(by));
        r.put("attempts", arr);
        r.put("total_ms", SystemClock.elapsedRealtime() - t0);
        r.put("effect", "unknown: the app cannot read dumpsys input");
        r.put("verify_on_pc", VERIFY_CMD);
        return r;
    }

    private static List<Integer> toList(int[] a) {
        List<Integer> l = new ArrayList<>();
        for (int v : a) l.add(v);
        return l;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
