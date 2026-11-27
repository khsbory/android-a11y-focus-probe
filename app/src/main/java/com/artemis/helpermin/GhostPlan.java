package com.artemis.helpermin;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java planner for POST /fix/ghost-pointers (no Android imports so it can be unit-tested on a PC).
 *
 * Goal: try to clear "ghost" pressed pointers left in InputDispatcher's input-filter verifier by sending
 * accessibility gestures. Effect is UNCERTAIN (see README, section "Ghost pointer fix"). This class only
 * decides WHAT to send; GhostFix sends it.
 *
 * Assumption (estimate, not verified on a device): the accessibility MotionEventInjector gives the i-th
 * stroke of a gesture pointer id i. So to produce a CANCEL carrying exactly the ids {a,b,c,d}, we start one
 * stroke per index 0..max(ids); strokes whose index is NOT in ids are made short so they lift early, leaving
 * only the wanted ids down while the gesture is pre-empted by the next one (framework then injects a CANCEL
 * for whatever is still down).
 */
final class GhostPlan {

    static final int MAX_ATTEMPTS = 3;
    static final int MAX_POINTER_ID = 9;          // we never create more than 10 simultaneous strokes
    static final long MIN_HOLD_MS = 100;
    static final long MAX_HOLD_MS = 300;          // hard cap per attempt
    static final long DEFAULT_HOLD_MS = 250;
    static final long SHORT_STROKE_MS = 40;       // early-lift strokes (indexes not in the wanted id set)
    static final long PREEMPT_AFTER_MS = 120;     // chain mode: next attempt is sent this long after the previous
    static final double MIN_FRAC = 0.15;          // keep away from status bar, nav bar and back-gesture edges
    static final double MAX_FRAC = 0.85;
    static final float SPACING_PX = 24f;          // horizontal spread so strokes are not identical points

    static final class Stroke {
        final int index;          // position in the gesture (= expected pointer id, estimate)
        final long startMs;
        final long durationMs;
        final float dx;           // offset from the base point, pixels

        Stroke(int index, long startMs, long durationMs, float dx) {
            this.index = index;
            this.startMs = startMs;
            this.durationMs = durationMs;
            this.dx = dx;
        }
    }

    static final class Attempt {
        final int number;         // 1-based
        final String kind;        // match-ids | single | all-5 | all-10
        final List<Stroke> strokes;
        final long holdMs;

        Attempt(int number, String kind, List<Stroke> strokes, long holdMs) {
            this.number = number;
            this.kind = kind;
            this.strokes = strokes;
            this.holdMs = holdMs;
        }

        long maxEndMs() {
            long m = 0;
            for (Stroke s : strokes) m = Math.max(m, s.startMs + s.durationMs);
            return m;
        }
    }

    private GhostPlan() {}

    /** Returns null if the parameters are acceptable, otherwise a short error message. */
    static String validate(int[] ids, int attempts, long holdMs, double x, double y) {
        if (attempts < 1 || attempts > MAX_ATTEMPTS) {
            return "attempts must be 1.." + MAX_ATTEMPTS;
        }
        if (holdMs < MIN_HOLD_MS || holdMs > MAX_HOLD_MS) {
            return "hold_ms must be " + MIN_HOLD_MS + ".." + MAX_HOLD_MS;
        }
        if (!(x >= MIN_FRAC && x <= MAX_FRAC) || !(y >= MIN_FRAC && y <= MAX_FRAC)) {
            return "x and y are screen fractions and must be within " + MIN_FRAC + ".." + MAX_FRAC
                    + " (edges and system bars are refused)";
        }
        if (ids != null) {
            if (ids.length == 0 || ids.length > MAX_POINTER_ID + 1) {
                return "ids must contain 1.." + (MAX_POINTER_ID + 1) + " pointer ids";
            }
            boolean[] seen = new boolean[MAX_POINTER_ID + 1];
            for (int id : ids) {
                if (id < 0 || id > MAX_POINTER_ID) return "pointer id out of range 0.." + MAX_POINTER_ID + ": " + id;
                if (seen[id]) return "duplicate pointer id: " + id;
                seen[id] = true;
            }
        }
        return null;
    }

    /**
     * Build the attempt list. Order is chosen so that, in chain mode, the attempt that completes normally
     * (the last one) is never worse than the others.
     * With ids:    match-ids, single, all-5.     Without ids: single, all-5, all-10.
     */
    static List<Attempt> build(int[] ids, int attempts, long holdMs) {
        String[] order = ids != null
                ? new String[] {"match-ids", "single", "all-5"}
                : new String[] {"single", "all-5", "all-10"};
        List<Attempt> out = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            String kind = order[i];
            List<Stroke> strokes = new ArrayList<>();
            switch (kind) {
                case "match-ids": {
                    boolean[] want = new boolean[MAX_POINTER_ID + 1];
                    int max = 0;
                    for (int id : ids) {
                        want[id] = true;
                        max = Math.max(max, id);
                    }
                    for (int k = 0; k <= max; k++) {
                        strokes.add(new Stroke(k, 0, want[k] ? holdMs : SHORT_STROKE_MS, k * SPACING_PX));
                    }
                    break;
                }
                case "single":
                    strokes.add(new Stroke(0, 0, holdMs, 0f));
                    break;
                case "all-5":
                    addAll(strokes, 5, holdMs);
                    break;
                default: // all-10
                    addAll(strokes, 10, holdMs);
                    break;
            }
            out.add(new Attempt(i + 1, kind, strokes, holdMs));
        }
        return out;
    }

    private static void addAll(List<Stroke> strokes, int n, long holdMs) {
        for (int k = 0; k < n; k++) strokes.add(new Stroke(k, 0, holdMs, k * SPACING_PX));
    }

    /** "4,1,3,2" -> {4,1,3,2}; returns null for null/empty; throws NumberFormatException on junk. */
    static int[] parseIds(String s) {
        if (s == null) return null;
        String t = s.trim().replace("[", "").replace("]", "").replace("{", "").replace("}", "");
        if (t.isEmpty()) return null;
        String[] parts = t.split("[,\\s]+");
        int[] r = new int[parts.length];
        for (int i = 0; i < parts.length; i++) r[i] = Integer.parseInt(parts[i].trim());
        return r;
    }
}
