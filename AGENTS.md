# Life Dashboard Native App 개발 지침

## 세션 시작 및 문서 유지 규칙

개발을 시작하거나 세션을 이어갈 때 다음 문서를 순서대로 읽는다.

1. [현재 상태 및 인수인계](docs/SESSION_HANDOFF.md): 실제 구현 상태, 검증 결과, 다음 작업.
2. [단계별 개발 계획](docs/DEVELOPMENT_PLAN.md): 개발 방향, 단계별 범위와 완료 기준.
3. [개발 참고 및 결정 기록](docs/DEVELOPMENT_NOTES.md): 데이터 규칙, 주의사항, 미결정 항목.

- 개발 중 결정, 주의사항, 재현 가능한 문제 해결법이 생기면 `DEVELOPMENT_NOTES.md`를 갱신한다.
- 작업 단위 완료, 방향 변경, 세션 종료 시 `SESSION_HANDOFF.md`의 현재 상태·다음 작업·검증 결과를 갱신한다. 긴 작업은 중간에도 기록한다.
- 범위나 순서, 완료 기준이 바뀌면 `DEVELOPMENT_PLAN.md`를 함께 갱신한다.
- 계획·제안·구현·검증 완료를 구분한다. 실제 증거 없이 완료 체크를 하지 않는다.
- 빌드, 자동 테스트, 에뮬레이터, 실기기 검증을 구분하고 실행 명령·결과·미검증 범위를 남긴다.
- 다음 작업은 실행 가능한 체크리스트로 남긴다. 기존 결정 변경 시 날짜와 변경 이유를 기록한다.
- 문서에는 알림 원문, 건강 정보, 인증 정보 등 실제 민감 데이터를 넣지 않고 익명화된 예시를 사용한다.
- 이 규칙은 이후 개발 작업 중 문서 갱신을 지속하라는 사용자 요청에 따른다. 문서 작성만 요청된 세션에서는 앱 구현을 임의로 시작하지 않는다.

---

## 1. 프로젝트 목표

이 프로젝트는 Android 네이티브 기반의 개인 생활 데이터 대시보드 앱이다.

앱의 핵심 목적은 단순히 일정이나 알림을 보여주는 것이 아니라, 사용자의 생활에서 발생하는 주요 데이터를 지속적으로 수집하고 정규화해 Timeline 형태로 축적하는 것이다.

축적된 데이터는 향후 AI 기반 분석, 질의응답, 생활 패턴 분석 등에 활용할 수 있어야 한다.

핵심 개념은 다음과 같다.

- 생활 데이터를 자동 수집한다.
- 원본 데이터를 가능한 한 보존한다.
- 수집된 데이터를 공통 `LifeEvent` 구조로 정규화한다.
- 모든 생활 이벤트를 시간순 Timeline으로 기록한다.
- Dashboard와 Briefing은 Timeline 데이터를 기반으로 생성한다.
- 장기적으로 AI가 쉽게 활용할 수 있는 형태로 데이터를 축적한다.

---

# 2. 플랫폼 및 기본 기술

Android 네이티브 앱으로 개발한다.

기본 기술 스택:

- Kotlin
- Jetpack Compose
- Room
- Kotlin Coroutine
- Flow
- WorkManager
- Android Calendar Provider
- NotificationListenerService
- Health Connect

웹 기반 기술이나 React Native 등은 사용하지 않는다.

UI는 Jetpack Compose를 사용한다.

---

# 3. MVP 핵심 기능

초기 버전에서는 기능을 과도하게 확장하지 않는다.

다음 기능에 집중한다.

## 3.1 Calendar

Android Calendar 데이터를 읽어 다음 정보를 제공한다.

- 오늘 일정
- 내일 일정
- 일정 시간순 정렬
- 일정 시작/종료 시간
- 일정 제목
- 위치 정보
- 일정 기반 하루 요약

Calendar에서 가져온 데이터는 UI에서만 사용하는 것이 아니라 반드시 `LifeEvent`로 저장한다.

---

## 3.2 Notification Inbox

Android `NotificationListenerService`를 사용해 앱 알림을 수집한다.

모든 알림을 무조건 사용자에게 보여주는 것이 목적은 아니다.

생활에 의미 있는 알림을 자동으로 분류하고 구조화하는 것이 핵심이다.

