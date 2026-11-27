package com.artemis.helpermin;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Harmless in-app demo screen that mimics real accessibility defects so a
 * diagnosis tool can find them. No network, no data. Not a launcher entry.
 */
public class DemoDefectsActivity extends Activity {
    private TextView status;
    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView tv(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        return t;
    }

    @Override
    protected void onCreate(Bundle b) {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE); // hide action bar
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(28), dp(16), dp(8));

        // 1. Good examples: headings (no defect)
        TextView title = tv("접근성 진단 시연 화면", 20);
        title.setAccessibilityHeading(true);
        root.addView(title);
        TextView h2 = tv("카드 목록", 16);
        h2.setAccessibilityHeading(true);
        root.addView(h2);

        // 2. CARD_SUBTITLE_MISSING: contentDescription hides the visible subtitle
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        card.setClickable(true);
        card.setMinimumHeight(dp(48));
        card.addView(tv("이번 달 소비", 14));
        card.addView(tv("지난달보다 12% 줄었어요", 12));
        card.setContentDescription("이번 달 소비");
        card.setOnClickListener(v -> status.setText("상태: 소비 카드 열림"));
        root.addView(card);

        // Row for the icon button + chip (compact)
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        // 3. UNLABELED_ICON_BUTTON: no contentDescription
        ImageButton icon = new ImageButton(this);
        icon.setImageResource(android.R.drawable.ic_menu_search);
        icon.setOnClickListener(v -> status.setText("상태: 아이콘 눌림"));
        row.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

        // 4. CHIP_STATE_MISSING: visual arrow only, no expanded state exposed
        final Button chip = new Button(this);
        final boolean[] open = {false};
        chip.setText("기간 ▼");
        chip.setAllCaps(false);
        chip.setOnClickListener(v -> {
            open[0] = !open[0];
            chip.setText(open[0] ? "기간 ▲" : "기간 ▼");
            status.setText("상태: 기간 " + (open[0] ? "펼침" : "접힘"));
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        clp.leftMargin = dp(12);
        row.addView(chip, clp);

        // 9. SMALL_TARGET: clickable 24dp x 24dp
        TextView close = tv("닫기", 8);
        close.setGravity(Gravity.CENTER);
        close.setClickable(true);
        close.setFocusable(true);
        close.setOnClickListener(v -> status.setText("상태: 닫기 눌림"));
        LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(dp(24), dp(24));
        xlp.leftMargin = dp(12);
        row.addView(close, xlp);
        root.addView(row);

        // 5. CHART_PARTIAL: contentDescription lacks peer average
        View chart = new View(this) {
            private final Paint p = new Paint();
            @Override
            protected void onDraw(Canvas c) {
                int w = getWidth(), h = getHeight();
                p.setColor(0xFF3F6FD8);
                c.drawRect(w * 0.10f, h * 0.30f, w * 0.30f, h, p);
                p.setColor(0xFFAAAAAA);
                c.drawRect(w * 0.40f, h * 0.55f, w * 0.60f, h, p);
                p.setColor(0xFF3F6FD8);
                c.drawRect(w * 0.70f, h * 0.15f, w * 0.90f, h, p);
            }
        };
        chart.setFocusable(true);
        chart.setContentDescription("내 소비 120만원");
        root.addView(chart, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        // 6. FIXED_LABEL_CARD: generic fixed contentDescription over a specific title
        LinearLayout card2 = new LinearLayout(this);
        card2.setOrientation(LinearLayout.VERTICAL);
        card2.setFocusable(true);
        card2.setClickable(true);
        card2.setMinimumHeight(dp(48));
        card2.addView(tv("점심 모임 후기", 14));
        card2.setContentDescription("커뮤니티 댓글");
        card2.setOnClickListener(v -> status.setText("상태: 후기 열림"));
        root.addView(card2);

        // 7. SILENT_CUSTOM_ACTION: action changes status text, announces nothing
        TextView saved = tv("저장된 항목", 14);
        saved.setFocusable(true);
        saved.setMinimumHeight(dp(48));
        saved.setGravity(Gravity.CENTER_VERTICAL);
        saved.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        R.id.action_demo_mark_delete, "항목 삭제 표시"));
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, Bundle args) {
                if (action == R.id.action_demo_mark_delete) {
                    status.setText("상태: 삭제 표시됨"); // no announceForAccessibility on purpose
                    return true;
                }
                return super.performAccessibilityAction(host, action, args);
            }
        });
        root.addView(saved);

        // 8. EMPTY_FOCUS: focusable empty view, no text/label, 48dp high
        FrameLayout empty = new FrameLayout(this);
        empty.setFocusable(true);
        root.addView(empty, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        // 9b. Popup-like group (v0.1.16 test). Native Views marked IMPORTANT_FOR_ACCESSIBILITY_NO are pruned by the
        // framework (the service does not set FLAG_INCLUDE_NOT_IMPORTANT_VIEWS), so, like a WebView/Compose popup,
        // the items are virtual nodes (AccessibilityNodeProvider) that report importantForAccessibility=false:
        //   1,2 = clickable + checkable + text + roleDescription 'radio button' (must be reported),
        //   3 = unlabeled container, 4 = decorative non-clickable text (must NOT be reported).
        root.addView(new PopupMenuView(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48) * 4));

        // 10. Status text
        status = tv("상태: 대기", 14);
        status.setPadding(0, dp(8), 0, 0);
        root.addView(status);

        // 11. Link: ClickableSpan inside TextView (no URL, only updates status text)
        TextView link = tv("", 14);
        android.text.SpannableString ls = new android.text.SpannableString("자세히 보기 안내");
        ls.setSpan(new android.text.style.ClickableSpan() {
            @Override
            public void onClick(View widget) {
                status.setText("상태: 링크 눌림");
            }
        }, 0, ls.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        link.setText(ls);
        link.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        link.setMinimumHeight(dp(48));
        link.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(link);

        // 12. Test inputs for /type: plain field and a password field (guard must refuse the latter)
        android.widget.EditText plain = new android.widget.EditText(this);
        plain.setId(R.id.demo_input);
        plain.setHint("시험용 입력");
        plain.setSingleLine(true);
        root.addView(plain);
        android.widget.EditText pw = new android.widget.EditText(this);
        pw.setId(R.id.demo_password);
        pw.setHint("비밀번호 시험");
        pw.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(pw);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        // Test-only popups: am start -f 0x10008000 -n .../.DemoDefectsActivity --ez dialog true | --ez danger true
        if (getIntent().getBooleanExtra("dialog", false)) {
            new android.app.AlertDialog.Builder(this).setTitle("시험 팝업")
                    .setMessage("이 팝업은 시연용입니다").setPositiveButton("확인", null).show();
        } else if (getIntent().getBooleanExtra("danger", false)) {
            new android.app.AlertDialog.Builder(this).setTitle("시험 경고(테스트 전용)")
                    .setMessage("해킹 위험성이 탐지되었습니다").setPositiveButton("확인", null).show();
        }

        // targetSdk 35 is edge-to-edge: pad the top by the status bar inset
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            v.setPadding(dp(16), ins.getSystemWindowInsetTop() + dp(8), dp(16), dp(8));
            return ins;
        });
        setContentView(root);
    }

    /** Host view with virtual, non-important children (mimics a webview/compose popup menu). */
    private class PopupMenuView extends View {
        private final boolean[] sel = {true, false};
        private int focusedId = -1;
        private final android.view.accessibility.AccessibilityNodeProvider provider = new Provider();

        PopupMenuView(Context c) {
            super(c);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES); // host stays unlabeled
        }

        @Override
        public android.view.accessibility.AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return provider;
        }

        private android.graphics.Rect rowRect(int id) { // screen-space bounds of virtual row id (1..4)
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            int h = getHeight() / 4;
            return new android.graphics.Rect(loc[0], loc[1] + (id - 1) * h, loc[0] + getWidth(), loc[1] + id * h);
        }

        private void sendVirtual(int id, int type) {
            if (getParent() == null) return;
            android.view.accessibility.AccessibilityEvent ev = android.view.accessibility.AccessibilityEvent.obtain(type);
            ev.setPackageName(getContext().getPackageName());
            ev.setClassName("android.view.View");
            ev.setSource(this, id);
            getParent().requestSendAccessibilityEvent(this, ev);
        }

        private class Provider extends android.view.accessibility.AccessibilityNodeProvider {
            @Override
            public AccessibilityNodeInfo createAccessibilityNodeInfo(int id) {
                PopupMenuView host = PopupMenuView.this;
                AccessibilityNodeInfo info;
                if (id == android.view.accessibility.AccessibilityNodeProvider.HOST_VIEW_ID) { // host
                    info = AccessibilityNodeInfo.obtain(host);
                    host.onInitializeAccessibilityNodeInfo(info);
                    for (int k = 1; k <= 4; k++) info.addChild(host, k);
                    return info;
                }
                info = AccessibilityNodeInfo.obtain();
                info.setSource(host, id);
                info.setParent(host);
                info.setPackageName(host.getContext().getPackageName());
                info.setClassName("android.view.View");
                info.setEnabled(true);
                info.setVisibleToUser(true);
                info.setImportantForAccessibility(false);
                android.graphics.Rect r = rowRect(id);
                info.setBoundsInScreen(r);
                android.graphics.Rect pr = new android.graphics.Rect(r);
                int[] loc = new int[2];
                host.getLocationOnScreen(loc);
                pr.offset(-loc[0], -loc[1]);
                info.setBoundsInParent(pr);
                if (id == 1 || id == 2) {
                    info.setText("시험 메뉴 항목 " + id);
                    info.setClickable(true);
                    info.setCheckable(true);
                    info.setChecked(sel[id - 1]);
                    info.getExtras().putCharSequence("AccessibilityNodeInfo.roleDescription", "radio button");
                    info.addAction(AccessibilityNodeInfo.ACTION_CLICK);
                } else if (id == 4) {
                    info.setText("장식 텍스트(시험)"); // text only: not a control
                }
                info.addAction(focusedId == id ? AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                        : AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
                info.setAccessibilityFocused(focusedId == id);
                return info;
            }

            @Override
            public boolean performAction(int id, int action, Bundle args) {
                PopupMenuView host = PopupMenuView.this;
                if (id == android.view.accessibility.AccessibilityNodeProvider.HOST_VIEW_ID) {
                    return host.performAccessibilityAction(action, args);
                }
                switch (action) {
                    case AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS:
                        if (focusedId == id) return false;
                        focusedId = id;
                        host.invalidate();
                        sendVirtual(id, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
                        return true;
                    case AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:
                        if (focusedId != id) return false;
                        focusedId = -1;
                        host.invalidate();
                        sendVirtual(id, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);
                        return true;
                    case AccessibilityNodeInfo.ACTION_CLICK:
                        if (id != 1 && id != 2) return false;
                        sel[0] = id == 1;
                        sel[1] = id == 2;
                        status.setText("상태: 시험 메뉴 항목 " + id + " 선택");
                        sendVirtual(id, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);
                        return true;
                    default:
                        return false;
                }
            }
        }
    }
}
