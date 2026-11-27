#!/usr/bin/env python
"""sweep_report.py - walk the current screen with the Artemis helper, record what
TalkBack requested for speech (from adb logcat) per element, and write a diagnosis report.

Run:  python -X utf8 -I sweep_report.py [options]
Console output is ASCII/English only. Korean appears only in the generated report files.
"""
import argparse
import calendar
import copy
import datetime
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

DEFAULT_DENSITY = 2.75
HTTP_TIMEOUT = 8
ADB_TIMEOUT = 30

# Configurable word lists (override with --word-lists FILE.json; each key replaces the default list).
DEFAULT_WORDS = {
    # short role word TalkBack says alone when a node has no label
    "role_words": ["목록", "버튼", "이미지", "제목", "탭", "링크", "스위치", "확인란", "편집창", "표", "그리드",
                   "체크박스", "선택", "라디오 버튼", "메뉴", "대화상자",
                   "list", "button", "image", "heading", "tab", "link", "switch", "checkbox",
                   "edit box", "table", "grid", "menu", "dialog", "radio button"],
    # generic names that are not a real accessible name for a clickable node
    "generic_words": ["버튼", "이미지", "아이콘", "button", "image", "icon", "그림"],
    # state words that must be present when a node is checkable/selected/expandable
    "state_words": ["선택됨", "선택 안 됨", "선택 해제", "확인됨", "확인 안 됨", "해제", "펼침", "접힘",
                    "켜짐", "꺼짐", "checked", "unchecked", "not checked", "selected", "not selected",
                    "expanded", "collapsed", "on", "off"],
    # unit words: '500,014' followed by one of these is merged into one phrase
    "unit_words": ["원", "개", "명", "건", "%", "퍼센트", "점", "시간", "분", "초", "일", "월", "년", "회", "번",
                   "위", "달러", "세", "층", "호", "km", "m", "kg", "g", "mb", "gb", "kb"],
    # regexes (case-insensitive) for speech that is not part of the screen content
    "offscreen_patterns": [r"https?://", r"www\.", r"\.(com|net|org|co\.kr|kr)\b", r"새 알림", r"알림이 도착",
                           r"notification", r"배터리", r"battery"],
}
UNLABELED_CLASSES = {"ImageButton", "ImageView", "View"}

LOG_LINE_RE = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d{3})\s")
SPEAK_RE = re.compile(r'Speaking fragment text="(.*)", utteranceId=', re.S)
NUM_RE = re.compile(r"^\d[\d,.\s]*$")


def say(*parts):
    """Print ASCII-only text so console output stays English and never garbles."""
    text = " ".join(str(p) for p in parts)
    print(text.encode("ascii", "replace").decode("ascii"), flush=True)


class ToolError(Exception):
    pass


class HttpStatus(ToolError):
    def __init__(self, code, path):
        super().__init__("HTTP %s for %s" % (code, path))
        self.code = code


# ----------------------------------------------------------------- HTTP helper
class Helper:
    def __init__(self, host, port, token):
        self.base = "http://%s:%d" % (host, port)
        self.token = token

    def call(self, method, path, body=None, timeout=HTTP_TIMEOUT):
        data = None
        headers = {"X-Artemis-Token": self.token}
        if method == "POST":
            data = json.dumps(body if body is not None else {}).encode("utf-8")
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                raw = resp.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            raise HttpStatus(e.code, path)
        except (urllib.error.URLError, OSError, TimeoutError) as e:
            raise ToolError("helper unreachable or timed out (%s %s): %s" % (method, path, e))
        try:
            return json.loads(raw) if raw.strip() else {}
        except ValueError:
            raise ToolError("helper returned non-JSON for %s %s" % (method, path))


# ------------------------------------------------------------- node utilities
def norm_bounds(b):
    if b is None:
        return ""
    if isinstance(b, str):
        return b
    if isinstance(b, dict):
        keys = ("left", "top", "right", "bottom")
        if all(k in b for k in keys):
            return "[%s,%s][%s,%s]" % tuple(b[k] for k in keys)
    if isinstance(b, (list, tuple)) and len(b) == 4:
        return "[%s,%s][%s,%s]" % tuple(b)
    return json.dumps(b, ensure_ascii=False)


def first_of(n, *keys):
    for k in keys:
        if k in n and n[k] is not None:
            return n[k]
    return None


def norm_node(n):
    n = n or {}
    child = n.get("childText") or n.get("childrenText") or ""
    if isinstance(child, list):
        child = " ".join(str(c) for c in child)
    lvl = first_of(n, "headingLevel", "level")
    st = n.get("states") if isinstance(n.get("states"), dict) else {}
    return {
        "i": n.get("i"),
        "enabled": bool(st.get("enabled", True)),
        "a11yFocused": bool(n.get("a11yFocused")),
        "t_epoch_ms": n.get("t_epoch_ms"),
        "text": n.get("text") or "",
        "contentDescription": n.get("contentDescription") or n.get("desc") or "",
        "class": n.get("class") or n.get("className") or "",
        "viewId": n.get("viewId") or n.get("id") or "",
        "bounds": norm_bounds(n.get("bounds")),
        "clickable": bool(n.get("clickable")),
        "childText": child,
        "checkable": bool(n.get("checkable")),
        "checked": bool(n.get("checked") or st.get("checked")),
        "selected": bool(n.get("selected") or st.get("selected")),
        "expandable": bool(first_of(n, "expandable", "expanded", "collapsed")),
        "heading": bool(first_of(n, "heading", "isHeading", "headingFlag")),
        "headingLevel": lvl if lvl not in (None, "") else None,
    }