우선 다음 세 종류를 자동 추출한다.

### DELIVERY

택배 및 배송 관련 알림.

예:

- 배송 시작
- 배송 중
- 배송 완료
- 오늘 도착 예정

가능하면 다음 정보를 추출한다.

- 택배사
- 송장번호
- 배송 상태
- 상품명
- 예상 도착 정보

---

### PAYMENT

결제 및 카드 승인 관련 알림.

가능하면 다음 정보를 추출한다.

- 금액
- 가맹점
- 카드사
- 결제 시간
- 결제 형태

예:

```json
{
  "type": "PAYMENT",
  "merchant": "스타벅스",
  "amount": 5900,
  "cardCompany": "현대카드"
}
```

---

### RESERVATION

예약 관련 알림.

예:

- 호텔
- 항공
- 식당
- 병원
- 공연
- 기타 예약

가능하면 다음 정보를 추출한다.

- 예약 종류
- 장소
- 날짜
- 시간
- 예약번호
- 업체명

---

# 4. Health Connect

Health Connect에서 사용자의 건강 데이터를 가져온다.

초기에는 다음 데이터만 우선 지원한다.

- 걸음수
- 운동 시간
- 운동 기록
- 수면 시작 시간
- 수면 종료 시간
- 수면 총 시간

과도하게 의료 데이터까지 확장하지 않는다.

Health 데이터도 반드시 `LifeEvent`로 저장한다.

---

# 5. Morning Briefing

사용자의 최근 생활 데이터를 기반으로 아침 브리핑을 생성한다.

초기에는 AI가 반드시 필요한 기능으로 만들지 않는다.

우선 규칙 기반으로 구현할 수 있어야 한다.

예:

```text
좋은 아침입니다.

어제 수면
6시간 10분

어제 걸음수
8,420보

운동
32분

오늘 일정
3개

오늘 오전 일정
2개
```

필요한 경우 최근 평균과 비교할 수 있다.

예:

```text
최근 7일 평균보다 수면이 42분 짧습니다.
```

Morning Briefing 역시 생성 결과를 저장할 수 있다.

단, 원본 데이터와 파생 데이터는 명확히 구분한다.

---

# 6. Life Timeline

이 프로젝트에서 가장 중요한 기능이다.

Calendar, Notification, Health Connect 등 서로 다른 소스에서 발생한 데이터를 하나의 Timeline으로 통합한다.

예:

```text
2026-10-07

07:12
SLEEP
수면 종료
6시간 14분

08:03
STEP_SUMMARY
8,421보

09:18
DELIVERY
CJ대한통운 배송 출발

11:32
PAYMENT
스타벅스 5,900원

14:00
CALENDAR
치과 예약

18:42
RESERVATION
호텔 예약 완료
```

Timeline은 시간순으로 정렬한다.

필터링 가능한 구조로 설계한다.

예:

- 전체
- 일정
- 건강
- 결제
- 배송
- 예약

Timeline 데이터는 장기간 보관 가능한 구조로 만든다.

---

# 7. 데이터 아키텍처

데이터는 다음 3단계 구조를 따른다.

```text
RAW DATA
    ↓
STRUCTURED LIFE EVENT
    ↓
DERIVED DATA
```

각 단계의 역할을 명확하게 분리한다.

---

# 8. Raw Data

외부 소스에서 받아온 데이터는 가능한 한 손실 없이 저장한다.

예:

- Notification 원문
- Calendar 원본 데이터
- Health Connect 원본 record

Raw 데이터는 향후 파싱 로직 변경 또는 AI 재분석 시 활용할 수 있어야 한다.

예를 들어 현재 버전에서 PAYMENT로 인식하지 못했던 과거 알림을 나중에 새로운 파서나 AI로 다시 분석할 수 있어야 한다.

## raw_event

권장 필드:

```text
id TEXT PK

source_type TEXT

source_key TEXT

occurred_at INTEGER

received_at INTEGER

raw_json TEXT

hash TEXT
```

`source_type` 예:

```text
NOTIFICATION
CALENDAR
HEALTH_CONNECT
```

---

# 9. LifeEvent

`life_event`는 앱 전체에서 가장 중요한 핵심 테이블이다.

