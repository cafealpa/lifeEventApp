# Health Connect 걸음 시간 범위 호환 처리 (2026-10-09)

## 확인된 원인

사용자가 v0.8.1에서 제공한 진단은 전경 수집의 STEP 원본 조회 3페이지에서 StepsRecord 생성자 검증이 실패한 것을 보여준다. 예외 위치는 SDK 1.1.0 StepsRecord 49행이며 `startTime must be before endTime.` 조건이다. 사용자 건강 값, 기록 ID, 토큰은 제공받거나 문서에 기록하지 않았다.

권한은 모두 허용됐다. 조회 기간 필터의 문제가 아니라 반환된 걸음 기록의 시간 범위와 SDK 생성자 계약의 불일치다. 시작과 종료가 같은지 또는 역전됐는지는 진단만으로 구별하지 않는다. 동일 시각의 Android 플랫폼 StepsRecord를 만드는 Robolectric 테스트로 SDK 변환 오류와 원본 API 경로의 보존 가능성을 재현했다.

## 수정 계약

- HealthStepRecovery는 기본 Jetpack 조회를 유지한다. 정확한 예외 메시지와 StepsRecord 생성자 스택이 일치하는 IllegalArgumentException에만 같은 조회 범위/페이지 토큰/페이지 크기/정렬의 Android 공개 API로 재시도한다. 새 권한이나 비공개 API는 사용하지 않는다.
- 페이지를 통째로 버리거나 임의 시간으로 수정하지 않는다. 공개 필드인 시작/종료/offset/count와 metadata 전체를 명시적으로 Raw에 보존한다. SDK/플랫폼 경로의 정상 걸음 Raw 형식을 통일한다. 이전 Gson 기반 원본과 다른 경우 새 revision을 만들며 원본 이력은 보존한다.
- 시간 범위가 SDK 계약에 맞지 않으면 LifeEvent STEP/UNVERIFIED와 validationError=INVALID_TIME_RANGE로 저장한다. 모든 읽은 ID를 스냅샷에 포함하므로 이 기록을 누락/삭제로 오인하지 않는다. 원본 수정 후에는 같은 source ID/event ID로 ACTIVE 복구가 가능하다.
- 변경 이력 변환에서도 같은 오류가 발생할 수 있다. 동일 토큰으로 Android getChangeLogs를 재시도하고 걸음은 직접 보존한다. 다른 요청 자료형은 ID로 Jetpack 재조회하며 삭제 로그도 모두 반영한다. 재조회 실패를 무시하거나 토큰을 성공 처리하지 않는다.
- 기존 전체 페이지 읽기 후 삭제 대조, 모든 처리 및 합계 성공 후 토큰 저장을 유지한다. 실패/취소/불완전 페이지를 정상 빈 결과로 바꾸지 않는다. 이미 저장된 원본은 기존 정책대로 유지하고 다음 수집에서 멱등적으로 재처리한다.
- 수면/운동 및 걸음 aggregate 흐름을 유지한다. 이 수정은 알려진 걸음 변환 오류에서 후속 수집이 중단되는 것을 막지만, 다른 자료형/집계의 독립적인 오류까지 모두 성공으로 바꾸지는 않는다. 합계는 Health Connect aggregate 결과이며 문제 원본의 count를 임의로 직접 더하거나 빼지 않는다.
- 진단에 invalidStepRecords 수만 추가하고 성공 시 SUCCESS_WITH_WARNINGS로 표시한다. 실제 값/ID는 진단에 넣지 않는다. 원본 ID는 해당 수집의 중복 경고 방지 집합에만 사용하고 진단으로 직렬화하지 않는다.

## 검증 및 남은 확인

HealthStepRecoveryTest는 동일 시각 SDK 예외 재현/원본 필드 보존, 정상 경로 유지, 다른 예외·권한·취소 전파, 복구 실패의 빈 결과 오인 방지, 혼합 기록의 Room 저장·중복·수정/재처리 복구, 민감 정보 없는 완료 경고를 검증한다. 첫 실행에서 테스트용 원본 metadata의 dataOrigin이 비어 있음을 확인하고 실제 조회 응답처럼 채웠다.

실제 사용자 단말에서 같은 데이터로 모든 페이지/변경 이력/aggregate가 끝까지 성공하는지는 수정판 설치 후 진단으로 확인한다. 단위 테스트와 소스/API 검증을 실기기 IPC 확인으로 취급하지 않는다.

공식 API: [원본 페이지 조회](https://developer.android.com/reference/android/health/connect/ReadRecordsRequestUsingFilters.Builder), [플랫폼 걸음 기록](https://developer.android.com/reference/android/health/connect/datatypes/StepsRecord), [HealthConnectManager](https://developer.android.com/reference/android/health/connect/HealthConnectManager).