def node_key(n):
    return (n["viewId"], n["bounds"], n["text"], n["contentDescription"], n["class"])


def norm_actions(a):
    """Return list of action label strings from various response shapes."""
    if isinstance(a, dict):
        for k in ("actions", "customActions", "items"):
            if isinstance(a.get(k), list):
                a = a[k]
                break
        else:
            return []
    out = []
    for item in a or []:
        if isinstance(item, str):
            out.append(item)
        elif isinstance(item, dict):
            lab = item.get("label") or item.get("name") or item.get("description") or item.get("id")
            if lab:
                out.append(str(lab))
    return out


def norm_action_runs(a):
    out = []
    for r in a or []:
        if isinstance(r, dict) and "t1" in r:
            out.append({"label": str(r.get("label", "")), "t0": float(r.get("t0", r["t1"])), "t1": float(r["t1"])})
    return out


def parse_bounds(b):
    nums = re.findall(r"-?\d+", b or "")
    return tuple(int(x) for x in nums) if len(nums) == 4 else None


def contains(outer, inner):
    return outer[0] <= inner[0] and outer[1] <= inner[1] and outer[2] >= inner[2] and outer[3] >= inner[3]


def tokens(t):
    return set(x for x in (norm_text(w) for w in re.split(r"\s+", t or "")) if len(x) >= 2)


UNLABELED_SPEECH = ("라벨이 지정되지 않음", "unlabeled", "라벨 없음")
DROPDOWN_GLYPHS = "▼▲▾▴⌄∨∧"


def bounds_size(b):
    nums = re.findall(r"-?\d+", b or "")
    if len(nums) != 4:
        return None
    l, t, r, bt = (int(x) for x in nums)
    return abs(r - l), abs(bt - t)


# ------------------------------------------------------------------- sweeping
LAST_SWEEP_META = {}


def sweep_via_endpoint(h, delay_ms, max_n, clock_offset=None):
    """clock_offset = pc_epoch - phone_epoch (s). Helper nodes carry t_epoch_ms (phone clock)."""
    t_scale = max(HTTP_TIMEOUT, max_n * (delay_ms / 1000.0 + 1.0) + HTTP_TIMEOUT)
    t0 = time.time()
    res = h.call("POST", "/sweep", {"delay_ms": delay_ms, "max": max_n, "actions": True, "from": "first"},
                 timeout=t_scale)
    t1 = time.time()
    if isinstance(res, dict) and res.get("success") is False:
        raise ToolError("helper /sweep reported failure: %s" % str(res)[:200])
    nodes = res.get("nodes") if isinstance(res, dict) else res
    if not isinstance(nodes, list):
        raise ToolError("unexpected /sweep response shape")
    n = len(nodes)
    LAST_SWEEP_META.clear()
    LAST_SWEEP_META.update({k: res.get(k) for k in ("count", "elapsed_ms", "stopped", "skipped", "candidates")
                            if isinstance(res, dict)})
    # per-node PC-clock start times: prefer t_epoch_ms (phone clock -> PC), else t_ms from sweep start
    starts, exact = [], True
    for i, raw in enumerate(nodes):
        te = raw.get("t_epoch_ms") if isinstance(raw, dict) else None
        tm = raw.get("t_ms") if isinstance(raw, dict) else None
        if te is not None and clock_offset is not None:
            starts.append(float(te) / 1000.0 + clock_offset)
        elif tm is not None:
            starts.append(t0 + float(tm) / 1000.0)
        else:
            exact = False
            starts.append(t0 + (t1 - t0) * i / max(n, 1))
    steps = []
    for i, raw in enumerate(nodes):
        a = starts[i]
        b = starts[i + 1] if i + 1 < n else max(t1, a)
        steps.append({"node": norm_node(raw), "actions": norm_actions(raw.get("actions")),
                      "action_runs": norm_action_runs(raw.get("actionRuns")),
                      "t0": a, "t1": b, "approx_time": not exact, "no_latency": True})
    return steps, (starts[0] if starts else t0), t1, not exact


def sweep_via_focus(h, delay_ms, max_n, waiter=None):
    steps, seen = [], set()
    t_start = time.time()
    for i in range(max_n):
        t0 = time.time()
        res = None
        if i == 0:
            try:
                res = h.call("POST", "/focus/first")
            except HttpStatus as e:
                if e.code not in (404, 405, 501):
                    raise
        if res is None:
            res = h.call("POST", "/focus/next")
        t1 = time.time()
        if not isinstance(res, dict) or not res.get("node") or res.get("performed") is False \
                or res.get("success") is False:
            if not steps:
                raise ToolError("focus call reported no node/failure on the first step")
            break
        node = norm_node(res["node"])
        key = node_key(node)
        if key in seen:
            break
        seen.add(key)
        try:
            actions = norm_actions(h.call("GET", "/actions"))
        except ToolError:
            actions = []
        step = {"node": node, "actions": actions, "action_runs": [], "t0": t0, "t1": t1, "approx_time": False}
        if waiter:
            step["wend"] = waiter(t0, t1)
        else:
            time.sleep(delay_ms / 1000.0)
        steps.append(step)
    return steps, t_start, time.time(), False


