# 현재 상태 및 세션 인수인계

최종 갱신: 2026-10-07 (Asia/Seoul)

## 바로 이어서 시작하기

1. AGENTS.md → 이 문서 → DEVELOPMENT_PLAN.md → DEVELOPMENT_NOTES.md를 읽는다.
2. 검증 상세는 [VERIFICATION.md](VERIFICATION.md), 빌드/설치는 [README](../README.md)를 확인한다.
3. 실제 파일과 최신 사용자 요청을 확인한다. 신규 프로젝트를 다시 만들지 않는다.

## 사용자 요청과 현재 결론

사용자는 기능 구현 이후 홈 1안(오늘 한눈에)과 타임라인 A안의 디자인 적용을 요청했다. 2026-10-07 디자인을 Compose 소스에 반영했다. 최신 결과는 이 문서 마지막 디자인 적용 항목을 따른다.

준비 및 Phase 1~8의 기능을 구현했다. 기본 Material UI로 동작하며 앱 빌드, JVM 테스트와 Android 14 에뮬레이터 검증을 수행했다. 개인 실기기 데이터 대조와 장기 백그라운드 동작까지 검증 완료한 것은 아니다.

## 구현 상태

| 영역 | 구현 |
|---|---|
| 프로젝트 | Kotlin/Compose 단일 app, minSdk 35(Android 15 이상), target/compileSdk 36 |
| 저장소 | Room 5개 테이블, schema v1 내보내기, Raw 버전 이력, 중복 방지, 재처리/복구 |
| Calendar | 최근 30일~향후 90일, 일반/반복/종일 일정, 변경 및 조회 구간 내 삭제 반영 |
| 알림 | Listener, 원문/extras 저장, 활성 키 수명 유지, 일반 Inbox |
| 분류 | 결제/취소, 배송/송장, 예약/취소, 태그/엔티티, 보수적 미분류 |
| Health | 걸음/수면/운동 원본, 변경 토큰과 삭제 반영, 걸음 aggregate, 수면 단계 보존 |
| 화면 | Dashboard, 날짜/타입별 Timeline, 원본 포함 상세, Inbox, 동의/권한/수집 상태/삭제 설정 |
| 집계 | dirty 날짜 영속화, 변경 전후 날짜 재계산, 시간대 변경 전체 재계산 |
| 브리핑 | 날짜별 갱신, 최근 7일 비교, 입력 집계 추적, 오전 목표 작업 및 실행 시 보완 |
| 개인정보 | 동의 전 수집 안 함, 생활 데이터 서버 전송 없음, 업데이트용 인터넷 사용, 백업/기기 이전 제외, 로컬 전체 삭제 |

주요 파일은 app/src/main/java/com/lifedashboard 아래 Store.kt, LifeRepository.kt, NotificationParser.kt, Collectors.kt, LifeApplication.kt, MainActivity.kt다.

## 변경 전 기능 검증 결과 (minSdk 28 APK)

- `:app:assembleDebug`: 성공.
- `:app:testDebugUnitTest`: 25개 통과 (Domain 13, Repository 9, HealthNormalizer 3).
- `:app:connectedDebugAndroidTest`: Android 14 전용 AVD에서 6개 통과. Calendar 생성/수정/삭제, Listener 실제 전달/갱신, Health Connect 권한/조회/aggregate, 네이티브 DB, 앱 실행/화면 이동 포함.
- `:app:lintDebug`: 오류 0개. 버전 업데이트, SharedPreferences 사용 및 코드 스타일 권고 등의 경고 35개는 남아 있다.
- 최종 Debug APK 직접 설치 및 MainActivity 실행 확인. 개인 실기기는 연결되지 않았다.
- JDK 경로는 이 PC의 `C:/Users/cafea/.jdks/openjdk-21.0.2`. SDK는 local.properties에 기기별로 설정한다.
- 테스트 실행 환경은 API 34 에뮬레이터이며 Health Connect의 사용자 데이터는 비어 있었다. 실제 건강 앱 데이터 값 대조와 구분한다.
- 계측 테스트는 앱을 정리/제거할 수 있다. 수동 실행이 필요하면 생성된 APK를 다시 설치한다.

## 현재 최소 지원 버전

사용자 요청으로 Android 15(API 35) 이상으로 변경했다. Room Robolectric 테스트도 API 35로 실행한다. 기존 API 34 AVD에는 새 APK를 설치할 수 없으며, Android 15 이상 단말 실행 검증은 별도로 필요하다. 변경 후 빌드/자동 테스트 결과는 VERIFICATION.md의 최소 지원 변경 기록을 따른다.

