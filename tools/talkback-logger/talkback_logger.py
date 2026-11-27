# -*- coding: utf-8 -*-
"""TalkBack speech logger.

Follows `adb logcat -v time` of the connected phone (TalkBack log level VERBOSE)
and appends every phrase TalkBack speaks to one file per day: logs/YYYY-MM-DD.txt
(the date comes from the phone log time; midnight starts a new file).

Output format inside each daily file (no date line, the file name is the date):
    12:53:03 some spoken phrase

Usage:
    python talkback_logger.py                      # live mode (runs forever)
    python talkback_logger.py --replay FILE --out OUT_DIR [--today YYYY-MM-DD]   # test mode
"""
import argparse
import collections
import json
import logging
import logging.handlers
import os
import re
import subprocess
import sys
import threading
import time
from datetime import datetime, timedelta, date

# ----------------------------- settings -----------------------------
ADB_PATH = os.environ.get("ADB_PATH") or "adb"
ANDROID_SERIAL = os.environ.get("ANDROID_SERIAL") or None
RETRY_SECONDS = 5                      # wait between retries / device polls
WATCHDOG_SECONDS = 10                  # how often to verify the device is still attached
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
LOG_DIR = os.path.join(BASE_DIR, "logs")
DEFAULT_OUT = LOG_DIR                  # daily files are written into this directory
DEFAULT_STATE = os.path.join(LOG_DIR, "logger-state.json")
RUN_LOG = os.path.join(LOG_DIR, "logger-run.log")
RUN_LOG_MAX_BYTES = 1024 * 1024        # 1 MB rotation for the run log
# --------------------------------------------------------------------

log = logging.getLogger("tblogger")

# Example: "10-18 12:53:03.070 V/talkback: SpeechControllerImpl(17287): Speaking fragment text="...", utteranceId=talkback_90, ..."
LINE_RE = re.compile(r"^(?:(?P<year>\d{4})-)?(?P<stamp>\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}) [VDIWEF]/(?P<body>talkback(?=\s*[:(]).*)$")
SPEAK_RE = re.compile(r'Speaking fragment text="(.*)", utteranceId=(\S+?),')
HEAD_RE = re.compile(r'Speaking fragment text="(.*)$')

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def setup_logging(console):
    os.makedirs(LOG_DIR, exist_ok=True)
    log.setLevel(logging.INFO)
    fmt = logging.Formatter("%(asctime)s %(levelname)s %(message)s")
    fh = logging.handlers.RotatingFileHandler(
        RUN_LOG, maxBytes=RUN_LOG_MAX_BYTES, backupCount=1, encoding="utf-8")
    fh.setFormatter(fmt)
    log.addHandler(fh)
    if console:
        sh = logging.StreamHandler(sys.stderr)
        sh.setFormatter(fmt)
        log.addHandler(sh)


