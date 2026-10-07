# 기능 검증 기록

검증일: 2026-10-07 (Asia/Seoul)

## 변경 전 기능 검증 환경

- Windows, JDK 21.0.2, Gradle Wrapper 8.14.3.
- Android 14(API 34) x86_64 테스트 AVD `familychat_test`, 화면 1080×2340.
- minSdk 28, targetSdk/compileSdk 36. 이 절은 minSdk 변경 전 APK의 검증 이력이다. 현재 최소 지원은 아래 변경 기록의 API 35다.

## 자동 검증

실행 명령:

```powershell
$env:JAVA_HOME = 'C:/Users/cafea/.jdks/openjdk-21.0.2'
./gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug --console=plain
```

JDK 경로는 검증 환경의 실제 경로이며 다른 PC에서는 해당 PC에 맞게 설정한다.

변경 전 기능 검증 결과: 단위/Room/건강 정규화 테스트 25개, Android 계측 테스트 6개 통과. 최종 변경 후 같은 명령으로 재확인했고 모두 통과했다. Android lint는 오류 0개, 경고 35개(버전/스타일 및 SharedPreferences 권고 등)다.

### JVM 및 Room 테스트

- 결제 금액과 잔액 구분, 승인/취소, 모호한 금액·광고·승인 실패의 보수적 처리.
- 그룹 알림의 구조화 제외, 배송 상태/송장, 예약 취소 및 없는 날짜 비생성.
- STEP/STEP_SUMMARY 중복 합산 방지, 수면 종료일 귀속, 수면 단계의 깨어 있는 시간 제외.
- 운동 자정 분할 및 겹치는 세션 시간 병합, 취소 일정 제외, 원화 승인/취소 차액.
- Raw A→B→A 버전 보존, 같은 원본 재수집/재처리 중복 방지.
- 파싱 실패 후 Raw 보존, 미처리 Raw 복구, 실패 원본이 다른 정상 원본 처리를 막지 않음.
- 재분류 후 동일 LifeEvent ID 유지 및 태그·엔티티 정합성.
- 일정 날짜 이동 후 이전/새 날짜 집계 갱신, 증분 집계와 전체 재계산 일치.
- 브리핑 날짜별 ID 유지, 삭제 상태 재처리 유지, 로컬 전체 삭제.
- 파일 DB를 닫았다 열어도 Raw와 LifeEvent 유지.
- 실제 SDK Steps/Sleep/Exercise record의 JSON 직렬화 및 메타데이터·시간대·수면 단계 보존.

### Android 14 계측 테스트

- 네이티브 SQLite 저장, Raw 재처리, Timeline 조회, 집계/브리핑 생성.
- 실제 Calendar Provider에 테스트 일정 생성 → 재수집 → 제목/날짜 수정 → 삭제 반영. 테스트 Calendar는 종료 시 제거.
- MainActivity 실행.
- 설정 이용 안내, Timeline 날짜 조회, 알림함 화면 이동.
- 시스템 shell 합성 알림의 NotificationListener 수집 및 동일 알림 갱신 시 LifeEvent 중복 방지.
- 실제 Health Connect 권한 및 기록 조회, 변경 토큰, 30개 날짜의 걸음 집계, 재수집 중복 방지. 사용자 건강 기록이 없는 테스트 환경이므로 실제 건강 앱 값과의 대조는 아니다.

## 아직 별도 검증이 필요한 범위

- 개인 실기기의 은행/카드/택배/예약 앱별 실제 알림 형식과 제조사 백그라운드 제한.
- Samsung Health 등 실제 제공 앱의 수면·걸음·운동 및 다중 소스 우선순위와 집계 대조.
- 반복 일정 예외 및 계정 변경의 다양한 실제 패턴, 권한 철회/재부팅/장기 Doze 상태.
- 현재 최소 지원 Android 15 및 최신 타깃 버전 단말에서의 UI 및 권한 동작.
- 기존 배포 DB로부터의 migration: 최초 schema v1이므로 업그레이드 경로는 아직 없다.
- 장기간 대용량 데이터 성능, 공개 배포용 서명 및 스토어 권한 심사.

## 오류 분석 기록