## 현재 빌드 도구 (AGP 업그레이드 후)

AGP 9.2.1 / Gradle 9.4.1 / Kotlin 2.2.21 / KSP 2.3.2. Android Studio가 생성한 호환 플래그를 유지하며 내장 Kotlin 및 새 DSL 전환은 아직 하지 않았다. 사용 중단 예정 경고는 후속 AGP 10 전환 전에 검토한다. 앱 코드/DB 스키마/minSdk는 이번 점검에서 변경하지 않았다. 검증 결과는 VERIFICATION.md 최신 항목을 참고한다.

## 산출물

- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Room 스키마: `app/schemas/com.lifedashboard.LifeDatabase/1.json`.
- 테스트/lint 보고서: VERIFICATION.md의 경로 참고.
- 서명은 개발용 Debug 서명이다. 공개 배포용 서명, Git 커밋/원격 푸시/Release는 수행하지 않았다. 이 작업 시작 시 Git 저장소가 없었다.

## 다음 작업

- [ ] 개인 Android 단말에 APK를 설치하고 권한 부여 후 실제 일정·알림·건강 데이터를 대조한다.
- [ ] 익명화한 실제 카드/배송/예약 알림으로 파서 인식 범위를 보완한다. 원문을 로그나 문서에 남기지 않는다.
- [ ] 제조사별 백그라운드 제한, 재부팅, 권한 철회/재허용, 반복 일정 예외를 실기기에서 확인한다.
- [ ] API 35 및 target API 36 기기 호환성을 확인한다.
- [x] 사용자 선택 디자인을 적용했다. UI 실제 기기 검증은 아래 최신 항목 참고.
- [ ] 공개 배포 요청 시 별도 서명 및 배포 경로를 정한다.

## 중요한 유지 사항

- 디자인 소스 적용/빌드와 실제 기기 시각 검증 완료를 구분한다.
- 첫 schema v1 이후 배포된 DB를 변경할 때는 migration이 필요하다. destructive migration으로 데이터를 지우지 않는다.
- Raw 저장이 성공한 뒤 정규화가 실패해도 Raw를 삭제하지 않는다.
- 집계의 updatedAt=0은 dirty 상태이며 사용자 조회에 노출하지 않는다.
- 건강 변경 토큰 만료 시 이전 구간 정합성은 미확인으로 표시한다. 미조회/권한 없음/미집계를 정상 0으로 표시하지 않는다.
- Notification 키는 활성 수명 기준이다. OS가 제공하지 않은 원문 또는 앱 중단 중 사라진 알림을 복구했다고 주장하지 않는다.
- 집계 계산 시 전체 이벤트 입력을 한 번 읽는다. 장기 데이터 성능은 실측 후 날짜 범위 DAO로 개선할 수 있다.

## 작업 이력

### 2026-10-06

AGENTS.md를 기반으로 개발 계획, 개발 참고, 인수인계 문서 및 문서 갱신 규칙을 구성했다.

### 2026-10-07

사용자 요청에 따라 Phase 1~8 기능을 구현하고 기본 UI와 APK를 만들었다. 자동/에뮬레이터 검증과 주요 데이터 경계 테스트를 추가했다. 실제 기기 확인 항목과 알려진 한계를 문서로 분리했다.

## 이후 갱신 방법

개발 중 결정/주의사항은 DEVELOPMENT_NOTES.md, 범위/기준은 DEVELOPMENT_PLAN.md, 현재 상태/다음 작업/검증은 이 문서와 VERIFICATION.md를 함께 갱신한다. 완료 여부는 실제 근거로 판단한다.



## 2026-10-07 — 실기기 반복 오류 분석 인수인계

- 사용자 요청은 반복 오류의 원인 분석. 앱 소스/APK 변경이나 설치는 하지 않았다.
- 정적 분석으로 알림 extras 변환의 메인 스레드 실행 및 앱 예외 처리 누락, 알림마다 집계 작업 추가와 전체 데이터 반복 조회를 확인했다. 실제 충돌과의 인과관계는 미확정이다. 상세는 DEVELOPMENT_NOTES.md 11절.
- 검증: `C:/Users/cafea/AppData/Local/Android/Sdk/platform-tools/adb.exe devices -l` 결과 연결 기기 없음. 기존 테스트 코드/검증 기록과 현재 수집·저장·화면 소스를 읽었으며 이번 분석에서 빌드/테스트/실기기 재현은 수행하지 않았다.
- [ ] 정확한 시스템 오류 문구, 기종/OS, 발생 계기 및 설치 APK 정보를 확보한다.
- [ ] 연결 후 com.lifedashboard의 crash 로그와 ApplicationExitInfo/ANR 기록으로 Exception/ANR/OOM/시스템 종료를 구분한다. 알림 원문·건강 데이터는 문서에 남기지 않는다.
- [ ] 실제 스택과 일치하는 최소 수정 후 API 35 이상에서 기존 활성 알림 재연결, 알림 연속 갱신, 건강 수집 중 알림 도착을 검증한다.
- 추가 사용자 정보: 아침에 오류 표시. 현재 회사에 있어 폰 연결 및 진단 조작 불가. 오전 예약 작업과의 연관성은 미확정이며 지금 추가 단말 검증은 진행할 수 없다.