# ------------------------------------------------------------------------ adb
def adb(args_ns, *cmd, timeout=ADB_TIMEOUT):
    full = [args_ns.adb, "-s", args_ns.serial] + list(cmd)
    try:
        p = subprocess.run(full, capture_output=True, timeout=timeout)
    except FileNotFoundError:
        raise ToolError("adb not found at: %s" % args_ns.adb)
    except subprocess.TimeoutExpired:
        raise ToolError("adb timed out: %s" % " ".join(cmd))
    out = p.stdout.decode("utf-8", "replace")
    if p.returncode != 0:
        raise ToolError("adb failed (rc=%d): %s" % (p.returncode, p.stderr.decode("utf-8", "replace").strip()[:200]))
    return out


DATE_RE = re.compile(r"^(\d{9,})(?:\.(\d+|N))?\s+([+-])(\d\d)(\d\d)")


def measure_clock(args_ns):
    """Return (clock_offset_s = pc - phone, tz_seconds of phone)."""
    a = time.time()
    out = adb(args_ns, "shell", "date '+%s.%N %z'", timeout=15).strip()
    b = time.time()
    m = DATE_RE.match(out)
    if not m:  # fallback: seconds only
        a = time.time()
        sec = adb(args_ns, "shell", "date +%s", timeout=15).strip()
        b = time.time()
        z = adb(args_ns, "shell", "date +%z", timeout=15).strip()
        mz = re.match(r"([+-])(\d\d)(\d\d)", z)
        if not sec.isdigit() or not mz:
            raise ToolError("could not parse phone date output")
        phone = float(sec)
        tz = (1 if mz.group(1) == "+" else -1) * (int(mz.group(2)) * 3600 + int(mz.group(3)) * 60)
    else:
        frac = m.group(2)
        phone = float(m.group(1)) + (float("0." + frac) if frac and frac.isdigit() else 0.0)
        tz = (1 if m.group(3) == "+" else -1) * (int(m.group(4)) * 3600 + int(m.group(5)) * 60)
    return ((a + b) / 2.0) - phone, tz


def read_density(args_ns):
    """Read-only: 'wm density' -> dpi / 160. Falls back to the default."""
    if args_ns.density:
        return args_ns.density
    try:
        out = adb(args_ns, "shell", "wm density", timeout=15)
        m = re.search(r"Override density:\s*(\d+)", out) or re.search(r"Physical density:\s*(\d+)", out)
        if m:
            return int(m.group(1)) / 160.0
    except ToolError:
        pass
    return DEFAULT_DENSITY


# ------------------------------------------------------------------ log parse
def parse_speech(log_text, year, tz_s, clock_offset):
    """Return list of {ts (PC epoch), text}."""
    entries = []
    for line in log_text.splitlines():
        m = LOG_LINE_RE.match(line)
        if m:
            entries.append([m.groups(), line])
        elif entries:
            entries[-1][1] += "\n" + line
    phrases = []
    for g, body in entries:
        if "Speaking fragment text=" not in body:
            continue
        sm = SPEAK_RE.search(body)
        if not sm:
            continue
        mo, d, hh, mi, ss, ms = (int(x) for x in g)
        try:
            naive = calendar.timegm((year, mo, d, hh, mi, ss)) + ms / 1000.0
        except (ValueError, OverflowError):
            continue
        text = re.sub(r"\s*\n\s*", " ", sm.group(1)).strip()
        phrases.append({"ts": naive - tz_s + clock_offset, "text": text})
    phrases.sort(key=lambda p: p["ts"])
    return phrases


def merge_numeric_units(phrases, words):
    """'500,014' followed by '원' is one phrase, not two (no false positive)."""
    units = set(w.lower() for w in words["unit_words"])
    out, i = [], 0
    while i < len(phrases):
        p = phrases[i]
        if NUM_RE.match(p["text"]) and i + 1 < len(phrases):
            q = phrases[i + 1]
            if q["text"].strip().lower() in units and q["ts"] - p["ts"] <= 1.5:
                out.append({"ts": p["ts"], "text": p["text"] + " " + q["text"].strip()})
                i += 2
                continue
        out.append(p)
        i += 1
    return out


def norm_text(t):
    return re.sub(r"[^0-9a-zA-Z\u3131-\u318e\uac00-\ud7a3]", "", t or "").lower()


def label_all(n):
    return " ".join(x for x in (n["text"], n["contentDescription"], n["childText"]) if x).strip()