다양한 데이터 소스를 하나의 공통 이벤트 형식으로 통합한다.

권장 구조:

```text
id TEXT PK

type TEXT

category TEXT

occurred_at INTEGER

ended_at INTEGER NULL

title TEXT

summary TEXT

source_type TEXT

source_id TEXT NULL

raw_event_id TEXT NULL

data_json TEXT

importance REAL

created_at INTEGER

updated_at INTEGER
```

---

# 10. LifeEvent Type

초기에는 지나치게 많은 Type을 만들지 않는다.

우선 다음 정도로 시작한다.

```text
CALENDAR

PAYMENT

DELIVERY

RESERVATION

SLEEP

STEP

STEP_SUMMARY

EXERCISE

NOTIFICATION

BRIEFING
```

Type은 Kotlin enum처럼 사용할 수 있지만 DB에는 TEXT로 저장한다.

새로운 Event Type 추가 시 DB migration 부담을 최소화하기 위함이다.

---

# 11. Category

`type`보다 더 넓은 그룹을 표현한다.

예:

```text
SCHEDULE

FINANCE

HEALTH

LIFE

COMMUNICATION
```

필요한 경우 nullable로 둘 수 있다.

---

# 12. data_json

모든 타입별 상세 데이터를 개별 컬럼으로 만들지 않는다.

공통 검색 및 정렬에 필요한 값만 정규 컬럼으로 두고, 이벤트별 상세값은 `data_json`에 저장한다.

예:

PAYMENT:

```json
{
  "merchant": "스타벅스",
  "amount": 5900,
  "cardCompany": "현대카드"
}
```

DELIVERY:

```json
{
  "carrier": "CJ대한통운",
  "trackingNumber": "123456789",
  "status": "OUT_FOR_DELIVERY"
}
```

SLEEP:

```json
{
  "durationMinutes": 385
}
```

이 구조는 미래 기능 추가에 유연해야 한다.

---

# 13. Source Tracking

모든 `LifeEvent`는 가능한 경우 원본 소스를 추적할 수 있어야 한다.

예:

```text
source_type = CALENDAR

source_id = Android Calendar Event ID
```

또는

```text
source_type = NOTIFICATION

raw_event_id = raw_event.id
```

같은 원본 데이터가 다시 수집될 경우 중복 이벤트가 생성되지 않아야 한다.

가능하면 다음 조합을 중복 방지에 사용한다.

```text
source_type + source_id
```

필요하면 UNIQUE index를 사용한다.

단 `source_id`가 존재하지 않는 이벤트도 있으므로 nullable 상황을 고려해야 한다.

---

# 14. event_tag

이벤트에는 여러 개의 Tag를 붙일 수 있다.

예:

```text
카페
소비
식음료
여행
학교
가족
병원
```

테이블 예:

```text
event_tag

event_id TEXT

tag TEXT

PRIMARY KEY(event_id, tag)
```

Tag는 초기에는 규칙 기반으로 생성할 수 있다.

향후 AI가 Tag를 자동으로 생성할 수 있어야 한다.

---

# 15. event_entity

AI 검색 및 관계 분석을 위해 이벤트에서 주요 Entity를 추출할 수 있도록 한다.

테이블 예:

```text
event_entity

id TEXT PK

event_id TEXT

entity_type TEXT

name TEXT

normalized_name TEXT
```

`entity_type` 예:

```text
PERSON

PLACE

COMPANY

PRODUCT

HOTEL

HOSPITAL
```

예:

```text
COMPANY
스타벅스

PLACE
서울역

HOTEL
호텔 한큐 레스파이어 오사카
```

이 구조를 통해 향후 다음과 같은 검색을 지원할 수 있어야 한다.

```text
오사카 관련 이벤트 전부 보여줘.
```

---

# 16. Daily Summary

Dashboard에서 매번 수천 개의 LifeEvent를 계산하지 않도록 일간 집계 데이터를 별도 저장할 수 있다.

권장 테이블:

```text
daily_summary

date TEXT PK

step_count INTEGER

sleep_minutes INTEGER

exercise_minutes INTEGER

payment_count INTEGER

payment_amount INTEGER

calendar_count INTEGER

delivery_count INTEGER

reservation_count INTEGER

summary_json TEXT

updated_at INTEGER
```

