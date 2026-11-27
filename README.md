# Android Accessibility Focus Probe

Formerly `artemis-helper-min`; package ID `com.artemis.helpermin` and header `X-Artemis-Token` are kept for compatibility. See [Security](SECURITY.md).

An on-device helper for human-supervised Android accessibility testing. It reads accessibility nodes, moves focus, and runs bounded focus sweeps. It is designed to coexist with TalkBack without acquiring `UiAutomation` or using UIAutomator. Its tree traversal approximates TalkBack; node labels and gesture callbacks do not establish what TalkBack said. It is not a screen reader, an AI agent, or a conformance checker.

## Build and install

Requirements: Android 7.0+ (API 24; target SDK 35), Bash (Git Bash on Windows), JDK with `javac` and `keytool`, Python 3, Android SDK Platform 35 or newer, Build Tools (`aapt2`, `d8`, `zipalign`, `apksigner`), and Platform Tools (`adb`). Some node properties require newer Android versions. Set `ANDROID_HOME` if needed. No Gradle is required.

Build from reviewed source without a shared signing key. The build creates a local debug key if none is present; never commit it or use it for production signing. Android requires the same signer for updates; a different signing key may require uninstalling an earlier test build, which removes its local data.

```sh
bash build_apk.sh
adb install ArtemisHelperMin.apk
```

Use only devices and apps you own or are authorized to test. Enable the helper manually in Android Settings → Accessibility. Keep TalkBack enabled for tests that require it. The accessibility service can read on-screen information and perform actions; use a separate test profile with synthetic data.

## Local API

The device server binds to `127.0.0.1:18889`. Use only a local ADB forward on a trusted workstation, never a LAN listener, public proxy or tunnel. Replace `REPLACE_WITH_A_FRESH_LOCAL_TEST_TOKEN` with a unique high-entropy token locally. The token is kept in memory and must be configured again after a process restart. Keep your actual token out of shared files and logs.

```sh
adb shell am broadcast -n com.artemis.helpermin/.TokenReceiver \
  -a com.artemis.helpermin.SET_TOKEN --es token REPLACE_WITH_A_FRESH_LOCAL_TEST_TOKEN
adb forward tcp:18889 tcp:18889
curl --fail http://127.0.0.1:18889/health
```

The table describes v0.1.18. Supply the `X-Artemis-Token` header except for status probes. Use JSON for structured inputs. Treat even read-only output as potentially sensitive; never send credentials or run state-changing calls on sensitive screens.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| GET | `/`, `/ping`, `/health` | Local status; no token required |
| GET | `/focus/current`, `/popup` | Current accessibility focus or dialog summary |
| GET or POST | `/inspect` | Inspect visible window and node properties |
| POST | `/focus/first`, `/focus/last`, `/focus/next`, `/focus/prev` | Traverse focus; next/previous can filter by category |
| POST | `/focus/to`, `/focus/swipe` | Focus a matching target or dispatch a navigation gesture |
| POST | `/sweep`, `/sweep/stop` | Start or stop bounded traversal (scrolling is opt-in) |
| GET | `/sweep/last` | Read the most recent sweep result |
| POST | `/scroll` | Scroll a container |
| GET | `/actions` | List custom accessibility actions |
| POST | `/actions/run`, `/activate`, `/activate/read`, `/act/at-text` | Run an action or activate a control; these can change app state |
| POST | `/type`, `/global` | Test text input in the built-in demo only, or run supported system navigation |
| GET | `/guard/status`, `/fix/ghost-pointers/info` | Guard status or experimental diagnostic information |
| POST | `/guard/reset`, `/fix/ghost-pointers` | Guard reset or experimental gesture diagnostic, not needed for ordinary audits |

When finished, run `adb forward --remove tcp:18889` and disable the helper in Accessibility Settings.

## Demo and versions

`DemoDefectsActivity` is a synthetic in-app screen with good controls and deliberately flawed examples, including an unnamed control and a custom action that changes status without an accessibility announcement. It has no launcher icon. Start it on the test device with `adb shell am start -n com.artemis.helpermin/.DemoDefectsActivity`. Compare node evidence with direct TalkBack listening. See [CHANGELOG.md](CHANGELOG.md) for development history; this document covers the default branch baseline, not later experiments.

Historical checks of the synthetic demo found that `tb` traversal matched direct TalkBack swipe order at 11/11 compared positions in v0.1.9 and 12/12 in v0.1.10 (with the demo link). These were small, version-specific checks, not general accuracy results or a new device retest.

## Tools

- [`tools/sweep_report.py`](tools/README.md) aligns focus-sweep events with timestamped TalkBack speech requests and writes per-element Markdown and JSON reports. Missing trace evidence is not proof of silence.
- [`tools/talkback-logger`](tools/talkback-logger/README.md) keeps requested speech text locally. It may capture unrelated on-screen text, including from the existing log buffer. Never share raw logs, state files, or reports containing identifiable data.

Run the [offline synthetic replay](tools/README.md#offline-synthetic-test) without a phone, ADB, or token.

## Author and license

Hyongsop Kim, [AccessBridge](https://accessbridge.co.kr/). [MIT License](LICENSE) for original material. Apache-licensed portions are identified in [NOTICE](NOTICE). This is not an official Google, TalkBack, or Apache project.

## 한국어 요약

Android에서 TalkBack을 켠 채 접근성 포커스와 화면 요소 속성, 탐색 순서를 살펴보는 도구입니다. `UiAutomation`을 쓰지 않으며, 노드 정보나 제스처 콜백을 실제 TalkBack 발화로 간주하지 않습니다. 합성 시연 화면 `DemoDefectsActivity`에서 먼저 확인하세요.

접근성 서비스는 화면을 읽고 동작을 수행할 수 있습니다. 허가된 테스트 기기·앱에서만 쓰고, 신뢰할 수 있는 PC의 로컬 ADB 포트포워딩으로 연결하세요. 사용 후에는 포트포워딩과 서비스를 해제하세요. 실제 발화·화면 데이터·토큰·기기 식별자·서명 키는 공유하지 마세요.
