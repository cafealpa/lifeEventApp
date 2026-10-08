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

## 2026-10-07 — v0.3.0 Release 검증

- `JAVA_HOME=C:/Users/cafea/.jdks/openjdk-21.0.2`, `./scripts/release.ps1 -Publish` 성공.
- `:app:testDebugUnitTest :app:lintRelease :app:assembleRelease`: 성공. 테스트 39개 통과. 고정 인증서·applicationId·versionName 0.3.0·versionCode 3·디버깅 비활성 검사 통과.
- 공개 latest 릴리즈가 v0.3.0이며 draft/prerelease가 아님을 확인. 인증 없이 APK 재다운로드 후 update.json, GitHub digest, 길이, SHA-256 일치. 공개 SHA256SUMS.txt도 대조 통과.
- 최종 게시 APK: 25,404,376 bytes; SHA-256 `89edd6a4ee4f2674bd152d78bfe38c20d71a243b8db332509c2e924c794a33aa`.
- 커밋 후 패키징 시 Git 버전 정보가 갱신되므로 사전 로컬 준비 APK와 최종 게시 APK 해시는 다르다. 위 해시는 태그 3684bd0에서 게시하고 공개 재다운로드한 최종 파일 기준이다.
- 실기기 설치/업데이트와 화면 시각 검증은 미수행.

## 2026-10-07 — 지역화폐 결제 파서 보완 검증

