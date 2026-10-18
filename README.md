# CallScheduler (통화 녹음 자동 분석 및 일정 등록 앱 v2)

통화 종료 후 생성되는 통화 녹음 파일을 자동 감지하여 STT(음성 인식) 및 일정·할 일·콜백을 추출하고 Google 캘린더에 등록하는 안드로이드 앱입니다.

## 지원 파일명 형식
- `통화 이름_날짜_통화종료시간.m4u` (예: `통화 김대리_261018_121018.m4u`, `통화 홍길동_20261018_1210.m4u`)
- `통화 녹음_이름_전화번호_날짜_시간.m4a` 등 다양한 녹음 파일명 및 `.m4u`, `.m4a`, `.amr`, `.mp3`, `.wav`, `.ogg` 확장자 지원

## 모바일 환경에서 APK 자동 빌드 방법 (GitHub Actions)
1. 이 저장소에 코드가 업로드되면 `.github/workflows/main.yml` 워크플로우가 자동 실행되어 `app-debug.apk`를 빌드합니다.
2. GitHub 저장소 상단 **Actions** 탭 → **Build CallScheduler Debug APK** 실행 결과 하단 **Artifacts**에서 `CallScheduler-debug-apk`를 내려받아 설치하세요.
