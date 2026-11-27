# Local accessibility evidence tools

`sweep_report.py` aligns focus-sweep events with TalkBack speech requests from logcat and produces per-element Markdown and JSON. It does not verify audible playback or prove absence of speech. Use only a test app and device you are authorized to inspect. Keep outputs local; both the log and derived reports can contain private text.

For live operation, set `ARTEMIS_TOKEN` to your local helper token and `ANDROID_SERIAL` to the explicitly selected test device for speech collection. The helper connection must use the local ADB forward. ADB comes from `PATH`, or use `ADB_PATH` to choose the executable. Never capture credential or payment screens. See the [TalkBack logger](talkback-logger/README.md) for the separate local recorder.

## Offline synthetic test

From the repository root with Python 3 (no device, token, ADB or network needed):

```sh
python -X utf8 tools/tests/make_samples.py
python -X utf8 tools/sweep_report.py --replay-sweep tools/tests/sweep_sample.json --replay-log tools/tests/log_sample.txt --out-dir tools/tests/out
python -X utf8 tools/talkback-logger/talkback_logger.py --replay tools/tests/log_sample.txt --out tools/tests/out/logger --today 2026-10-22
```

The inputs are generated fixtures, not device recordings. The first replay produces reports in `tools/tests/out`; the second exercises the logger parser and writes only to its offline output folder. This does not validate live TalkBack behavior.