class Recorder:
    """Parses logcat lines, de-duplicates, and appends formatted records."""

    def __init__(self, out_dir, state_path, today=None):
        self.out_dir = out_dir
        self.state_path = state_path
        self.today = today or date.today()
        self.state = {"last_ts": "", "last_keys": [], "last_date": ""}
        self.recent = collections.deque(maxlen=500)   # in-run duplicate guard
        self.recent_set = set()
        self.pending = None  # (datetime, prefix, text) for multi-line fragments
        self._load_state()
        os.makedirs(out_dir, exist_ok=True)
        self.fh = None
        self.fh_day = None

    def _load_state(self):
        try:
            with open(self.state_path, "r", encoding="utf-8") as f:
                data = json.load(f)
            for k in self.state:
                if k in data:
                    self.state[k] = data[k]
        except FileNotFoundError:
            pass
        except Exception as e:  # corrupt state: start fresh but keep going
            log.warning("state file unreadable (%s); starting fresh", e)

    def _save_state(self):
        tmp = self.state_path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.state, f)
        os.replace(tmp, self.state_path)

    def _make_dt(self, m):
        if m['year']:
            try:
                return datetime.strptime(m['year'] + '-' + m['stamp'], '%Y-%m-%d %H:%M:%S.%f')
            except ValueError:
                return None
        mo, d, h, mi, s, ms = (int(x) for x in re.findall(r'\d+', m['stamp']))
        now = datetime.combine(self.today, datetime.min.time()) + timedelta(hours=12)
        year = self.today.year
        try:
            dt = datetime(year, mo, d, h, mi, s, ms * 1000)
        except ValueError:
            return None
        # A log date far in the future means it belongs to the previous year (year boundary).
        if dt > now + timedelta(days=2):
            try:
                dt = dt.replace(year=year - 1)
            except ValueError:
                return None
        return dt

    def close(self):
        try:
            if self.fh:
                self.fh.close()
        except Exception:
            pass
        self.fh = None
        self.fh_day = None

    def _file_for(self, day):
        if self.fh_day != day:
            self.close()
            self.fh = open(os.path.join(self.out_dir, day + ".txt"), "a",
                           encoding="utf-8", newline="\n")
            self.fh_day = day
        return self.fh

    def feed(self, raw):
        """Process one logcat line (str, without newline)."""
        m = LINE_RE.match(raw)
        if not m:
            return
        rest = m['body']
        marker = 'Speaking fragment text='
        # continuation of a multi-line fragment
        if self.pending is not None:
            pending_dt, prefix, first = self.pending
            if marker not in rest and rest.startswith(prefix) and self._make_dt(m) == pending_dt:
                text = first + " " + rest[len(prefix):]
                sm = SPEAK_RE.search(marker + '"' + text)
                if sm:
                    self._emit(self.pending[0], sm.group(1), sm.group(2))
                    self.pending = None
                else:
                    self.pending = (self.pending[0], self.pending[1], text)
                return
            self._flush_pending()
        idx = rest.find(marker)
        if idx < 0:
            return
        dt = self._make_dt(m)
        if dt is None:
            return
        sm = SPEAK_RE.search(rest)
        if sm:
            self._emit(dt, sm.group(1), sm.group(2))
            return
        hm = HEAD_RE.search(rest)
        if hm:
            self.pending = (dt, rest[:idx], hm.group(1))

    def _flush_pending(self):
        if self.pending is not None:
            dt, _, text = self.pending
            self.pending = None
            # Fragment never completed (e.g. continuation lines filtered out): keep the visible part.
            self._emit(dt, text + " [partial]", "partial")

    def flush(self):
        self._flush_pending()

    def _emit(self, dt, text, utt):
        text = " ".join(text.split())
        if not text:
            return
        ts = dt.strftime("%Y-%m-%d %H:%M:%S.") + "%03d" % (dt.microsecond // 1000)
        key = "%s|%s" % (utt, text)
        last = self.state["last_ts"]
        if last:
            if ts < last:
                return
            if ts == last and key in self.state["last_keys"]:
                return
        rkey = (ts[:19], utt, text)
        if rkey in self.recent_set:
            return
        if len(self.recent) == self.recent.maxlen:
            self.recent_set.discard(self.recent[0])
        self.recent.append(rkey)
        self.recent_set.add(rkey)

        day = dt.strftime("%Y-%m-%d")
        fh = self._file_for(day)
        fh.write("%s %s\n" % (dt.strftime("%H:%M:%S"), text))
        fh.flush()
        self.state["last_date"] = day
        if ts == last:
            self.state["last_keys"].append(key)
        else:
            self.state["last_ts"] = ts
            self.state["last_keys"] = [key]
        self._save_state()


# ----------------------------- adb helpers -----------------------------
def adb_devices():
    """Return list of serials in 'device' state."""
    try:
        out = subprocess.run([ADB_PATH, "devices"], capture_output=True, timeout=15,
                             creationflags=NO_WINDOW).stdout.decode("utf-8", "replace")
    except Exception as e:
        log.warning("adb devices failed: %s", e)
        return []
    serials = []
    for ln in out.splitlines()[1:]:
        parts = ln.split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.append(parts[0])
    return serials


def pick_device(preferred=None):
    serials = adb_devices()
    if preferred:
        return preferred if preferred in serials else None
    if len(serials) > 1:
        log.warning("Multiple devices connected; set ANDROID_SERIAL to choose one")
    return serials[0] if len(serials) == 1 else None


def follow(rec, serial):
    """Follow logcat for one device until it ends. Buffered history is included at start."""
    cmd = [ADB_PATH, "-s", serial, "logcat", "-v", "time", "-v", "year", "talkback:V", "*:S"]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                            creationflags=NO_WINDOW)
    stop = threading.Event()

    def watchdog():
        while not stop.wait(WATCHDOG_SECONDS):
            if serial not in adb_devices():
                log.info("device %s detached; stopping logcat", serial)
                try:
                    proc.kill()
                except Exception:
                    pass
                return

    t = threading.Thread(target=watchdog, daemon=True)
    t.start()
    try:
        for chunk in iter(proc.stdout.readline, b""):
            line = chunk.decode("utf-8", "replace").rstrip("\r\n")
            try:
                rec.feed(line)
            except Exception as e:
                log.exception("line processing error: %s", e)
    finally:
        stop.set()
        rec.flush()
        try:
            proc.kill()
        except Exception:
            pass
        try:
            proc.wait(timeout=5)
        except Exception:
            pass


def run_live(out_dir, state_path):
    log.info("logger started (pid %d)", os.getpid())
    rec = None
    preferred = ANDROID_SERIAL
    while True:
        try:
            if rec is None:
                rec = Recorder(out_dir, state_path)
            serial = pick_device(preferred)
            if not serial:
                time.sleep(RETRY_SECONDS)
                continue
            preferred = serial  # do not switch phones during reconnects
            log.info("device connected: %s", serial)
            follow(rec, serial)
            log.info("logcat stream ended; will retry")
        except Exception as e:
            log.exception("unexpected error: %s", e)
        time.sleep(RETRY_SECONDS)


def run_replay(path, out_dir, state_path, today):
    rec = Recorder(out_dir, state_path, today=today)
    n = 0
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            rec.feed(line.rstrip("\r\n"))
            n += 1
    rec.flush()
    rec.close()
    print("replayed %d lines -> %s" % (n, out_dir))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--replay", help="read a saved logcat file instead of a live phone (test mode)")
    ap.add_argument("--out", default=DEFAULT_OUT, help="output directory for daily files")
    ap.add_argument("--state", help="state file path (default: derived from --out in test mode)")
    ap.add_argument("--today", help="override today's date (YYYY-MM-DD) for year inference")
    ap.add_argument("--console", action="store_true", help="also log to stderr")
    a = ap.parse_args()
    if a.replay:
        state = a.state or (a.out.rstrip("\\/") + ".state.json")
        today = datetime.strptime(a.today, "%Y-%m-%d").date() if a.today else None
        run_replay(a.replay, a.out, state, today)
        return
    setup_logging(a.console)
    run_live(a.out, a.state or DEFAULT_STATE)


if __name__ == "__main__":
    main()