## 2026-10-07 — 반복 오류 예상 경로 수정

- 사용자 후속 요청으로 알림 변환/콜백 예외 격리, 순서 보존 IO 처리, 수집 잠금 분리, 집계 요청 병합 및 날짜 범위 조회, UI DB 조회 예외 안내를 구현했다.
- 신규 소스: NotificationPayload.kt. 신규 테스트: NotificationPayloadTest.kt, DeriveSchedulingTest.kt 및 RepositoryTest의 날짜/시간대 집계 대조.
- Raw/이벤트 스키마와 기존 데이터를 유지한다. DB 초기화, 폰 설치, 공개 배포는 하지 않았다. 현재 경로는 Git 저장소가 아니므로 커밋도 하지 않았다.
- 기존 11절의 분석 전용 상태 이후 실제 수정한 결과이며 상세 계약/한계는 DEVELOPMENT_NOTES.md 12절을 따른다.
- [ ] 사용자가 진단 가능한 환경으로 돌아오면 수정 APK를 설치하고 아침 작업·실제 알림 수집을 확인한다.
- [ ] 다시 발생하면 실제 충돌/ANR 기록으로 현재 가설과 구분한다. 현재 회사에 있는 사용자에게 즉시 기기 조작을 요구하지 않는다.
- 최종 검증: 테스트 30개 통과, lint 오류 0/경고 35, Debug APK 빌드 성공. 체크섬과 명령은 VERIFICATION.md의 반복 오류 보완 항목 참고. 실기기 재현 및 설치는 미수행.


## 2026-10-07 — GitHub 업로드 및 앱 업데이트 요청

- 업데이트 화면과 GitHub 릴리즈 조회/다운로드/설치 검증 구현 완료. 버전 0.2.0 (2). 새 소스 AppUpdater.kt, UpdateScreen.kt 및 업데이트 FileProvider 설정.
- 로컬 Git 저장소 main 초기화 및 게시 대상 파일 검토. 키·토큰·DB·APK·local.properties·로그는 추적 대상에서 제외했다.
- 테스트 38개 통과, lint 오류 0/경고 37, Debug APK 빌드 성공. Release 스크립트 PowerShell 문법 검사 통과. 현재 API 35 이상 에뮬레이터가 없어 UI/설치 실동작은 미검증.
- [ ] 사용자 답변: 저장소 공개 여부 및 기존 Debug 서명 사용 승인/새 전용 키 선택.
- [ ] 확정된 서명 설정과 공개 인증서 지문을 적용하고 Release 빌드 및 서명을 검증한다.
- [ ] cafealpa/lifeEventApp 저장소 생성/소스 푸시, v0.2.0 태그와 APK/update.json/SHA256SUMS 릴리즈 게시.
- [ ] 공개 API/파일을 인증 없이 다시 내려받아 버전과 SHA-256 대조.
- 자동 승인 검토는 Debug 키의 Release 지속 사용만 차단했다. 화면/다운로드/테스트 작업은 완료했으며 서명 정책 확정 전 공개 게시하지 않는다.


### GitHub 배포 승인 반영

- 공개 저장소 생성/푸시 완료: https://github.com/cafealpa/lifeEventApp (main).
- 사용자가 기존 Debug 키로 Release 서명하는 방식을 명시 승인했다. Release debuggable=false, 인증서 지문 고정. 새 키 생성이나 앱 삭제는 하지 않았다.
- 앞의 공개 범위/서명 승인 대기는 해소됐다. APK 릴리즈 및 공개 다운로드 검증을 진행한다.


### v0.2.0 배포 완료

