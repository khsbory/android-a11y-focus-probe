# TalkBack 읽은 문구 기록기

폰의 TalkBack이 음성 합성을 요청한 문구를 PC에 기록합니다. 실제 음성 재생을 증명하지는 않습니다. (TalkBack 로그 출력 수준 VERBOSE 필요, 노드 트리 디버깅은 꺼 둠)

저장소 루트에서 PowerShell로 아래 명령을 실행합니다. Python과 Android Platform Tools가 필요합니다. `adb`가 PATH에 없으면 `ADB_PATH`에 실행 파일 경로를 지정하세요. 기기가 여러 대 연결돼 있으면 `ANDROID_SERIAL`로 대상 한 대를 지정해야 기록이 시작됩니다.

- 켜기: `powershell -File .\tools\talkback-logger\start_logger.ps1` (이미 켜져 있으면 안내만 함)
- 끄기: `powershell -File .\tools\talkback-logger\stop_logger.ps1` (이 폴더의 기록기와 그 adb만 종료)
- 상태: `powershell -File .\tools\talkback-logger\status_logger.ps1` (실행 여부, 폰 연결, 오늘 파일 크기, 마지막 기록 시각)

기록과 상태 파일은 도구 폴더의 `logs`에 로컬로 저장되며 Git에서 제외됩니다. 기존 독립 기록기가 실행 중이면 두 인스턴스를 동시에 켜지 않도록 기존 기록기를 종료한 뒤 전환하세요. 화면 탐색은 [별도 순회 도구](../README.md)를 사용합니다.

## 기록 위치와 형식
- `logs\YYYY-MM-DD.txt` : 날짜별 파일(폰 로그 시각 기준, 자정에 다음 날 파일로 넘어감). 한 줄에 `HH:MM:SS 읽은 문구`.
- `logs\logger-state.json` : 마지막 기록 시각(재시작 때 중복 방지). 지우지 마세요.
- `logs\logger-run.log` : 기록기 동작 로그(1MB 순환).

## 동작
- 폰이 연결될 때까지 기다리고, 끊기면 멈췄다가 다시 연결되면 자동 재시작합니다. 폰 버퍼에 남은 최근 로그도 한 번 읽습니다.
- 오류가 나도 5초 뒤 다시 시도합니다. PC 시작 때 자동 실행은 설정하지 않았습니다.

## 주의
- 모든 앱에서 읽은 문구가 기록되므로 이름, 금액 등 개인 값이 파일에 들어갈 수 있습니다.
- 자동 삭제는 없습니다. 날짜별 파일이라 오래된 파일은 직접 지우면 됩니다.
- 여러 줄 문구는 한 줄로 합쳐 기록합니다. 줄이 이어지지 않고 끊긴 경우 끝에 `[partial]`이 붙습니다.