Dashboard는 가능하면 `daily_summary`를 우선 사용한다.

상세 화면 또는 AI 분석 시 `life_event`를 조회한다.

---

# 17. 권장 Room Entity

초기 `LifeEventEntity` 예:

```kotlin
@Entity(
    tableName = "life_event",
    indices = [
        Index("occurredAt"),
        Index("type"),
        Index("category"),
        Index(value = ["sourceType", "sourceId"], unique = true)
    ]
)
data class LifeEventEntity(
    @PrimaryKey
    val id: String,

    val type: String,
    val category: String?,

    val occurredAt: Long,
    val endedAt: Long?,

    val title: String,
    val summary: String?,

    val sourceType: String,
    val sourceId: String?,
    val rawEventId: String?,

    val dataJson: String?,

    val importance: Double = 0.5,

    val createdAt: Long,
    val updatedAt: Long
)
```

실제 구현 과정에서 필요하면 개선할 수 있지만 기본 철학은 유지한다.

---

# 18. 초기 DB 테이블

초기 버전은 가능한 한 다음 5개 테이블을 중심으로 시작한다.

```text
raw_event

life_event

event_tag

event_entity

daily_summary
```

새로운 기능을 추가하기 위해 무조건 새로운 테이블을 만드는 방식은 피한다.

먼저 `LifeEvent` 모델로 표현 가능한지 검토한다.

---

# 19. Dashboard

Dashboard는 데이터를 생성하는 계층이 아니다.

저장된 데이터의 현재 상태를 보여주는 View 역할에 집중한다.

초기 Dashboard에는 다음 정도를 보여준다.

```text
GOOD MORNING

오늘 일정

어제 수면

걸음수

운동 시간

중요 알림

택배

결제

예약
```

Dashboard가 앱의 중심 데이터 구조가 되지 않도록 한다.

앱의 중심은 `LifeEvent Store`다.

---

# 20. Timeline UI

Timeline은 주요 화면 중 하나로 만든다.

예:

```text
10월 7일

07:12
수면 종료
6시간 14분

08:00
건강
8,421보 / 운동 32분

09:18
배송
CJ대한통운 배송출발

11:32
결제
스타벅스 5,900원

14:00
일정
치과

18:42
예약
호텔 예약 완료
```

날짜별 Section 또는 LazyColumn 형태를 사용할 수 있다.

이벤트 타입별 아이콘을 사용할 수 있다.

---

# 21. AI 확장성

초기 버전에서 AI를 핵심 기능으로 넣을 필요는 없다.

우선 정확하고 깨끗한 생활 데이터를 쌓는 것을 최우선으로 한다.

향후 다음 기능을 지원할 수 있도록 설계한다.

예:

```text
오늘 뭐 했어?

지난달과 비교해서 생활 패턴이 어떻게 달라졌어?

잠을 적게 잔 다음 날 활동량은 어때?

올해 병원 간 기록 보여줘.

최근 3개월 여행 예약 기록 보여줘.

지난달 결제 중 외식 관련 소비 알려줘.

나는 보통 몇 시쯤 일정을 시작해?
```

---

# 22. AI Metadata

AI 기능이 추가될 경우 `LifeEvent` 본체를 과도하게 변경하지 않는다.

필요하면 다음 정보를 별도 테이블 또는 별도 필드로 확장한다.

```text
tags

entities

importance

ai_summary

embedding_id
```

하지만 embedding은 초기 버전에서 구현하지 않는다.

Semantic Search가 실제로 필요한 시점에 다음과 같은 별도 테이블을 추가한다.

```text
event_embedding
```

초기부터 vector DB 또는 embedding 저장 구조를 강제로 넣지 않는다.

---

# 23. Raw Data 보존 원칙

가능하면 Raw Data는 삭제하거나 덮어쓰지 않는다.

파싱 실패도 데이터다.

예:

```text
Notification 수집 성공

PAYMENT 분석 실패

→ raw_event는 유지

→ life_event는 일반 NOTIFICATION으로 생성하거나 보류
```

향후 Parser 개선 시 Raw Data를 다시 처리할 수 있어야 한다.

---

# 24. Parser 구조

Notification 파싱 로직과 데이터 저장 로직을 강하게 결합하지 않는다.

