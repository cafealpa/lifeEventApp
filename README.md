# Life Dashboard

Android에서 일정, 알림, 건강 기록을 수집하여 Raw → LifeEvent → Timeline/일간 집계/브리핑으로 저장하는 로컬 앱이다. 기능 우선 MVP이며 디자인은 기본 Material 3 UI다.

## 시작하기

1. Android Studio에서 이 폴더를 연다.
2. JDK 21, Android SDK 36을 준비하고 `local.properties`에 로컬 `sdk.dir`를 설정한다. 이 파일은 Git에 포함하지 않는다.
3. `./gradlew.bat :app:assembleDebug`로 빌드한다.
4. `app/build/outputs/apk/debug/app-debug.apk`를 Android 15(API 35) 이상 단말에 설치한다.
5. 앱의 설정에서 이용 안내에 동의하고 수집을 시작한다. 일정·알림 접근·건강 읽기 권한을 필요한 기능별로 허용한다.
6. 새로고침 후 Dashboard, 날짜별 Timeline, 알림함에서 수집 결과와 수집 상태를 확인한다.

Health Connect 기능은 해당 기기의 지원 및 설치 상태에 따라 사용 가능하다. MVP 건강 수집은 걸음·수면·운동 읽기 권한 3개가 모두 필요하다. 백그라운드 건강 읽기는 지원 기기에서 추가 권한을 허용해야 하며, 없으면 앱 실행 시 갱신한다.

## 개발 환경

| 항목 | 버전 |
|---|---|
| applicationId | com.lifedashboard |
| min / compile / target SDK | 35 / 36 / 36 |
| Gradle / AGP | 9.4.1 / 9.2.1 |
| Kotlin / KSP | 2.2.21 / 2.3.2 |
| Compose BOM / Room | 2025.06.01 / 2.7.2 |
| Health Connect / WorkManager | 1.1.0 / 2.10.1 |

AGP Upgrade Assistant의 호환 설정(`android.builtInKotlin=false`, `android.newDsl=false`)으로 기존 Kotlin Android 플러그인을 유지한다. 내장 Kotlin 전환은 아직 수행하지 않았으며 AGP 10 이전에 별도 검토한다. 최신 버전 자동 추종보다 검증한 조합을 고정한다. 실제 빌드에 사용한 JDK는 21이고 앱 바이트코드는 Java 17 대상으로 설정했다.

## 검증 명령

```powershell
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
# 전용 테스트 에뮬레이터를 연결한 뒤 실행
./gradlew.bat :app:connectedDebugAndroidTest
```

계측 테스트는 테스트용 Calendar를 만들고 제거하며 앱 데이터 삭제, 알림 접근 설정, 건강 권한 부여를 수행한다. 개인 실기기 대신 전용 테스트 AVD에서 실행한다. 변경된 데이터 스키마는 `app/schemas`에 보관한다. 이미 배포한 버전의 스키마를 변경할 때에는 Room migration을 추가한다.

## 주요 코드

- `Store.kt`: Room 5개 테이블과 DAO.
- `LifeRepository.kt`: 원본 버전 보존, 정규화, 재처리, 날짜별 집계, 브리핑.
- `NotificationParser.kt`: PAYMENT/DELIVERY/RESERVATION 규칙 파서.
- `Collectors.kt`: Calendar, Health Connect, NotificationListener 및 건강 정규화.
- `LifeApplication.kt`: 수집 동기화, 상태 기록, WorkManager 작업.
- `MainActivity.kt`: 기능 중심 Compose 화면과 ViewModel.

## 문서

- [개발 지침](AGENTS.md)
- [개발 계획 및 구현 상태](docs/DEVELOPMENT_PLAN.md)
- [데이터 규칙과 개발 참고](docs/DEVELOPMENT_NOTES.md)
- [현재 상태 및 다음 작업](docs/SESSION_HANDOFF.md)
- [검증 범위와 한계](docs/VERIFICATION.md)

업데이트 확인과 APK 다운로드에만 인터넷을 사용하며 생활 데이터는 서버로 보내지 않는다. 자동 백업/기기 이전에서 앱 데이터를 제외한다. 앱 설정의 전체 삭제는 앱 내부 데이터만 지우고 수집을 중지한다. 원래 Calendar/Health Connect 데이터를 삭제하지 않는다. GitHub 배포용 APK와 개발용 Debug APK를 구분한다. 스토어 배포는 현재 범위가 아니다.



## 앱 업데이트

설정 → 앱 업데이트에서 GitHub의 최신 정식 릴리즈를 확인한다. 최신 버전의 변경 내용과 다운로드 진행률을 보여주며 설치는 Android의 확인 화면에서 사용자가 승인한다.

- 저장소: `cafealpa/lifeEventApp`.
- 릴리즈 태그: `vX.Y.Z`. `versionCode`가 설치된 앱보다 클 때만 업데이트를 제공한다.
- 릴리즈 자산: `LifeDashboard.apk`, `update.json`, `SHA256SUMS.txt`.
- APK 크기/SHA-256, 패키지명, 버전, 설치 앱과 같은 서명 인증서를 확인한다. 다른 서명은 앱 삭제를 유도하지 않고 차단한다.
- 최초 설치 허용 설정에서 돌아오면 설치 화면을 연다. 설치 취소 후 완성된 APK는 재사용한다. 앱 재시작 후 최신 릴리즈를 다시 확인하면 같은 APK를 검증하여 재사용한다. 중단된 다운로드의 바이트 단위 이어받기는 지원하지 않는다.
- GitHub 인증 토큰을 APK에 넣지 않는다. 로그인 없는 업데이트는 공개 저장소/공개 릴리즈를 전제로 한다.

## 릴리즈 만들기

`app/build.gradle.kts`의 versionCode를 증가시키고 versionName을 변경한 뒤 `docs/releases/vX.Y.Z.md`에 변경 내용을 작성한다. 같은 버전의 공개 APK를 덮어쓰지 않는다.

```powershell
# JDK 21 및 local.properties의 SDK 경로 필요
./scripts/release.ps1
# 소스 커밋 완료 및 origin 설정 후 GitHub에 소스/태그/릴리즈 게시
./scripts/release.ps1 -Publish
# 공개 API와 APK 재다운로드 검증만 다시 실행
./scripts/verify-release.ps1 -Tag v0.2.0
```

스크립트는 테스트/lint/Release 빌드, 인증서 지문, APK 패키지/버전/디버깅 비활성 상태를 검사하고 release-output에 자산을 만든다. 서명키 파일과 암호는 Git에 올리지 않는다. `release-signing-certificate.sha256`은 비밀키가 아닌 공개 인증서 지문이며, 다른 PC에서 실수로 다른 키를 사용하는 것을 막는다. 사용자가 기존 데이터 유지 우선으로 승인한 기존 로컬 Debug 키로 Release APK를 서명한다. Release APK의 debuggable은 false다. 이 키를 잃거나 다른 PC의 자동 생성 Debug 키를 쓰면 업데이트 호환성이 깨진다. 현재 키를 별도로 안전하게 백업하고 이후에도 같은 키를 사용해야 한다. 전용 배포 키/키 회전으로의 전환은 별도 계획 없이 수행하지 않는다.
