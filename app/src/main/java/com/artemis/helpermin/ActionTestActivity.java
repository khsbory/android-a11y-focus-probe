package com.artemis.helpermin;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.accessibility.AccessibilityNodeInfo;

/** Harmless in-app screen with custom accessibility actions for testing. */
public class ActionTestActivity extends Activity {
    private TextView status;
    private int stars = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView a = makeItem("Action test item A");
        a.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.action_mark_done, "Mark item done"));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.action_add_star, "Add star"));
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, Bundle args) {
                if (action == R.id.action_mark_done) {
                    status.setText("Status: done A");
                    return true;
                }
                if (action == R.id.action_add_star) {
                    stars++;
                    status.setText("Status: stars " + stars);
                    return true;
                }
                return super.performAccessibilityAction(host, action, args);
            }
        });

        TextView bView = makeItem("Action test item B");
        bView.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.action_reset_counter, "Reset counter"));
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, Bundle args) {
                if (action == R.id.action_reset_counter) {
                    stars = 0;
                    status.setText("Status: reset");
                    return true;
                }
                return super.performAccessibilityAction(host, action, args);
            }
        });

        status = new TextView(this);
        status.setText("Status: idle");
        status.setTextSize(20);
        status.setPadding(0, pad, 0, pad);
        status.setFocusable(true);
        status.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        root.addView(a, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(bView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    private TextView makeItem(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(20);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        t.setPadding(0, p, 0, p);
        t.setFocusable(true);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return t;
    }
}