- 초기 컴파일 시 화면 검색 조건 클래스 `Query`가 Room `@Query`와 충돌했다. `TimelineQuery`로 분리했다.
- JUnit 테스트 함수가 Boolean을 반환하던 부분을 `runBlocking<Unit>`으로 수정했다.
- UiDevice shell 알림 입력의 공백/따옴표로 예상 텍스트와 실제 전달 텍스트가 달랐다. 공백 없는 고유 테스트 토큰을 사용한 뒤 실제 Listener 테스트가 통과했다.

## 보고서 위치

- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/reports/androidTests/connected/debug/index.html`
- `app/build/reports/lint-results-debug.html`
- `app/build/outputs/apk/debug/app-debug.apk`

빌드 출력은 재생성 가능한 로컬 산출물이다. 최종 보고서와 APK 존재를 확인했다. 최종 APK를 에뮬레이터에 다시 설치한 결과 Success, MainActivity cold launch는 Status: ok였다.

변경 전 APK SHA-256: `e2af59d995b2679cc4af14c961edf948d489aa94f4bcefe2f6c687c40feb4815`.



## 2026-10-07 — Android 15 이상으로 최소 지원 변경

- minSdk 28 → 35(Android 15). targetSdk/compileSdk 36 유지.
- Room Robolectric 테스트를 API 34 → 35로 변경.
- `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 성공. 단위/저장소/정규화 테스트 25개 통과, lint 오류 0개.
- 병합 Manifest의 minSdkVersion=35, targetSdkVersion=36 확인.
- Debug APK 재생성 완료. 기존 Android 14 계측 테스트는 변경 전 APK의 이력이다. 새 APK의 Android 15 이상 단말/에뮬레이터 실행은 아직 미검증.
새 APK SHA-256: `d287b1e49d876455a562ae604de5e43a5f77e4584da9daa119753f631a6c1551`.

## 2026-10-07 — AGP 9.2.1 업그레이드 후 검증