def assign_speech(steps, phrases, grace_s, words):
    """Attach phrases to steps. Returns (offscreen, ignored_outside_window)."""
    for s in steps:
        s["spoken"] = []
    pats = [re.compile(p, re.I) for p in words["offscreen_patterns"]]
    offscreen, ignored = [], 0
    if not steps:
        return offscreen, len(phrases)
    lo = min(s["t0"] for s in steps) - 0.05
    hi = max(s.get("wend", s["t1"]) for s in steps) + grace_s
    for p in phrases:
        if p["ts"] < lo or p["ts"] > hi:
            ignored += 1
            continue
        cands = [s for s in steps if s["t0"] - 0.05 <= p["ts"] <= s.get("wend", s["t1"]) + grace_s]
        if not cands:
            offscreen.append({"ts": p["ts"], "text": p["text"], "reason": "no_step"})
            continue
        best = max(cands, key=lambda s: s["t0"])  # most recently started step
        if any(rx.search(p["text"]) for rx in pats) and norm_text(p["text"]) not in norm_text(label_all(best["node"])):
            offscreen.append({"ts": p["ts"], "text": p["text"], "reason": "pattern"})
            continue
        best["spoken"].append({"ts": p["ts"], "text": p["text"], "lag_ms": (p["ts"] - best["t0"]) * 1000.0})
    return offscreen, ignored


# ------------------------------------------------------------------- diagnose
def norm_phrase(t):
    return re.sub(r"[\s.,!]+$", "", t.strip()).lower()


def diagnose(run, speech_available, slow_ms, grace_s, density, words):
    steps = run["steps"]
    role = set(w.lower() for w in words["role_words"])
    generic = set(w.lower() for w in words["generic_words"])
    state_words = [w.lower() for w in words["state_words"]]
    all_ph = run["phrases"]
    prev_joined, prev_key = None, None
    for s in steps:
        n = s["node"]
        flags = []
        label = label_all(n)
        spoken = [p["text"] for p in s["spoken"]]
        joined = " | ".join(spoken)
        joined_n = norm_text(" ".join(spoken))
        simple = n["class"].split(".")[-1]
        if speech_available:
            fallback = bool(spoken) and not label and all(
                any(u in t.lower() for u in UNLABELED_SPEECH) for t in spoken)
            if not spoken or fallback:
                flags.append("EMPTY_FOCUS" if not label else "NO_SPEECH")
            else:
                if n["clickable"] and (norm_phrase(label) in generic or all(norm_phrase(t) in generic for t in spoken)):
                    flags.append("NAME_GENERIC")
                elif not label and all(norm_phrase(t) in role for t in spoken):
                    flags.append("ROLE_ONLY")
                if not n["contentDescription"]:
                    segs = [x for x in re.split(r"[\r\n]+", n["text"] + "\n" + n["childText"]) if norm_text(x)]
                    if any(len(norm_text(x)) >= 2 and norm_text(x) not in joined_n for x in segs):
                        flags.append("SUBTITLE_MISSING")
                has_glyph = any(g in label for g in DROPDOWN_GLYPHS)
                if (n["checkable"] or n["checked"] or n["selected"] or n["expandable"] or has_glyph):
                    low = " ".join(spoken).lower()
                    if not any(w in low for w in state_words):
                        flags.append("STATE_MISSING")
        if n["clickable"] and simple in UNLABELED_CLASSES and not label:
            flags.append("UNLABELED_CLICKABLE")
        key = node_key(n)
        if speech_available and joined and prev_joined == joined and prev_key != key:
            flags.append("DUPLICATE")
        if s["actions"]:
            flags.append("CUSTOM_ACTIONS")
        if n["heading"]:
            flags.append("HEADING_MAP")
        if n["clickable"]:
            sz = bounds_size(n["bounds"])
            if sz and (sz[0] < 48 * density or sz[1] < 48 * density):
                flags.append("SMALL_TARGET")
        if speech_available:
            for ar in s.get("action_runs", []):
                if not any(ar["t1"] <= p["ts"] <= ar["t1"] + grace_s for p in all_ph):
                    flags.append("ACTION_NO_SPEECH")
                    break
        lat = (s["t1"] - s["t0"]) * 1000.0 if not s.get("approx_time") and not s.get("no_latency") and s["t1"] > s["t0"] else None
        s["latency_ms"] = lat
        if lat is not None and lat > slow_ms:
            flags.append("SLOW")
        s["flags"] = flags
        prev_joined, prev_key = joined, key
    # --- container analysis (helper nodes are flat: relate nodes by bounds containment) ---
    for i, s in enumerate(steps):
        n = s["node"]
        lab = " ".join(x for x in (n["text"], n["contentDescription"]) if x).strip()
        pb = parse_bounds(n["bounds"])
        if not lab or not pb or n["class"].split(".")[-1] in ("TextView", "Button"):
            continue
        kids = []
        for t in steps[i + 1:i + 8]:
            kb = parse_bounds(t["node"]["bounds"])
            kt = (t["node"]["text"] or t["node"]["contentDescription"]).strip()
            if kb and kt and kb != pb and contains(pb, kb):
                kids.append(kt)
        if not kids:
            continue
        lab_tok = tokens(lab)
        kid_tok = set().union(*[tokens(k) for k in kids])
        if lab_tok and kid_tok and not (lab_tok & kid_tok) and not any(norm_text(lab) in norm_text(k) or norm_text(k) in norm_text(lab) for k in kids):
            s["flags"].append("LABEL_MISMATCH")
            s["note"] = "label '%s' shares no words with child text: %s" % (lab, " / ".join(kids))
        elif speech_available:
            sp = norm_text(" ".join(p["text"] for p in s["spoken"]))
            miss = [k for k in kids if norm_text(k) and norm_text(k) not in sp]
            if miss:
                s["flags"].append("SUBTITLE_MISSING")
                s["note"] = "child text not in this node's speech: " + " / ".join(miss)
    run["screen_flags"] = []
    if steps and not any(s["node"]["heading"] for s in steps):
        run["screen_flags"].append("HEADING_MAP_NONE")