- 저장소: https://github.com/cafealpa/lifeEventApp
- 릴리즈: https://github.com/cafealpa/lifeEventApp/releases/tag/v0.2.0
- 태그 v0.2.0 소스 커밋: 71ea05f. 버전 0.2.0 / versionCode 2 / Android 15 이상.
- LifeDashboard.apk, update.json, SHA256SUMS.txt 게시 완료. 인증 없이 latest API와 APK를 재다운로드하여 GitHub digest/메타데이터/파일 크기/SHA-256 일치 확인.
- APK: 25,355,224 bytes; SHA-256 4f00620b3e0672cb16efce65a794c4d74a784376ae9d1e55a93c6b9b40d67c35.
- 이전의 공개 범위/서명 승인/게시 대기 항목은 완료됐다. 실제 키는 게시하지 않았으며 Release APK는 사용자 승인한 기존 개발 인증서로 서명하고 디버깅은 비활성화했다.
- 기존 0.1.0에는 업데이트 화면이 없어 이번 APK는 직접 설치해야 한다. 이후 설정 → 앱 업데이트에서 새 정식 릴리즈를 확인한다.
- 실기기 설치/업데이트 및 아침 오류 재현은 여전히 미검증이다. 현재 Android 15 이상 테스트 이미지도 없어 UI 실동작 검증을 빌드/단위 테스트로 대체했다고 주장하지 않는다.


## 2026-10-07 — 대시보드 및 타임라인 디자인 적용

- 홈 1안 / 타임라인 A안 스타일을 적용했다. 공통 LifeTheme, 카드, 선 아이콘과 화면 구성은 DesignScreens.kt에 있다.
- 홈은 브리핑 → 2열 지표 → 오늘 일정 → 생활 알림 → 수집 상태 순서. 오늘 일정 Flow를 Store.kt와 LifeViewModel에 연결했다.
- 하단 홈/타임라인, 상단 설정, 홈 생활 알림의 알림 보관함. 상세/원본과 설정의 업데이트/권한/삭제 기능 유지.
- 타임라인은 날짜 선택 달력 및 시간 연결선 목록. 기존 하루 범위 최신순/100건 페이징을 유지하며 시안의 여러 날짜 동시 노출과는 구분한다.
- 기존 DB schema v1, 앱 버전 0.2.0(2), 수집/파서 유지. 공개 릴리즈와 폰 설치는 수행하지 않았다.
- [ ] API 35 이상에서 실제 화면/큰 글꼴/필터/날짜 선택/알림 보관함/원본 상세를 확인한다.
- [ ] 배포 요청 시 새 버전 코드를 부여하고 기존 승인된 서명으로 별도 릴리즈한다.
- 검증 명령 및 결과는 VERIFICATION.md의 디자인 적용 항목 참고.
- 최종 검증: Debug 앱/계측 APK 빌드 성공, 단위 테스트 39개 통과, lint 오류 0/경고 37, diff 공백 검사 통과. Android 15 이상 단말이 없어 실제 시각/조작 검증은 미수행.

## 2026-10-07 — v0.3.0 릴리즈 준비

- 사용자 요청으로 디자인 변경을 GitHub 정식 릴리즈에 게시한다.
- versionName 0.3.0 / versionCode 3. 기존 승인된 서명 및 DB schema v1 유지.
- 릴리즈 설명: docs/releases/v0.3.0.md. 테스트·Release lint/build·서명 확인 후 게시한다.
- 실기기 설치와 시각 검증은 미수행 상태를 유지한다. 문제 발생 시 데이터 삭제를 유도하지 않고 버전 코드가 더 높은 수정판으로 배포한다.

## 2026-10-07 — v0.3.0 공개 배포 완료

- 릴리즈: https://github.com/cafealpa/lifeEventApp/releases/tag/v0.3.0
- 소스 태그: v0.3.0 → 3684bd0. versionName 0.3.0 / versionCode 3 / Android 15 이상.
- 소스/main/tag 및 LifeDashboard.apk/update.json/SHA256SUMS.txt 공개 게시 완료. 기존 서명 및 DB schema v1 유지.
- scripts/release.ps1 -Publish 성공. 단위 테스트 39개, Release lint/build 통과, 인증서/패키지/버전/디버깅 비활성 검증 통과.
- 인증 없는 latest API 및 공개 APK 재다운로드 검증 통과. 메타데이터/크기/GitHub digest/SHA-256 및 공개 SHA256SUMS.txt 일치.
- APK 25,404,376 bytes, SHA-256 89edd6a4ee4f2674bd152d78bfe38c20d71a243b8db332509c2e924c794a33aa.
- 0.2.0 사용자는 설정 → 앱 업데이트에서 확인 가능. 실기기 설치/시각 검증은 미수행.
- 동일 공개 저장소에 동일 산출물 범위로 게시하는 후속 릴리즈 요청은 재확인 없이 진행하도록 사용자 승인됨.
