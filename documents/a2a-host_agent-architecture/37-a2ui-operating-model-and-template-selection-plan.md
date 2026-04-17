# 37. A2UI 운영 모델 및 템플릿 선택 현재 구현 정리

## 1) 문서 목적

본 문서는 현재 supervisor A2UI 구현을 기준으로 서버, compose, 템플릿, 웹 클라이언트 동작을 정리한다.

- 기준 소스
  - `LlmSupervisorResponseComposeService`
  - `DefaultSupervisorProductInfoA2uiService`
  - `ProductA2uiTemplateRegistry`
  - `ProductA2uiComposePromptProvider`
  - `SupervisorA2ARequestValidator`
  - `src/main/resources/static/a2a-supervisor-chat.html`
  - `src/main/resources/static/a2ui/a2ui-renderer.js`

---

## 2) 현재 A2UI 활성 조건

A2UI는 항상 동작하지 않는다. 아래 조건을 모두 만족해야 한다.

1. `host.a2a.a2ui.enabled=true`
2. compose 시점에 `A2uiComposePromptProviderRegistry`가 매칭되는 provider를 찾음
3. 현재는 사실상 `product` agent의 성공 결과가 존재해야 함
4. A2UI compose 결과(`message`, `selectedView`) 파싱 성공
5. `DefaultSupervisorProductInfoA2uiService.build(...)`가 렌더 가능한 product payload를 추출 성공

하나라도 실패하면 일반 텍스트 compose 경로로 폴백한다.

---

## 3) 현재 서버 동작

### 3.1 Compose 단계

`LlmSupervisorResponseComposeService`는 아래 순서로 동작한다.

1. downstream 결과를 요약
2. 실패만 존재하면 결정적 failure summary 반환
3. A2UI 가능성이 있으면 A2UI compose prompt 실행
4. LLM이 JSON 형태로
   - `message`
   - `selectedView`
   를 반환
5. 파싱 실패 시 repair prompt 1회 수행
6. 선택된 view와 함께 `SupervisorA2uiService.build(...)` 호출
7. 성공 시
   - 일반 텍스트 메시지 1개
   - A2UI protocol payload 1개
   를 순서대로 반환

즉, 현재 A2UI는 "텍스트 응답을 대체"하는 구조가 아니라 "텍스트 + A2UI payload"를 함께 보내는 구조다.

### 3.2 A2UI payload 생성

현재 구현체는 `DefaultSupervisorProductInfoA2uiService` 하나다.

동작:

- `product` agent의 성공 결과만 대상으로 함
- payload에서 `structuredData.productDetail` 우선 탐색
- 없으면 전체 payload를 재귀 탐색
- `baseProductInfo`가 있는 product node를 찾으면 표준 message sequence 생성

생성 메시지 타입:

- `surfaceUpdate`
- `dataModelUpdate`
- `beginRendering`

표준 catalog ID:

- `https://a2ui.org/specification/v0_8/standard_catalog_definition.json`

---

## 4) 현재 템플릿 선택 구조

### 4.1 지원 템플릿

현재 허용 값:

- `summary`
- `pricing`
- `timeline`
- `booking`

`ProductA2uiComposePromptProvider`가 각 템플릿의 선택 기준을 compose prompt에 주입한다.

### 4.2 선택 책임

현재 템플릿 선택은 별도 selector 서비스가 아니라 compose 단계에 통합돼 있다.

흐름:

1. compose prompt가 최종 메시지와 `selectedView`를 함께 생성
2. 서버가 `selectedView`를 enum으로 검증
3. `ProductA2uiTemplateRegistry`가 해당 템플릿 구현을 선택

즉, 예전 계획 문서에 있던 별도 `A2uiTemplateSelectionService`는 현재 구현되어 있지 않다.

### 4.3 fallback

- 허용되지 않은 템플릿 키는 `summary`로 폴백
- provider가 없으면 A2UI 경로 자체를 건너뜀
- build 실패 시 일반 compose 응답 유지

---

## 5) 현재 템플릿 구현 수준

구현 클래스:

- `SummaryProductA2uiTemplate`
- `PricingProductA2uiTemplate`
- `TimelineProductA2uiTemplate`
- `BookingProductA2uiTemplate`

현재 상태:

- 4개 템플릿이 존재한다.
- 공통 카드 집합은 크게 공유한다.
- 주된 차이는 root child 순서와 기본 메시지 전략이다.

즉, 템플릿 enum과 선택 흐름은 구현되었지만 화면 구조 차별화는 아직 제한적이다.

이 점 때문에 `pricing`, `timeline`이 선택되어도 사용자가 체감하는 UI 차이는 크지 않을 수 있다.

---

## 6) 현재 product A2UI 데이터 계약