def summarize(run, duration, speech_available):
    steps = run["steps"]
    n = len(steps)
    spoken = sum(1 for s in steps if s["spoken"])
    counts = {}
    for s in steps:
        for f in s["flags"]:
            counts[f] = counts.get(f, 0) + 1
    for f in run["screen_flags"]:
        counts[f] = counts.get(f, 0) + 1
    lags = [s["spoken"][0]["lag_ms"] for s in steps if s["spoken"]]
    return {
        "nodes": n,
        "speech_available": speech_available,
        "spoken_nodes": spoken,
        "coverage_pct": (100.0 * spoken / n) if (n and speech_available) else None,
        "flag_counts": counts,
        "duration_s": duration,
        "nodes_per_sec": (n / duration) if duration > 0 else None,
        "avg_speech_lag_ms": (sum(lags) / len(lags)) if lags else None,
        "offscreen": len(run["offscreen"]),
    }


def compare_modes(runs):
    fast = next((r for r in runs if r["mode"] == "fast"), None)
    wait = next((r for r in runs if r["mode"] == "wait-speech"), None)
    if not fast or not wait:
        return None
    fmap = {node_key(s["node"]): s for s in fast["steps"]}
    rows, missing, cut = [], 0, 0
    for s in wait["steps"]:
        f = fmap.get(node_key(s["node"]))
        w_txt = " | ".join(p["text"] for p in s["spoken"])
        f_txt = " | ".join(p["text"] for p in f["spoken"]) if f else ""
        verdict = None
        if w_txt and not f_txt:
            verdict, missing = "MISSING_IN_FAST", missing + 1
        elif w_txt and f_txt and len(norm_text(f_txt)) < len(norm_text(w_txt)):
            verdict, cut = "CUT_IN_FAST", cut + 1
        if verdict:
            rows.append({"class": s["node"]["class"].split(".")[-1], "label": label_all(s["node"]),
                         "wait": w_txt, "fast": f_txt, "verdict": verdict})
    return {"fast_label": fast["label"], "wait_label": wait["label"],
            "fast_coverage": fast["summary"]["coverage_pct"], "wait_coverage": wait["summary"]["coverage_pct"],
            "missing_in_fast": missing, "cut_in_fast": cut, "rows": rows}


# --------------------------------------------------------------------- report
def md(s):
    return str(s).replace("|", "\\|").replace("\n", " ").replace("\r", " ")


def fmt(v, spec="%.1f", none="-"):
    return none if v is None else spec % v


