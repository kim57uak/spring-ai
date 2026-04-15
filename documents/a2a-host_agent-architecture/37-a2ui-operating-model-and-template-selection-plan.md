# 37. A2UI 운영 모델 및 템플릿 선택 전략 기획서

## 1) 목적

본 문서는 host supervisor의 A2UI 구현 현황, 표준 준수 원칙, 운영 시 UI 생성 전략, 템플릿 선택 책임 분리, 그리고 현재 코드 반영 상태를 정리한다.

- 대상
  - supervisor 기반 상품 상세/검색 결과 A2UI 렌더링
  - 표준 A2UI v0.8 protocol 적용 범위
  - 템플릿 선택 로직의 운영 설계
- 비대상
  - 자유 생성형 UI 연구
  - 하위 agent 전체 계약 재설계
  - 디자인 시스템 전면 개편

핵심 결론:

1. 운영 환경에서는 "LLM이 UI 전체 JSON을 자유 생성"하게 두지 않는다.
2. UI 구조는 코드로 고정하고, LLM은 제한된 선택과 텍스트 생성만 맡긴다.
3. 같은 상품 데이터라도 여러 UI 템플릿을 가질 수 있으며, 선택은 규칙 기반 또는 제한된 LLM 분류로 수행한다.
4. 템플릿 선택은 agent planning 단계가 아니라 compose 단계에서 product 결과를 받은 뒤 수행하는 것이 맞다.
5. A2UI protocol은 표준을 따르되, `summary/pricing/timeline/booking` 템플릿 체계와 화면 설계는 서비스 커스텀이다.

---

## 2) 현재 구현 상태 요약

현재 프로젝트의 A2UI 관련 구현은 아래 기준으로 정리된다.

### 2.1 서버

- `com.example.springsupervisorai.service.agent.a2ui.DefaultSupervisorProductInfoA2uiService`
  - downstream `product` 결과에서 상품 상세 원본 JSON을 추출
  - A2UI v0.8 server-to-client 메시지 배열 생성
  - 현재 사용 메시지
    - `surfaceUpdate`
    - `dataModelUpdate`
    - `beginRendering`
- 표준 catalog ID 사용
  - `https://a2ui.org/specification/v0_8/standard_catalog_definition.json`
- `com.example.springsupervisorai.service.agent.compose.LlmSupervisorResponseComposeService`
  - A2UI 활성 시 일반 compose 스트림 대신 단일 `complete(...)` 호출로 A2UI compose 수행
  - LLM 출력 계약
    - `message`
    - `selectedView`
- `com.example.springsupervisorai.service.agent.a2ui.A2uiComposePromptProvider`
  - 도메인별 템플릿 정의 주입 책임
- `com.example.springsupervisorai.service.agent.a2ui.ProductA2uiComposePromptProvider`
  - `product` 결과가 있을 때만 상품용 템플릿 정의(`summary/pricing/timeline/booking`)를 compose prompt에 주입
- 현재 supervisor는 A2UI payload를 직접 render하지 않고, SSE `a2ui` event에 JSON 문자열로 전달한다.

### 2.2 웹 UI

- `src/main/resources/static/a2ui/a2ui-renderer.js`
  - A2UI protocol payload를 파싱
  - 표준 catalog 중심의 component renderer 제공
- `src/main/resources/static/a2a-supervisor-chat.html`
  - SSE `a2ui` event 수신
  - payload를 renderer에 전달
  - `a2uiClientCapabilities.supportedCatalogIds` 포함한 message 전송
  - `userAction`을 `application/json+a2ui` data part로 전송

### 2.3 입력 처리

- `SupervisorA2ARequestValidator`
  - 일반 text part 해석
  - A2UI `userAction` data part 해석
  - 현재 예약 생성 액션은 기존 supervisor 자연어 요청으로 정규화해 downstream 흐름에 태움

---

## 3) 표준 준수 범위와 현실적 해석

현재 구현은 아래 범위에서 A2UI 표준을 따르는 것을 목표로 한다.

### 3.1 현재 맞춘 범위

- A2UI v0.8 message type 사용
- 표준 catalog ID 사용
- 표준 component 위주 조합
- `userAction` 기반 client-to-server action 전달
- client capability(`supportedCatalogIds`) 전달

### 3.2 아직 애플리케이션 래퍼가 남아있는 부분

현재 supervisor 전체 스트리밍은 A2UI 전용 transport가 아니라 기존 supervisor SSE 구조를 유지한다.

