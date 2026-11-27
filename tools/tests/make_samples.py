"""Generate synthetic offline test data (no phone needed): sweep_sample.json and log_sample.txt.

Run 1 = wait-speech (everything spoken), run 2 = fast 300 ms (TalkBack drops/cuts some phrases,
and the screen has no headings, to trigger HEADING_MAP_NONE).
Every diagnosis flag is triggered at least once.
"""
import json
import os
import time

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = 1760000000.0  # PC epoch (UTC); phone tz = 0, clock offset = 0


def node(cls, text="", desc="", clickable=False, vid="", bounds="[0,0][400,150]", child="", **kw):
    d = {"class": cls, "text": text, "contentDescription": desc, "clickable": clickable,
         "viewId": vid, "bounds": bounds, "childText": child}
    d.update(kw)
    return d


# (node, custom actions, spoken phrases, slow?)
SPEC = [
    (node("android.widget.TextView", text="홈", bounds="[0,0][400,150]", heading=True, headingLevel=1), [], ["홈", "제목"], False),
    (node("android.widget.ImageButton", clickable=True, bounds="[0,160][100,210]"), [], [], False),           # UNLABELED_CLICKABLE, SMALL_TARGET, EMPTY_FOCUS
    (node("android.widget.Button", text="확인", clickable=True, bounds="[0,220][400,370]"), ["빠른 실행"], ["확인 버튼"], False),  # CUSTOM_ACTIONS
    (node("android.widget.FrameLayout", bounds="[0,380][400,530]"), [], ["목록"], False),                      # ROLE_ONLY
    (node("android.widget.TextView", text="중복", bounds="[0,540][400,690]"), [], ["중복"], False),
    (node("android.widget.TextView", text="중복", bounds="[0,700][400,850]"), [], ["중복"], False),            # DUPLICATE
    (node("android.widget.TextView", text="느린 항목", bounds="[0,860][400,1010]"), [], ["느린 항목"], True),    # SLOW
    (node("android.widget.ImageView", desc="이미지", clickable=True, bounds="[0,1020][400,1170]"), [], ["이미지"], False),  # NAME_GENERIC
    (node("android.widget.TextView", text="가격\n500,014원", bounds="[0,1180][400,1330]"), [], ["가격", "500,014", "원"], False),  # no flag (merge)
    (node("android.widget.TextView", text="제목\n부제목 내용", bounds="[0,1340][400,1490]"), [], ["제목"], False),  # SUBTITLE_MISSING
    (node("android.widget.CheckBox", text="알림 설정", clickable=True, checkable=True, checked=True,
          bounds="[0,1500][400,1650]"), [], ["알림 설정", "확인란"], False),                                   # STATE_MISSING
    (node("android.widget.TextView", text="주소 표시줄", bounds="[0,1660][400,1810]"), [], ["주소 표시줄", "http://example.com/x", "새 알림이 도착했습니다"], False),  # off-screen speech
    (node("android.widget.Button", text="삭제", clickable=True, bounds="[0,1820][400,1970]"), [], ["삭제 버튼"], False),  # ACTION_NO_SPEECH (last step)
]
DROP_FAST = {0, 2, 6}  # TalkBack said nothing for these in the fast run
CUT_FAST = {3: ["목"], 10: ["알림 설정"]}  # truncated speech in the fast run


def make_run(base, delay_ms, mode, fast):
    steps, log = [], []
    t = base
    for i, (n, acts, sp, slow) in enumerate(SPEC):
        n = dict(n)
        if fast:
            n.pop("heading", None)
            n.pop("headingLevel", None)
        t0 = t
        t1 = t0 + (1.4 if slow else 0.08)
        step = {"node": n, "actions": acts, "t0": t0, "t1": t1}
        phrases = sp
        if fast and i in DROP_FAST:
            phrases = []
        elif fast and i in CUT_FAST:
            phrases = CUT_FAST[i]
        wend = t1
        if mode == "wait-speech" and phrases:
            wend = t1 + 0.15 + 0.1 * len(phrases) + 0.2
            step["wend"] = wend
        if i == len(SPEC) - 1:
            step["action_runs"] = [{"label": "삭제 실행", "t0": t1 + 0.5, "t1": t1 + 0.6}]
        for k, ph in enumerate(phrases):
            ts = t1 + 0.15 + 0.1 * k
            lt = time.gmtime(ts)
            log.append('%02d-%02d %02d:%02d:%02d.%03d I/Speech( 100): Speaking fragment text="%s", utteranceId=u%d'
                       % (lt.tm_mon, lt.tm_mday, lt.tm_hour, lt.tm_min, lt.tm_sec, int((ts % 1) * 1000), ph, i))
        steps.append(step)
        t = (wend if mode == "wait-speech" else t1) + delay_ms / 1000.0
    return {"mode": mode, "delay_ms": delay_ms, "steps": steps}, log


def main():
    r_wait, log_wait = make_run(BASE, 0, "wait-speech", fast=False)
    r_fast, log_fast = make_run(BASE + 60.0, 300, "fast", fast=True)
    data = {"phone_tz_s": 0, "clock_offset_s": 0.0, "density": 2.75, "runs": [r_wait, r_fast]}
    with open(os.path.join(HERE, "sweep_sample.json"), "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    junk = ["10-09 08:53:19.000 I/Other( 1): unrelated line", "continuation without timestamp"]
    with open(os.path.join(HERE, "log_sample.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(junk + log_wait + log_fast) + "\n")


if __name__ == "__main__":
    main()