def render_report(runs, meta, cmp_):
    L = ["# TalkBack 스윕 진단 리포트", ""]
    L.append("- 생성 시각: %s" % meta["generated"])
    L.append("- 실행 방식: %s" % meta["mode"])
    L.append("- 유예 시간(grace): %d ms / SLOW 기준: %d ms / 밀도: %.2f" % (meta["grace_ms"], meta["slow_ms"], meta["density"]))
    if not meta["speech_available"]:
        L.append("- 주의: 음성 로그를 사용할 수 없어 음성 기반 판정(NO_SPEECH 등)을 생략했습니다.")
    L.append("")
    for run in runs:
        sm = run["summary"]
        L += ["## 요약 (%s)" % run["label"], ""]
        L.append("- 노드 수: %d" % sm["nodes"])
        L.append("- 음성 커버리지: %s (%d/%d)" % (fmt(sm["coverage_pct"], "%.1f%%"), sm["spoken_nodes"], sm["nodes"]))
        L.append("- 스윕 속도: %s nodes/sec (총 %s 초)" % (fmt(sm["nodes_per_sec"], "%.2f"), fmt(sm["duration_s"], "%.2f")))
        L.append("- 평균 발화 지연: %s ms" % fmt(sm["avg_speech_lag_ms"], "%.0f"))
        if sm["flag_counts"]:
            L.append("- 플래그: " + ", ".join("%s=%d" % kv for kv in sorted(sm["flag_counts"].items())))
        else:
            L.append("- 플래그: 없음")
        if "HEADING_MAP_NONE" in run["screen_flags"]:
            L.append("- 헤딩 맵: 이 화면에는 헤딩이 하나도 없습니다 (TalkBack 헤딩 탐색 불가).")
        sw = run.get("sweep_meta") or {}
        if sw:
            L.append("- 헬퍼 sweep 응답: count=%s, candidates=%s, skipped=%s, stopped=%s" % (
                sw.get("count"), sw.get("candidates"), sw.get("skipped"), sw.get("stopped")))
            if sw.get("candidates") is not None and sw.get("count") is not None and sw["count"] < sw["candidates"]:
                L.append("- 경고: 후보 %s개 중 %s개만 방문됨 (skipped=%s). 헬퍼가 이미 접근성 포커스가 있는 첫 노드를 건너뛰었을 수 있습니다." % (
                    sw["candidates"], sw["count"], sw.get("skipped")))
        if run.get("approx_time"):
            L.append("- 참고: /sweep 응답에 tPcMs가 없어 단계 시간을 균등 분할로 추정했습니다.")
        L.append("- 화면 밖 발화(판정 제외): %d건 / 스윕 시간 밖 발화 무시: %d건" % (sm["offscreen"], run["ignored"]))
        L.append("")
    if len(runs) > 1:
        L += ["## 실행별 음성 커버리지 비교", "",
              "| 실행 | 노드 수 | 발화 노드 | 커버리지 | nodes/sec | 평균 발화 지연(ms) | 화면 밖 발화 |",
              "|---|---|---|---|---|---|---|"]
        for run in runs:
            sm = run["summary"]
            L.append("| %s | %d | %d | %s | %s | %s | %d |" % (
                run["label"], sm["nodes"], sm["spoken_nodes"], fmt(sm["coverage_pct"], "%.1f%%"),
                fmt(sm["nodes_per_sec"], "%.2f"), fmt(sm["avg_speech_lag_ms"], "%.0f"), sm["offscreen"]))
        L += ["", "빠른 스윕에서 커버리지가 크게 떨어지면 TalkBack 로그(또는 발화)가 스윕 속도를 따라가지 못한다는 뜻입니다.", ""]
    if cmp_:
        L += ["## 모드 비교: fast vs wait-speech", "",
              "- fast (%s) 커버리지: %s" % (cmp_["fast_label"], fmt(cmp_["fast_coverage"], "%.1f%%")),
              "- wait-speech (%s) 커버리지: %s" % (cmp_["wait_label"], fmt(cmp_["wait_coverage"], "%.1f%%")),
              "- fast에서 누락된 발화 노드: %d / 잘린 발화 노드: %d" % (cmp_["missing_in_fast"], cmp_["cut_in_fast"]), ""]
        if cmp_["rows"]:
            L += ["| 클래스 | 텍스트 / 설명 | wait-speech 발화 | fast 발화 | 판정 |", "|---|---|---|---|---|"]
            for r in cmp_["rows"]:
                L.append("| %s | %s | %s | %s | %s |" % (md(r["class"]), md(r["label"]), md(r["wait"]), md(r["fast"]), r["verdict"]))
            L.append("")
    for run in runs:
        L += ["## 요소별 상세 (%s)" % run["label"], "",
              "| 순서 | 클래스 | 텍스트 / 설명 | 클릭 | 커스텀 액션 | 발화 | 지연(ms) | 플래그 |",
              "|---|---|---|---|---|---|---|---|"]
        for i, s in enumerate(run["steps"], 1):
            n = s["node"]
            label = n["text"]
            if n["contentDescription"]:
                label = (label + " / " if label else "") + n["contentDescription"]
            if n["childText"]:
                label = (label + " / " if label else "") + "(child) " + n["childText"]
            L.append("| %d | %s | %s | %s | %s | %s | %s | %s |" % (
                i, md(n["class"].split(".")[-1]), md(label), "Y" if n["clickable"] else "",
                md(", ".join(s["actions"])), md(" || ".join(p["text"] for p in s["spoken"])),
                fmt(s["latency_ms"], "%.0f"), md(", ".join(s["flags"]))))
        L.append("")
        notes = [(i, s) for i, s in enumerate(run["steps"], 1) if s.get("note")]
        if notes:
            L += ["### 플래그 근거 (%s)" % run["label"], ""]
            for i, s in notes:
                L.append("- 순서 %d: %s" % (i, md(s["note"])))
            L.append("")
        heads = [(i, s) for i, s in enumerate(run["steps"], 1) if s["node"]["heading"]]
        L += ["### 헤딩 맵 (%s)" % run["label"], ""]
        if heads:
            for i, s in heads:
                L.append("- 순서 %d: 레벨 %s - %s" % (i, s["node"]["headingLevel"] or "?", md(label_all(s["node"]))))
        else:
            L.append("- 헤딩 없음")
        L.append("")
        if run["offscreen"]:
            L += ["### 화면 밖 발화 (판정 제외) (%s)" % run["label"], ""]
            for p in run["offscreen"]:
                L.append("- [%s] %s" % (p["reason"], md(p["text"])))
            L.append("")
    return "\n".join(L) + "\n"


# ----------------------------------------------------------------------- main
def parse_args(argv):
    p = argparse.ArgumentParser(description="Sweep the screen via the Artemis helper and diagnose TalkBack speech.")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=18889)
    p.add_argument("--token", default=os.environ.get("ARTEMIS_TOKEN"), help="local helper token (prefer ARTEMIS_TOKEN)")
    p.add_argument("--adb", default=os.environ.get("ADB_PATH") or "adb")
    p.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    p.add_argument("--mode", choices=["fast", "wait-speech"], default="fast")
    p.add_argument("--compare-modes", action="store_true", help="run fast and wait-speech, add a comparison section")
    p.add_argument("--delay-ms", type=int, default=300)
    p.add_argument("--max-wait-ms", type=int, default=2500, help="wait-speech: max wait per step")
    p.add_argument("--max", type=int, default=100)
    p.add_argument("--grace-ms", type=int, default=1500)
    p.add_argument("--slow-ms", type=int, default=1000)
    p.add_argument("--density", type=float, default=0.0, help="screen density (dpi/160); default: read via wm density, else 2.75")
    p.add_argument("--word-lists", help="JSON file overriding role_words/generic_words/state_words/unit_words/offscreen_patterns")
    p.add_argument("--out-dir", default="reports")
    p.add_argument("--clear-log", action="store_true", help="run 'adb logcat -c' before each sweep")
    p.add_argument("--speeds", default="", help="comma list of fast delays in ms, e.g. 300,1000")
    p.add_argument("--no-speech", action="store_true", help="skip adb speech capture")
    p.add_argument("--replay-log", help="logcat text file (offline)")
    p.add_argument("--replay-sweep", help="sweep JSON file (offline)")
    return p.parse_args(argv)