- 일반 텍스트 응답: `chunk`
- A2UI payload: `a2ui`
- 완료 이벤트: `done`

즉, "A2UI payload 자체"는 표준 쪽으로 정리되었지만, "supervisor 전체 스트림 프로토콜"은 여전히 앱 전용이다.

이 판단은 의도적이다.

이유:

1. 기존 supervisor 대화 스트림을 깨지 않기 위함
2. A2UI를 option feature로 유지하기 위함
3. 기존 markdown 응답과 A2UI 응답을 동시에 지원하기 위함

운영상 이 구조는 허용 가능하지만, "순수 A2UI transport만 사용한다"는 의미의 완전한 표준화와는 다르다.

### 3.3 표준과 커스텀의 경계

현재 구현은 "표준 A2UI protocol" 위에 "서비스 전용 템플릿 체계"를 얹은 구조다.

- 표준에 속하는 것
  - `surfaceUpdate`
  - `dataModelUpdate`
  - `beginRendering`
  - 표준 catalog ID
  - 표준 component(`Column`, `Card`, `Text`, `List`, `Button`, `TextField` 등)
- 서비스 커스텀에 속하는 것
  - `summary/pricing/timeline/booking` 템플릿 개념
  - 상품 전용 카드 구성
  - 템플릿별 카드 우선순위 및 레이아웃
  - 상품 도메인 전용 문구/스타일

즉, A2UI는 표준 wire format이고, 템플릿 시스템은 애플리케이션 커스텀이다.

---

## 4) 운영 환경에서의 A2UI 생성 원칙

### 4.1 결론

운영에서는 A2UI를 "자유 생성형 UI"로 사용하지 않는다.

운영에서는 아래 원칙을 따른다.

1. 구조는 코드가 결정한다.
2. 텍스트와 템플릿 선택은 LLM이 보조할 수 있다.
3. 표준을 벗어난 JSON 생성 권한은 LLM에 주지 않는다.

### 4.2 이유

LLM이 UI 전체 JSON을 자유 생성하면 아래 리스크가 발생한다.

- 스키마 위반
- component 오용
- 필수 action/context 누락
- 렌더 실패
- 예약/입력 흐름 장애
- 테스트 불가능성
- 디자인 일관성 붕괴

따라서 운영에서의 A2UI는 "생성형"이 아니라 "제약된 조립형"이어야 한다.

---

## 5) 역할 분리 원칙

### 5.1 자바 코드가 맡아야 하는 것

- 상품 원본 JSON 파싱
- A2UI message sequence 조립
- component 구조 결정
- data binding path 설계
- 예약 form/action context 계약
- 필수 필드 검증
- 표준 catalog 범위 내 component 선택
- fallback 정책

즉, UI 구조와 계약은 코드가 책임진다.

### 5.2 LLM이 맡겨도 되는 것

- 상품 요약 문구
- 사용자 질문에 따른 강조 포인트
- 템플릿 선택(`selectedView`)
- 안내 문구/추천 문구 생성
- 버튼 라벨의 자연어 표현

즉, LLM은 "무엇을 말할지"는 맡을 수 있지만 "어떻게 그릴지" 전체는 맡기지 않는다.

---

## 6) 템플릿 기반 운영 모델

같은 상품 데이터라도 서로 다른 UI 포맷으로 표현할 수 있다.

예시:

- `summary`
  - 상품 전반 소개 중심
- `pricing`
  - 가격/포함경비/선택경비 강조
- `timeline`
  - 일정/숙소/미팅 정보 강조
- `booking`
  - 예약 진행 유도 중심

중요한 점:

- 템플릿은 사전에 정의된 검증 가능한 집합이어야 한다.
- 운영에서 "랜덤 생성"이 아니라 "템플릿 선택"을 해야 한다.
- 랜덤성은 허용하되, 검증된 템플릿 중 하나를 선택하는 수준이어야 한다.

허용 가능한 선택 방식:

1. 규칙 기반 선택
2. 세션 기반 고정 선택
3. A/B 테스트 선택
4. 제한된 LLM 분류 선택

비권장:

- LLM이 임의로 새로운 UI 구조를 만드는 방식

---

## 7) 템플릿 선택을 어느 단계에서 할 것인가

### 7.1 결론

템플릿 선택은 planning 단계가 아니라 compose 단계에서 product 결과를 받은 뒤 수행해야 한다.

### 7.2 이유

#### planning 단계의 책임