`DefaultSupervisorProductInfoA2uiService`는 아래 데이터를 추출해 data model로 만든다.

- 기본 정보
  - `productCode`
  - `name`
  - `departureDate`
  - `arrivalDate`
  - `departureCity`
  - `arrivalCity`
  - `nights`
  - `days`
  - `price`
  - `currency`
- 부가 정보
  - `theme`
  - `brand`
  - `airline`
  - `thumbnailUrl`
  - `adultPrice`
  - `childPrice`
  - `infantPrice`
  - `depositPrice`
  - `singleRoomNote`
  - `includedItems`
  - `optionalItems`
  - `timeline`
  - `noticeItems`
  - `meetingDate`
  - `meetingTime`
  - `meetingAirport`

예약 form 초기 모델:

- `bookerName`
- `contact`
- `headCount`
- `birthDate`

---

## 7) 웹 클라이언트 동작

현재 `a2a-supervisor-chat.html`은 supervisor SSE 위에서 A2UI를 처리한다.

- `event: chunk`
  - 일반 텍스트 표시
- `event: a2ui`
  - A2UI payload를 renderer에 전달
- `event: done`
  - 스트림 종료 처리

클라이언트는 요청 시 `supportedCatalogIds`를 포함한 capability를 전달하고, `userAction`은 `application/json+a2ui` data part로 다시 서버에 보낸다.

즉, A2UI payload 형식은 표준 지향이지만 transport는 여전히 supervisor 전용 SSE envelope다.

---

## 8) A2UI 입력 처리

`SupervisorA2ARequestValidator`는 v1.0 `message.parts[]`를 해석한다.

지원 입력:

- 일반 text part
- `application/json+a2ui` data part

현재 특별 처리:

- `submit_reservation` action은 예약 생성용 자연어 프롬프트로 정규화된다.

예:

```text
예약생성해줘
상품코드: ...
예약자: ...
연락처: ...
인원수: ...
생년월일: ...
```

즉, 현재 A2UI action은 supervisor 내부에서 전용 command bus로 처리되는 것이 아니라 기존 자연어 오케스트레이션 흐름으로 다시 태운다.

---

## 9) 현재 표준 준수 수준

현재 구현은 아래 수준으로 보는 것이 정확하다.

표준에 가까운 부분:

- A2UI message type 사용
- 표준 catalog ID 사용
- 표준 component 중심 렌더링
- `userAction` payload 왕복

애플리케이션 커스텀인 부분:

- supervisor 전용 SSE 이벤트 래핑
- `summary/pricing/timeline/booking` 템플릿 체계
- product 전용 data path와 카드 구성
- 예약 action을 자연어 프롬프트로 재정규화하는 처리

따라서 현재 구조는 "표준 A2UI payload + 앱 전용 transport/템플릿" 조합이다.

---

## 10) 구현된 것과 아직 없는 것

구현됨:

- A2UI on/off 설정
- compose 단계 통합 템플릿 선택
- `common/product/reservation` 도메인 분리 구조
- product 결과 기반 A2UI build
- reservation 생성 입력 폼 A2UI build
- 웹 렌더러 연동
- A2UI userAction 입력 처리

아직 없음:

- 템플릿별로 완전히 다른 component tree
- 별도 template selection service
- 순수 A2UI transport 단일화
- action을 구조화된 command로 직접 처리하는 전용 서버 경로

---

## 11) 폴더 정책

`service/agent/a2ui` 아래 정책은 다음과 같이 고정한다.

- `common`
  - 표준 catalog/view 식별자
  - provider registry
  - domain service composite
  - transport/serialization 공통 계약
- `product`
  - 상품조회/상품생성처럼 product 도메인 결과를 설명하거나 입력받는 A2UI
- `reservation`
  - 예약생성/예약접수처럼 reservation 도메인 입력과 후속 액션을 다루는 A2UI

규칙:

- A2UI payload는 항상 표준 catalog `https://a2ui.org/specification/v0_8/standard_catalog_definition.json` 기준으로 조립한다.
- custom component catalog를 새로 만들지 않는다.
- domain package는 자기 도메인의 payload 추출, typed model, message builder, template만 가진다.
- 다른 도메인 입력 폼을 끌어다 직접 조립하지 않고, 필요하면 `common` 계약 또는 해당 도메인 service를 통해 연결한다.

---

## 12) 결론

현재 supervisor A2UI는 “운영 가능한 최소 제품 형태”까지는 구현되어 있다.

정확한 표현은 아래와 같다.

- 템플릿 선택은 compose 단계에 이미 통합 구현됨
- product/reservation 도메인 기준으로 A2UI payload 생성이 동작함
- client action도 다시 supervisor 입력으로 연결됨
- 다만 템플릿 시각적 차별화와 transport 표준화는 아직 후속 과제다