def load_words(path):
    words = copy.deepcopy(DEFAULT_WORDS)
    if path:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        for k, v in data.items():
            if k in words and isinstance(v, list):
                words[k] = [str(x) for x in v]
    return words


def make_waiter(args, off, tz, words):
    max_wait = args.max_wait_ms / 1000.0

    def wait(t0, t1):
        deadline = t1 + max_wait
        sg = time.gmtime(t0 - off + tz - 0.1)
        since = "%02d-%02d %02d:%02d:%02d.000" % (sg.tm_mon, sg.tm_mday, sg.tm_hour, sg.tm_min, sg.tm_sec)
        year = datetime.datetime.fromtimestamp(t0).year
        while True:
            out = adb(args, "shell", "logcat -d -v time -T '%s'" % since, timeout=15)
            ph = [p for p in parse_speech(out, year, tz, off) if p["ts"] >= t0 - 0.05]
            if ph:
                time.sleep(0.2)  # let the rest of the utterance land
                return time.time()
            if time.time() >= deadline:
                return time.time()
            time.sleep(0.25)
    return wait


def run_live(args, h, mode, delay_ms, speech, words):
    if mode == "wait-speech" and not speech:
        raise ToolError("wait-speech mode needs adb speech capture")
    if speech and args.clear_log:
        adb(args, "logcat", "-c")
    off_a = tz = None
    sweep_meta = {}
    density = args.density or DEFAULT_DENSITY
    if speech:
        off_a, tz = measure_clock(args)
        density = read_density(args)
    if mode == "wait-speech":
        steps, t0, t1, approx = sweep_via_focus(h, delay_ms, args.max, make_waiter(args, off_a, tz, words))
        via = "focus-loop"
    else:
        try:
            steps, t0, t1, approx = sweep_via_endpoint(h, delay_ms, args.max, off_a)
            via = "sweep"
            sweep_meta = dict(LAST_SWEEP_META)
        except HttpStatus as e:
            if e.code not in (404, 405, 501):
                raise
            steps, t0, t1, approx = sweep_via_focus(h, delay_ms, args.max)
            via = "focus-loop"
    run = {"mode": mode, "delay_ms": delay_ms, "via": via, "steps": steps, "t_start": t0, "t_end": t1,
           "approx_time": approx, "phone_tz_s": tz, "clock_offset_s": None, "density": density, "log_text": "", "sweep_meta": sweep_meta}
    if speech:
        off_b, tz2 = measure_clock(args)
        run["clock_offset_s"] = (off_a + off_b) / 2.0
        run["clock_drift_ms"] = abs(off_b - off_a) * 1000.0
        run["phone_tz_s"] = tz2
        run["log_text"] = adb(args, "logcat", "-d", "-v", "time", timeout=90)
    return run


def load_replay(args):
    with open(args.replay_sweep, "r", encoding="utf-8") as f:
        data = json.load(f)
    top = data if isinstance(data, dict) else {}
    raw_runs = top["runs"] if "runs" in top else [data]
    log_text = ""
    if args.replay_log:
        with open(args.replay_log, "r", encoding="utf-8", errors="replace") as f:
            log_text = f.read()
    runs = []
    for r in raw_runs:
        steps = []
        for s in r["steps"]:
            st = {"node": norm_node(s.get("node")), "actions": norm_actions(s.get("actions")),
                  "action_runs": norm_action_runs(s.get("action_runs")),
                  "t0": float(s["t0"]), "t1": float(s["t1"]), "approx_time": False}
            if "wend" in s:
                st["wend"] = float(s["wend"])
            steps.append(st)
        runs.append({"mode": r.get("mode", "fast"), "delay_ms": int(r.get("delay_ms", 0)), "via": "replay",
                     "steps": steps, "t_start": steps[0]["t0"] if steps else 0.0,
                     "t_end": max([s.get("wend", s["t1"]) for s in steps]) if steps else 0.0,
                     "approx_time": False, "phone_tz_s": r.get("phone_tz_s", top.get("phone_tz_s", 0)),
                     "clock_offset_s": r.get("clock_offset_s", top.get("clock_offset_s", 0.0)),
                     "density": args.density or r.get("density", top.get("density", DEFAULT_DENSITY)),
                     "log_text": log_text})
    return runs