- 수정 전 `:app:testDebugUnitTest --tests com.lifedashboard.DomainTest`에서 추가한 3개 테스트가 실패하여 단위 없는 결제 누락/인센티브 오인을 재현했다. 실제 결제 원문은 테스트에 저장하지 않았다.
- 수정 후 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain`: BUILD SUCCESSFUL. 테스트 44개, 실패/오류 0.
- 원 단위 유무, 제목/본문 결제 문구, 취소, 잘못된 금액·충전·예정·실패·광고·그룹 알림 제외, 기존 가맹점 표기 호환성 검증.
- Room 통합 검증: parserVersion 1 일반 알림 → v2 결제 재분류, id/rawEventId 보존, 원본/이벤트 중복 없음, 결제액만 합산하고 취소 차감. 인센티브는 합산/차감하지 않음.
- Debug APK: app/build/outputs/apk/debug/app-debug.apk. 버전은 0.3.0 유지. 공개 릴리즈/실기기 설치는 수행하지 않았으며 기존 공개 APK에는 수정이 포함되지 않음.
- 실기기의 실제 extras 형태와 업데이트 후 과거 기록 대조는 미검증. 저장된 원문이 있어야 재분석 가능.

## 2026-10-07 — 상세 정보 접기/JSON pretty print

- `./gradlew.bat :app:assembleDebug :app:lintDebug --console=plain`: BUILD SUCCESSFUL.
- 상세 대화상자 UI만 수정했으며 새 단위 테스트는 추가하지 않았다. 직전 파서/저장소 테스트 44개 통과는 이전 수정의 검증 결과다.
- `git diff --check` 통과. Debug APK 갱신 완료. 폰 설치·UI 실동작·공개 배포는 미수행.

## 2026-10-07 — v0.3.1 공개 릴리즈 검증

- `./scripts/release.ps1 -Publish`: 성공. 테스트 44개 통과, Release lint/build 성공, 기존 고정 서명/패키지/0.3.1(4)/디버깅 비활성 검사 통과.
- 공개 latest 릴리즈 v0.3.1, 인증 없는 APK 재다운로드 및 크기/metadata/GitHub digest/SHA-256 일치. 공개 SHA256SUMS.txt 추가 대조 통과.
- APK 25,420,760 bytes; SHA-256 `fc129392c17f85eb3400b78d433d188e0ee6dbd118c193aba50c8e0dc07610e6`.
- 소스 태그 31f725f. 폰 설치·업데이트·실제 알림 원문 대조는 미수행.

## 2026-10-07 — 광고 알림 분류 검증

- `./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain`: BUILD SUCCESSFUL.
- 단위/Room 테스트 47개 통과. 광고 접두사 우선 판별, 제목/중간/다른 표기 제외, 그룹·상시 광고 분류 검증.
- 기존 PAYMENT(v2)를 ADVERTISEMENT(v3)로 재분석하고 결제 집계 제외, 광고 보관함 필터 조회, 낮은 중요도, 원본/이벤트 ID 보존, 중복 방지를 검증했다.
- lint 오류 0/경고 37, diff 공백 검사 통과. Debug APK 갱신. 실기기 UI/수집 검증 및 공개 배포는 미수행.

## 2026-10-07 — 제목 광고 분류 확장 검증

- `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain`: BUILD SUCCESSFUL, 테스트 47개 통과.
- 제목만/본문만/둘 다 접두사 일치, 빈 본문, 그룹 알림, 제목 중간 일치와 앞 공백 제외 검증.
- 이전 v3 제목 광고 결제를 v4 광고로 재분류해 원본·ID 유지, 광고 필터 조회와 결제 합계 제외 검증.
- Debug APK 갱신. 실기기/공개 배포 미수행. 이번 작은 조건 변경에서는 lint를 재실행하지 않았으며 직전 광고 구현의 lint 통과 기록과 구분한다.

## 2026-10-08 — 알림 수동 분류 검증

- `:app:assembleDebug`, `:app:testDebugUnitTest :app:lintDebug --console=plain`: BUILD SUCCESSFUL.
- 전체 테스트 50개 통과. 새 Room 통합 테스트 3개: 수동 선택 재처리/파서 버전 복구/원문 갱신 유지 및 자동 복원, 결제 승인·취소와 각 분류 이동의 집계/필터 반영, 금액 누락·음수·미지원 유형·비알림 변경 거절과 원래 데이터 보존.
- 원문/ID 유지, 중복 없음, repository 재생성 이후 선택 유지 확인. DB schema v1 유지.
- lint 오류 0, 경고 37. diff 공백 검사 통과. Debug APK 갱신.
- API 35 이상 실제 단말의 분류 선택 UI/원문 수집/설치 검증과 공개 릴리즈는 미수행.

## 2026-10-08 — 집계 중 표시값 유지 검증

- `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain`: BUILD SUCCESSFUL.
- 전체 테스트 54개 통과. 새 4개 테스트: dirty 상태 반복 시 이전 값 유지/완료 후 교체, 최초 미집계와 날짜 분리, 전체 삭제 후 캐시 제거/재등장 방지, 정상 0값 반영과 삭제 날짜 제거.
- lint 오류 0/경고 37, diff 공백 검사 통과. Debug APK 갱신.
- 실제 단말 연속 수집 중 배지와 화면 갱신, 공개 릴리즈는 미수행. 저장값 계산 및 DB 스키마 변경 없음.

## 2026-10-08 — v0.4.0 배포 전 검증

- `scripts/release.ps1`: testDebugUnitTest 57개 통과(실패/오류 0), lintRelease 오류 0/경고 38, assembleRelease 성공.
- 신규 경고는 HomeAppLinks의 SharedPreferences.edit KTX 사용 제안이며 기능 오류가 아니다.
- 기존 고정 인증서 SHA-256 일치, 패키지 com.lifedashboard/versionCode 5/versionName 0.4.0 및 non-debuggable 검사 통과.
- `git diff --check` 통과. 실제 API 35 이상 단말 설치/외부 앱 실행/화면 동작은 미검증.

### v0.4.0 공개 검증

`scripts/release.ps1 -Publish` 성공. 소스 5887392 및 v0.4.0 태그 게시, 정식/latest 공개 확인. verify-release.ps1이 인증 없는 APK 재다운로드와 크기/SHA-256/메타데이터/GitHub digest를 검증했고 공개 SHA256SUMS.txt도 추가 대조했다. 최종 APK: 25,502,712 bytes, eca7f7cdd4c9e29105367a7f579e97e754e6f2393c60158acd611241610547f7. 실기기 설치/외부 앱 실행 미검증.

## 2026-10-08 — 일정 중복 회귀 검증

`:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain` 성공. 전체 61개 통과, 실패/오류 0. 새 Room 회귀 테스트 4개는 원본 유지 및 중복 표시/집계/브리핑 제외, 재분석, 대표 삭제/편집 복원, 삭제 감지 후보, 장소 충돌과 빈 장소 연결, 시간/반복 발생일/종일 기간 구분, 구버전 중복 복구를 검증했다. 실제 사용자 단말 데이터 및 UI는 미검증. `git diff --check` 통과.

## 2026-10-08 — 파서 규칙 검증

`:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain` 성공. 전체 67개 통과(실패/오류 0), lint 오류 0/경고 39. 새 테스트 6개에서 결제 완료+배송 안내/금액 라벨 우선, 앱/제목·본문/접두사/제외 조건, 순서/비활성/광고·그룹 보호, 잘못된·모호한 금액, 규칙 유효성/직렬화, 영속화 및 규칙 삭제 후 기본 복원, 원본 보존, 수동 분류 우선과 자동 복원 결과를 확인했다. 실제 단말 화면/알림 원문 검증과 공개 배포는 미수행.

## 2026-10-08 — 알림 보관 기간 검증

`:app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain` 성공. 전체 70개 통과(실패/오류 0). 신규 테스트 3개는 정확한 14일/30일 경계 및 1ms 이전 보존, 발생 시각 기준, 반복 실행, Raw 여러 revision/태그/엔티티 삭제와 재분석 복원 방지, 현재 수동 분류에 따른 보존/삭제, 다른 소스 보존을 검증했다. `git diff --check` 통과. 실제 단말/OS 주기 실행과 공개 릴리즈 미수행.

## 2026-10-08 — 타임라인 정렬

전체 단위 테스트 72개 및 assembleDebug 성공. 신규 Room 테스트 2개로 LIMIT 이전 양방향 정렬/105건 더 보기, 수면 종료 시각과 동일 시각 ID 정렬, 분류 필터 확인. diff 검사 통과. 실기기 및 공개 배포 미수행.

## 2026-10-08 — 집계 최적화 검증

전체 테스트 76개, assembleDebug/lintDebug 성공. 광고/기타 및 동일 집계 입력의 메타데이터 변경 시 집계 생략, 결제 금액/분류 변경 시 재계산, 새 날짜/타임존/브리핑 누락 복구, 정상 수집 후 5분 경계 검증. DeriveSchedulingTest는 일반 알림을 예약하지 않는 새 정책에 맞게 수정하고 결제 연속 변경/동일 입력 중복 수신/마지막 갱신 예약을 검증했다. diff 검사 통과. 700ms 배지와 단말 복귀 UI는 실기기 미검증, 공개 릴리즈 미수행.

## 2026-10-08 — v0.5.0 공개 릴리즈 검증

`scripts/release.ps1 -Publish` 성공. 76개 테스트 통과, Release lint 오류 0/경고 39, 기존 서명·패키지·버전·non-debuggable 확인. cf3c67b 소스와 v0.5.0 태그/정식 latest 게시. 공개 APK 25,682,932 bytes, SHA-256 a4db5f4dd112b2cf88433a866eb66edc39c018d8cd7707d91b7a31c1a1038055. 인증 없는 재다운로드/메타데이터/GitHub digest/SHA256SUMS.txt 일치 확인. 실제 단말 설치 및 실행 검증은 미수행.

## 2026-10-08 — 오늘 브리핑 v2 검증

전체 테스트 78개와 assembleDebug/lintDebug 성공, diff 검사 통과. TodayBriefingTest에서 일정 시작/진행/종료 경계, 시간대 인사, 오늘 활동/결제 순액/배송 알림 수·상태, 건강 기록 미확인을 검증했다. 기존 일정 중복 및 RefreshPolicyTest를 v2 브리핑 기준으로 갱신했으며, 저장 시각만 바뀌는 결제 메타데이터는 재생성을 유발하지 않는지 확인했다. 실제 단말 시각 레이아웃 및 분 단위 UI 갱신/공개 릴리즈 미수행.

## 2026-10-08 — 요약 메뉴형 설정 검증

JDK `C:/Users/cafea/.jdks/openjdk-21.0.2`에서 `./gradlew.bat :app:assembleDebug :app:lintDebug --console=plain` 성공(34초). lint 오류 0/경고 41. `git diff --check` 통과. 변경은 화면 구성과 탐색이며 추가 단위 테스트/기존 단위 테스트 재실행은 하지 않았다. `adb devices -l` 결과 연결 기기 없음. 실제 렌더링/큰 글꼴/권한 복귀/뒤로가기 조작 및 공개 릴리즈는 미수행. APK는 `app/build/outputs/apk/debug/app-debug.apk`.

## 2026-10-08 — v0.6.0 로컬 릴리즈 검증

JDK 21에서 `scripts/release.ps1` 성공(Gradle 1분 54초). 단위 테스트 78개 통과(실패/오류 0), Release lint/build 성공, 기존 인증서/패키지/버전 0.6.0(7)/non-debuggable 검증 통과. 배포 산출물은 release-output/v0.6.0의 LifeDashboard.apk/update.json/SHA256SUMS.txt다. 실기기 미검증. 공개 재다운로드 검증은 사용자 명시 요청으로 생략한다.

### v0.6.0 게시 결과

소스 태그 6404052 및 정식 v0.6.0 게시 성공. GitHub 응답에서 draft=false/prerelease=false, APK/메타데이터/체크섬 uploaded 확인. APK 크기 25,732,196 bytes 및 GitHub digest와 로컬 SHA-256 일치. 공개 재다운로드 검증은 사용자 요청으로 수행하지 않았다. Release lint 오류 0/경고 41.

## 2026-10-08 — 홈 카드 통합 및 브리핑 종료 검증

JDK 21에서 `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --console=plain` 성공. 후속 테스트 추가 뒤 `:app:testDebugUnitTest` 재실행 성공. 홈 일정 시작/종료 및 종일 구분, 최근 활성 배송 상태와 완료 알림 수, 브리핑 없이 집계 완료, 기존 BRIEFING 행 보존 및 타임라인 제외를 검증했다. lint 오류 0/경고 41, `git diff --check` 통과. 계측 테스트는 빌드만 수행했으며 단말 렌더링/기존 아침 예약 취소 실동작/공개 배포는 미수행.

## 2026-10-08 — v0.7.0 로컬 릴리즈 검증

JDK 21에서 scripts/release.ps1 성공(Gradle 1분 16초). 단위 테스트/Release lint/build 및 기존 인증서·패키지·버전 0.7.0(8)·non-debuggable 검증 통과. 배포 파일은 release-output/v0.7.0에 생성했다. 사용자 요청으로 공개 APK 재다운로드는 생략한다. 단말 검증은 미수행.

### v0.7.0 게시 결과

79개 테스트 통과, Release lint 오류 0/경고 41. 소스 태그 b48c3d5 및 정식/latest v0.7.0 게시 완료. GitHub API 응답으로 공개 상태와 3개 자산 업로드 확인, APK digest 및 로컬 SHA-256 일치. 사용자 요청대로 재다운로드 검증은 미수행.

## 2026-10-08 — 알림 개별 삭제 검증

JDK 21에서 `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain` 성공(35초). NotificationDeletionTest 4개로 원본 전체 이력/태그/엔티티 삭제, 다른 알림 보존, 재분석 비복원, 결제 취소 삭제 후 순합계, 수동 분류별 삭제, Calendar 보호를 확인했다. diff 검사 통과. 실제 단말 UI/알림 재전달 경계 및 공개 배포는 미수행.