- 사용자 Android Studio 변경 확인: AGP 9.2.1, Gradle 9.4.1, KSP 2.3.2. Kotlin/Compose 플러그인은 2.2.21.
- JDK 21에서 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain` 실행: BUILD SUCCESSFUL.
- JVM 테스트 25개 통과 (Domain 13, Repository 9, HealthNormalizer 3). Room Robolectric은 API 35.
- 기존 Kotlin/DSL 유지용 호환 플래그에 대한 사용 중단 예정 경고는 남아 있으며 추가 마이그레이션은 하지 않았다.
- 새 APK의 실기기/에뮬레이터 실행 및 연결 계측 테스트는 이번에 수행하지 않았다. 이전 Android 14 계측 결과와 구분한다.
Lint: 0 errors, 34 warnings

APK SHA-256: `7b008ed283a4ccc952523fd04a707653d74436047dc995623ba88abc1cffb560`.

## 2026-10-07 — 반복 오류 예상 경로 보완 검증

- JDK 21에서 최종 소스로 실행: `./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain`.
- BUILD SUCCESSFUL. JVM/Robolectric 테스트 30개 통과: Domain 13, Repository 10, HealthNormalizer 3, NotificationPayload 3, DeriveScheduling 1. 실패/오류 0.
- lint 오류 0개, 경고 35개. 기존 AGP/Kotlin 호환 플래그 사용 중단 예정 경고는 유지한다.
- 알림 부분 직렬화 실패, 배열 원본 보존, 순환 extras, 연속 알림 작업 병합/동일 알림 중복 무시/최종 변경 반영, 날짜 및 시간대 경계 집계를 자동 검증했다. WorkManager 테스트 환경의 요청 상태 검사와 실제 제조사 백그라운드 실행은 구분한다.
- APK: `app/build/outputs/apk/debug/app-debug.apk`. SHA-256: `ae93bbe5a6f48a93b4353a9ba01f5cb2287abf130f500770fb991f430a22e50a`.
- 최종 실행 로그: `build-stability-final.log`. 테스트 XML/HTML 및 lint 보고서는 기존 app/build 보고서 경로.
- 실기기 연결/설치, Android 15 이상 에뮬레이터 실행, 아침 오류 재현은 수행하지 않았다. 사용자 회사 환경으로 단말 진단 불가. 원래 오류의 원인은 여전히 미확정이며 이번 결과는 예상 실패 경로 수정 및 자동 검증이다.


## 2026-10-07 — 업데이트 페이지 로컬 검증

- JDK 21: `./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain` → BUILD SUCCESSFUL.
- 총 38개 테스트 통과 (기존 30 + AppUpdaterTest 8). 버전 코드 비교, 잘못된 패키지/태그/초안/프리릴리즈/다운로드 URL/크기/해시 거부 및 정상 캐시·잘린 파일·같은 크기 변조 파일을 확인했다.
- lint 오류 0, 경고 37. 로그 `build-updater-tests.log`.
- scripts/release.ps1 PowerShell Parser 문법 오류 0.
- Git staged 파일 이름 검사에서 keystore/jks/signing.properties/local.properties/DB/log/APK/env 없음. 흔한 토큰/비밀키 패턴 검사 일치 없음.
- Android SDK 이미지는 API 30/34만 있어 현재 minSdk 35 앱의 에뮬레이터 UI 및 설치 동작은 이번에 확인하지 않았다.
- 실제 GitHub 저장소와 Release 게시, Release APK 서명, 공개 API/다운로드 및 실기기 설치는 정책 답변 이후 검증할 항목이다.


### 사용자 승인 후 Release 검증

- `./scripts/release.ps1`: 테스트 38개, lintRelease, assembleRelease 성공.
- APK package=com.lifedashboard, versionName=0.2.0, versionCode=2, debuggable=false를 aapt2로 확인했다.
- apksigner 검증 성공. 기존 Debug APK와 Release 인증서 SHA-256이 일치하며 release-signing-certificate.sha256의 고정값과 대조했다.
- 실제 키는 Git 추적/게시 대상에서 제외했다. 기존 Debug 키로 개인 배포하는 정책은 사용자가 명시 승인했다.
- release-output/v0.2.0에 LifeDashboard.apk, update.json, SHA256SUMS.txt 생성 완료. 게시 후 최종 공개 파일 재검증 결과를 추가한다.


### 공개 릴리즈 검증 완료

- `./scripts/release.ps1 -Publish`: BUILD SUCCESSFUL, main/tag 푸시, draft 생성 후 정식 latest 릴리즈 게시 성공.
- `scripts/verify-release.ps1 -Tag v0.2.0`의 인증 없는 HTTP 호출로 latest API tag=v0.2.0, draft=false, prerelease=false 확인.
- APK 공개 재다운로드 25,355,224 bytes. SHA-256 `4f00620b3e0672cb16efce65a794c4d74a784376ae9d1e55a93c6b9b40d67c35`, update.json 및 GitHub asset digest 일치.
- Release lint 오류 0, 경고 37. 자동 테스트 38개 통과. 게시 로그 publish-release.log.
- 태그 v0.2.0은 소스 커밋 71ea05f를 가리킨다. 이후 배포 결과 문서만 별도 main 커밋으로 기록한다.

## 2026-10-07 — 홈 1안 / 타임라인 A안 디자인 적용

실행 환경: JDK `C:/Users/cafea/.jdks/openjdk-21.0.2`, 기존 Android SDK 36. 기본 명령 실행기가 X: 볼륨 인증 오류로 실패하여 승인된 sandbox 밖 실행으로 동일 프로젝트를 검증했다.

- 명령: `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest`
- 결과: BUILD SUCCESSFUL. 단위 테스트 39개, 실패 0개. lint 오류 0개, 경고 37개.
- 새 Room 회귀 검증: 홈 일정 조회의 종일 날짜 처리, 시간순 정렬, 취소 및 다음 날짜 제외.
- UI 계측 테스트 소스는 새 상단 설정/홈 알림 보관함/날짜 버튼 경로로 갱신하고 테스트 APK까지 빌드했다. 계측 실행은 하지 않았다.
- `git diff --check`: 공백 정리 후 통과.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
- SHA-256: `ea566c21dd81b8b368566372f162610f0f5a283c81f967ede5878bb2f3f348c4`.
- `adb devices -l`: 연결 기기 없음. 설치된 시스템 이미지는 API 30/34이고 현재 최소 지원 API 35에 맞는 AVD가 없다.
- 미검증: 실제 화면 렌더링/스크린샷, 작은 화면과 큰 글꼴, 달력 선택/필터/상세 이동 실동작, 실기기 설치. 빌드와 단위 테스트 결과로 대체 완료 처리하지 않는다.
- 공개 릴리즈, 소스 푸시 및 실기기 설치는 수행하지 않았다. 버전은 기존 0.2.0(2) 유지.