def main(argv=None):
    args = parse_args(argv)
    replay = bool(args.replay_sweep or args.replay_log)
    if replay and not args.replay_sweep:
        say("ERROR: --replay-log requires --replay-sweep")
        return 2
    try:
        words = load_words(args.word_lists)
        if replay:
            runs = load_replay(args)
            speech_available = bool(args.replay_log)
            mode_desc = "replay"
        else:
            if args.host != "127.0.0.1":
                raise ToolError("live mode requires the local 127.0.0.1 ADB forward")
            if not args.token or not args.token.strip():
                raise ToolError("set ARTEMIS_TOKEN for live mode")
            if not args.no_speech and (not args.serial or not args.serial.strip()):
                raise ToolError("set ANDROID_SERIAL for live speech capture")
            h = Helper(args.host, args.port, args.token)
            delays = [int(x) for x in args.speeds.split(",") if x.strip()] or [args.delay_ms]
            if args.compare_modes:
                plan = [("fast", d) for d in delays] + [("wait-speech", args.delay_ms)]
            elif args.mode == "wait-speech":
                plan = [("wait-speech", args.delay_ms)]
            else:
                plan = [("fast", d) for d in delays]
            speech_available = not args.no_speech
            runs = []
            for mode, d in plan:
                say("Running sweep: mode=%s delay_ms=%d" % (mode, d))
                try:
                    run = run_live(args, h, mode, d, speech_available, words)
                except ToolError as e:
                    if speech_available and mode == "fast" and "adb" in str(e):
                        say("WARNING: speech capture failed, continuing without speech: %s" % e)
                        speech_available = False
                        run = run_live(args, h, mode, d, False, words)
                    else:
                        raise
                runs.append(run)
            mode_desc = "compare-modes" if args.compare_modes else args.mode
    except (ToolError, OSError, ValueError, KeyError) as e:
        say("ERROR: %s" % e)
        return 2

    now = datetime.datetime.now()
    grace_s = args.grace_ms / 1000.0
    # focus-loop nodes (/focus/next) carry no headingFlag; borrow it from /sweep runs on the same screen
    head_keys = set(node_key(s["node"]) for r in runs if r["via"] == "sweep" for s in r["steps"] if s["node"]["heading"])
    for r in runs:
        if r["via"] == "focus-loop":
            for s in r["steps"]:
                if node_key(s["node"]) in head_keys:
                    s["node"]["heading"] = True
                    s["heading_borrowed"] = True
    for run in runs:
        run["label"] ="%s, delay %d ms" % (run["mode"], run["delay_ms"]) if run["mode"] == "fast" else run["mode"]
        phrases = []
        if speech_available and run["log_text"]:
            ref = run["t_start"] + (run["phone_tz_s"] or 0) if replay else run["t_start"]
            year = datetime.datetime.fromtimestamp(ref, datetime.timezone.utc).year if replay else datetime.datetime.fromtimestamp(ref).year
            phrases = parse_speech(run["log_text"], year, run["phone_tz_s"] or 0, run["clock_offset_s"] or 0.0)
            phrases = merge_numeric_units(phrases, words)
        run["phrases"] = phrases
        run["log_text"] = ""  # raw logcat is huge; parsed phrases are kept instead
        run["offscreen"], run["ignored"] = assign_speech(run["steps"], phrases, grace_s, words)
        diagnose(run, speech_available, args.slow_ms, grace_s, run["density"], words)
        dur = (run["t_end"] - run["t_start"]) if run["steps"] else 0.0
        run["summary"] = summarize(run, dur, speech_available)

    cmp_ = compare_modes(runs) if speech_available else None
    meta = {"generated": now.strftime("%Y-%m-%d %H:%M:%S"), "mode": mode_desc, "grace_ms": args.grace_ms,
            "slow_ms": args.slow_ms, "speech_available": speech_available,
            "density": runs[0]["density"] if runs else DEFAULT_DENSITY, "compare": cmp_}
    try:
        os.makedirs(args.out_dir, exist_ok=True)
        stamp = now.strftime("%Y%m%d_%H%M%S")
        md_path = os.path.abspath(os.path.join(args.out_dir, "sweep_%s.md" % stamp))
        js_path = os.path.splitext(md_path)[0] + ".json"
        with open(md_path, "w", encoding="utf-8", newline="\n") as f:
            f.write(render_report(runs, meta, cmp_))
        with open(js_path, "w", encoding="utf-8", newline="\n") as f:
            json.dump({"meta": meta, "runs": runs}, f, ensure_ascii=False, indent=2)
    except OSError as e:
        say("ERROR: cannot write report: %s" % e)
        return 2

    for run in runs:
        sm = run["summary"]
        say("--- mode=%s delay_ms=%d via=%s ---" % (run["mode"], run["delay_ms"], run["via"]))
        for i, s in enumerate(run["steps"], 1):
            say("step %03d class=%s speech=%s flags=%s" % (
                i, s["node"]["class"].split(".")[-1] or "?",
                "present" if s["spoken"] else "absent", ",".join(s["flags"]) or "-"))
        say("nodes=%d coverage=%s nodes_per_sec=%s offscreen=%d ignored=%d flags=%s" % (
            sm["nodes"], fmt(sm["coverage_pct"], "%.1f%%", "n/a"), fmt(sm["nodes_per_sec"], "%.2f", "n/a"),
            sm["offscreen"], run["ignored"],
            ",".join("%s=%d" % kv for kv in sorted(sm["flag_counts"].items())) or "none"))
    if cmp_:
        say("compare: missing_in_fast=%d cut_in_fast=%d" % (cmp_["missing_in_fast"], cmp_["cut_in_fast"]))
    say("Report written: %s" % md_path)
    say("Raw data written: %s" % js_path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
