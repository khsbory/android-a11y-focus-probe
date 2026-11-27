package com.artemis.helpermin;

import java.util.List;

/**
 * Plain-Java test for GhostPlan (no JUnit needed). Run from the repo root:
 *   javac -d build/test app/src/main/java/com/artemis/helpermin/GhostPlan.java tools/tests/GhostPlanTest.java
 *   java -cp build/test com.artemis.helpermin.GhostPlanTest
 */
public class GhostPlanTest {
    private static int failures = 0;

    private static void check(boolean ok, String name) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        // validation
        check(GhostPlan.validate(null, 3, 250, 0.5, 0.5) == null, "defaults are valid");
        check(GhostPlan.validate(null, 0, 250, 0.5, 0.5) != null, "attempts 0 rejected");
        check(GhostPlan.validate(null, 4, 250, 0.5, 0.5) != null, "attempts 4 rejected");
        check(GhostPlan.validate(null, 3, 301, 0.5, 0.5) != null, "hold 301 rejected");
        check(GhostPlan.validate(null, 3, 99, 0.5, 0.5) != null, "hold 99 rejected");
        check(GhostPlan.validate(null, 3, 300, 0.5, 0.5) == null, "hold 300 accepted");
        check(GhostPlan.validate(null, 3, 250, 0.02, 0.5) != null, "left edge x rejected");
        check(GhostPlan.validate(null, 3, 250, 0.5, 0.97) != null, "bottom edge y rejected");
        check(GhostPlan.validate(null, 3, 250, 0.5, Double.NaN) != null, "NaN y rejected");
        check(GhostPlan.validate(new int[] {4, 1, 3, 2}, 3, 250, 0.5, 0.5) == null, "ids 4,1,3,2 accepted");
        check(GhostPlan.validate(new int[] {1, 1}, 3, 250, 0.5, 0.5) != null, "duplicate id rejected");
        check(GhostPlan.validate(new int[] {10}, 3, 250, 0.5, 0.5) != null, "id 10 rejected");
        check(GhostPlan.validate(new int[] {-1}, 3, 250, 0.5, 0.5) != null, "id -1 rejected");

        // parsing
        int[] p = GhostPlan.parseIds("4,1,3,2");
        check(p.length == 4 && p[0] == 4 && p[3] == 2, "parse 4,1,3,2");
        p = GhostPlan.parseIds("{4, 1, 3, 2}");
        check(p.length == 4 && p[1] == 1, "parse braces and spaces");
        check(GhostPlan.parseIds("  ") == null && GhostPlan.parseIds(null) == null, "parse empty -> null");
        boolean threw = false;
        try { GhostPlan.parseIds("a,b"); } catch (NumberFormatException e) { threw = true; }
        check(threw, "parse junk throws");

        // plan with ids {4,1,3,2}
        List<GhostPlan.Attempt> plan = GhostPlan.build(new int[] {4, 1, 3, 2}, 3, 250);
        check(plan.size() == 3, "3 attempts");
        GhostPlan.Attempt a1 = plan.get(0);
        check(a1.kind.equals("match-ids") && a1.strokes.size() == 5, "match-ids has 5 strokes (index 0..4)");
        check(a1.strokes.get(0).durationMs == GhostPlan.SHORT_STROKE_MS, "index 0 lifts early (not wanted)");
        boolean longOk = true;
        for (int i : new int[] {1, 2, 3, 4}) longOk &= a1.strokes.get(i).durationMs == 250;
        check(longOk, "wanted ids 1..4 are held for hold_ms");
        check(plan.get(1).kind.equals("single") && plan.get(1).strokes.size() == 1, "attempt 2 single");
        check(plan.get(2).kind.equals("all-5") && plan.get(2).strokes.size() == 5, "attempt 3 all-5");

        // plan without ids
        plan = GhostPlan.build(null, 3, 300);
        check(plan.get(0).kind.equals("single") && plan.get(1).kind.equals("all-5")
                && plan.get(2).kind.equals("all-10") && plan.get(2).strokes.size() == 10, "no-ids order and sizes");

        // safety bounds hold for every kind
        boolean bounded = true;
        for (GhostPlan.Attempt a : GhostPlan.build(new int[] {9, 0}, 3, 300)) {
            bounded &= a.maxEndMs() <= GhostPlan.MAX_HOLD_MS && a.strokes.size() <= 10;
        }
        for (GhostPlan.Attempt a : plan) bounded &= a.maxEndMs() <= GhostPlan.MAX_HOLD_MS && a.strokes.size() <= 10;
        check(bounded, "every stroke ends within 300 ms and no attempt exceeds 10 strokes");
        check(GhostPlan.build(null, 1, 250).size() == 1, "attempts=1 gives one attempt");

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        if (failures != 0) System.exit(1);
    }
}