- 어떤 하위 agent를 호출할지 결정
- 아직 실제 상품 결과가 없음

#### compose 단계의 책임

- 실제 product 결과를 이미 보유
- 사용자의 질문과 실제 데이터 상태를 함께 평가 가능
- 가격 정보 부족/일정 정보 없음 같은 예외 판단 가능
- 최종 사용자 문장(`message`)과 템플릿 선택(`selectedView`)을 한 번에 생성 가능

따라서 아래 흐름이 맞다.

1. planning 단계에서 `product agent` 호출 여부 결정
2. product 결과 수신
3. compose 단계에서 `message + selectedView`를 함께 생성
4. 선택된 템플릿으로 A2UI message 조립

---

## 8) 템플릿 선택 로직 설계

### 8.1 권장 방식

현재 구현은 별도 selector 호출이 아니라 compose 단계에 템플릿 선택을 통합한 방식이다.

즉:

1. compose 프롬프트가 최종 사용자 문장 생성 책임을 가진다.
2. 같은 호출 안에서 `selectedView`도 함께 반환한다.
3. 서버는 반환된 `selectedView`를 enum으로 검증하고 템플릿 registry로 전달한다.

이 방식의 장점:

- LLM 호출 수 감소
- compose 문맥과 템플릿 선택 문맥 일치
- product 데이터와 사용자 질문을 동시에 반영 가능

### 8.2 LLM이 알아야 하는 정보

LLM에게 선택을 맡길 경우 반드시 아래를 명시해야 한다.

1. 선택 가능한 템플릿 목록
2. 각 템플릿의 선택 기준
3. 반환 가능한 값의 형식
4. 허용되지 않은 값에 대한 fallback 정책

현재 구현에서는 이 정보를 공통 프롬프트에 하드코딩하지 않고, 도메인별 provider가 주입한다.

- 공통 prompt
  - A2UI compose 역할
  - JSON only 반환 계약
  - `message`, `selectedView` 필드 계약
- 도메인별 provider
  - 허용 template key
  - key별 선택 기준
  - 도메인 전용 템플릿 정의

예:

- `ProductA2uiComposePromptProvider`
  - `summary/pricing/timeline/booking` 정의 제공
  - `product` 결과가 있는 경우에만 활성화

이 방식으로 공통 supervisor 프롬프트가 특정 비즈니스 규칙으로 오염되는 것을 막는다.

### 8.3 안전장치

LLM 선택 결과는 반드시 코드에서 검증해야 한다.

- 허용 enum 외 값은 fallback
- repair prompt에도 동일한 허용 key와 template catalog를 제공
- provider가 없으면 A2UI compose 경로를 건너뛰고 일반 compose로 fallback

---

## 9) 현재 템플릿 구현 상태와 한계

현재 템플릿 추상화는 다음 구조를 가진다.

- `ProductA2uiTemplate`
- `SummaryProductA2uiTemplate`
- `PricingProductA2uiTemplate`
- `TimelineProductA2uiTemplate`
- `BookingProductA2uiTemplate`
- `ProductA2uiTemplateRegistry`

현재 한계:

- 4개 템플릿 모두 동일한 카드 집합을 공유한다.
- 실질적인 차이는 `rootChildren()` 순서와 기본 메시지 수준이다.
- 따라서 `PRICING`, `TIMELINE`이 실제로 선택되어도 화면이 매우 유사하게 보일 수 있다.

로그 확인 결과:

- `selectedView=PRICING`
- `selectedView=TIMELINE`

은 정상적으로 결정되고 있었다.

즉 "가격/일정 요청인데 같은 포맷처럼 보이는 문제"는 LLM 선택 실패가 아니라 템플릿 디자인/컴포넌트 트리 차별화 부족 문제다.

다음 리팩토링 방향:

1. 템플릿별로 다른 컴포넌트 트리를 가진다.
2. 템플릿별로 강조 카드, 밀도, CTA, 정보 축약/확장 전략을 다르게 한다.
3. 표준 A2UI component만 사용하되, 배치와 정보 구조는 명확히 차별화한다.

---

## 10) 장애 회고: A2UI 렌더링 실패 원인

템플릿 분리 이후 한 차례 A2UI가 렌더링되지 않고 일반 `chunk` 스트리밍으로 fallback되는 문제가 있었다.

원인:

- `compose-a2ui-template` 안에 raw JSON 예시를 literal로 넣어 두었고
- Spring AI `PromptTemplate`가 `{...}`를 템플릿 변수로 오해해 렌더링 예외를 발생시켰다.