가능하면 다음 구조를 사용한다.

```text
NotificationListener
        ↓
RawEventRepository
        ↓
NotificationParser
        ↓
ParsedEvent
        ↓
LifeEventRepository
```

Parser 교체가 쉬워야 한다.

예:

```kotlin
interface EventParser {
    suspend fun parse(rawEvent: RawEvent): List<ParsedLifeEvent>
}
```

향후 다음 Parser를 독립적으로 추가할 수 있게 한다.

```text
PaymentNotificationParser

DeliveryNotificationParser

ReservationNotificationParser
```

---

# 25. 데이터 흐름

전체적인 데이터 흐름은 다음과 같이 유지한다.

```text
Android Data Sources

Calendar
Notification
Health Connect
        │
        ▼
Raw Data Collector
        │
        ▼
RawEvent Store
        │
        ▼
Parser / Normalizer
        │
        ▼
LifeEvent Store
        │
        ├──────── Timeline
        │
        ├──────── Dashboard
        │
        ├──────── Daily Summary
        │
        └──────── Morning Briefing
                      │
                      ▼
                  Future AI
```

---

# 26. 구현 우선순위

다음 순서로 개발한다.

## Phase 1

- Room DB 구축
- `raw_event`
- `life_event`
- Timeline 기본 UI

## Phase 2

- Android Calendar 연동
- Calendar 데이터를 LifeEvent로 변환
- Timeline 표시

## Phase 3

- NotificationListenerService
- Raw Notification 저장
- 기본 Notification Inbox

## Phase 4

Notification Parser 구현.

우선순위:

```text
PAYMENT

DELIVERY

RESERVATION
```

## Phase 5

Health Connect 연동.

```text
SLEEP

STEP

EXERCISE
```

## Phase 6

Dashboard

## Phase 7

Daily Summary

## Phase 8

Morning Briefing

AI 기능은 위 구조가 안정된 이후 진행한다.

---

# 27. 개발 원칙

## 단순성을 우선한다.

초기부터 Clean Architecture를 과도하게 복잡하게 적용하지 않는다.

권장 정도:

```text
UI

ViewModel

Repository

DataSource
```

필요해질 때 계층을 추가한다.

---

## LifeEvent 중심으로 생각한다.

새로운 기능을 만들 때 항상 먼저 질문한다.

```text
이 기능에서 어떤 LifeEvent가 발생하는가?
```

UI부터 만들지 않는다.

먼저 데이터가 어떻게 저장되는지 결정한다.

---

## 데이터 손실을 피한다.

원본 데이터를 지나치게 정제해서 저장하지 않는다.

가능하면 Raw Data를 남긴다.

---

## AI에 종속되지 않는다.

AI 없이도 앱의 기본 기능은 정상 동작해야 한다.

AI는 분석 및 검색 계층으로 추가한다.

---

## 외부 서비스에 과도하게 의존하지 않는다.

가능하면 데이터는 Android 단말 내부 SQLite/Room에 보관한다.

향후 동기화 기능이 필요하면 별도 설계한다.

---

# 28. 현재 제외된 기능

다음 기능은 현재 MVP 범위에서 제외한다.

- 범용 Automation Rule Engine
- Todo 관리
- Device Status Dashboard
- 사진 자동 백업
- NAS 모니터링
- 서버 상태 확인
- 위치 기반 자동화
- Wi-Fi 기반 자동화
- Bluetooth 기반 자동화
- 범용 사용자 정의 Trigger / Condition / Action 시스템

현재 프로젝트의 목적은 Tasker류 자동화 앱을 만드는 것이 아니다.

핵심은 생활 데이터를 자동으로 수집하고 Timeline으로 축적하는 것이다.

---

# 29. 프로젝트의 핵심 정의

이 프로젝트를 한 문장으로 정의하면 다음과 같다.

> Android에서 일정, 중요한 알림, 건강 데이터를 자동으로 수집하여 하나의 Life Timeline으로 축적하고, 이를 Dashboard와 향후 AI 분석에 활용하는 개인 생활 데이터 앱.

가장 중요한 자산은 UI가 아니라 장기간 축적되는 `LifeEvent` 데이터다.

모든 개발 의사결정은 이 원칙을 기준으로 한다.