증상:

- 서버 로그에 `Invalid supervisor prompt template`
- 프런트에는 `[[A2UI]]` 대신 일반 `chunk` 토큰만 전송

조치:

- raw JSON 예시 제거
- 필드 설명 형태로 프롬프트 계약 변경

교훈:

- PromptTemplate에 literal JSON 예시를 넣을 때는 `{}` escaping 또는 비-JSON 설명 형식을 사용해야 한다.

---

## 11) 결론

현재 A2UI 구조는 다음 원칙으로 정리된다.

1. protocol은 표준을 따른다.
2. 템플릿은 서비스 커스텀으로 운영한다.
3. 템플릿 선택은 planning이 아니라 compose 단계에서 한다.
4. 도메인별 템플릿 정의는 provider 방식으로 주입한다.
5. 현재 남은 과제는 템플릿별 레이아웃을 "순서 차이" 수준에서 "구조 차이" 수준으로 끌어올리는 것이다.

- 허용 enum: `summary`, `pricing`, `timeline`, `booking`
- 그 외 값: 무조건 `summary` fallback
- 입력 데이터가 불충분하면 선택 결과와 무관하게 `summary` 또는 가능한 템플릿으로 downgrade

---

## 9) 권장 아키텍처 변경안

### 9.1 신규 서비스 추가

권장 신규 컴포넌트:

- `A2uiTemplateSelectionService`
  - 책임: 템플릿 선택
- `RuleBasedA2uiTemplateSelectionService`
  - 책임: 명확한 키워드 매칭
- `LlmA2uiTemplateSelectionService`
  - 책임: 애매한 케이스 분류
- `CompositeA2uiTemplateSelectionService`
  - 책임: 규칙 우선, 실패 시 LLM, 실패 시 기본값

### 9.2 현재 코드에서의 연결 지점

현재 `DefaultSupervisorProductInfoA2uiService.resolveRequestedView(...)`는 하드코딩 기반이다.

향후 권장 변경:

```text
DefaultSupervisorProductInfoA2uiService.build(...)
  -> templateSelectionService.select(userMessage, productSummary)
  -> selectedView
  -> build messages by template
```

즉, 템플릿 선택 책임을 별도 서비스로 분리한다.

---

## 10) 디자인 운영 원칙

현재 상품 A2UI는 카드 기반 UI이며, 요약 카드/가격 카드/일정 카드/안내 카드/예약 카드 조합으로 구성된다.

운영 원칙:

1. 핵심 템플릿 수는 3~4개 수준으로 제한
2. 모든 템플릿은 동일한 데이터 계약을 공유
3. 디자인 차이는 정보 밀도와 섹션 우선순위 중심으로 조절
4. 액션 계약과 data path는 모든 템플릿에서 일관되게 유지

즉, 디자인의 다양성은 허용하되 계약과 행동은 동일해야 한다.

---

## 11) 단계별 실행 계획

### Phase 1. 현재 구현 안정화

- 현재 표준 catalog 기반 message 생성 유지
- 현행 renderer 안정화
- 브라우저 실렌더 E2E 확인

### Phase 2. 템플릿 선택 서비스 도입

- `A2uiTemplateSelectionService` 추가
- 기존 `resolveRequestedView` 대체
- 규칙 기반 우선 적용

### Phase 3. LLM 보조 선택 도입

- 애매한 케이스에 한해 LLM classifier 도입
- enum-only 반환 검증
- fallback 강제

### Phase 4. 다중 디자인 템플릿 운영

- `summary`, `pricing`, `timeline`, `booking` 템플릿 분리
- 템플릿별 UI 조합 조정
- A/B 테스트 또는 세션 고정 전략 적용 가능

---

## 12) 최종 결론

현재 프로젝트에서 A2UI의 올바른 운영 방향은 아래와 같다.

1. A2UI는 표준 protocol을 따르되, supervisor 전체 transport는 기존 앱 구조와 공존시킨다.
2. UI 구조는 코드가 만든다.
3. LLM은 텍스트와 템플릿 선택 보조 역할까지만 맡긴다.
4. 템플릿 선택은 planning 단계가 아니라 A2UI build 직전 단계에서 수행한다.
5. 운영에서의 A2UI는 "자유 생성형"이 아니라 "제약된 템플릿 조립형"이다.

이 원칙을 기준으로 앞으로의 host supervisor A2UI는 안정성과 실험 가능성을 동시에 확보할 수 있다.
