# 36. A2UI v0.8 표준 프로토콜 기반 상품 상세 기획서

## 1) 목적과 범위

본 문서는 "단일 상품 원본 JSON"을 A2UI v0.8 표준 프로토콜 wire format으로 안전하게 렌더링하기 위한 설계를 정의한다.

- 대상: 패키지 상품 1건 상세 표현
- 비대상: 예약 생성/조회, 정산 조회
- 목표
  - A2UI v0.8 표준 메시지(`surfaceUpdate`, `dataModelUpdate`, `beginRendering`) 사용
  - 대형 원본 JSON을 표준 프로토콜 + custom catalog 조합으로 렌더링
  - 실패 시 기존 텍스트 fallback 보장

---

## 2) 전체 구조

단일 상품 원본 JSON 1건을 우선 1개 주요 surface로 렌더링한다.

1. `package_product_surface`
- 요약, 가격, 일정, 규정, 예약 생성 폼을 하나의 surface로 구성

전송 원칙:
- 최초 응답에서 상품 요약/가격/일정/규정/예약 생성 폼을 모두 렌더링
- 예약 생성은 surface 내부 form submit으로 처리
- 추가 상세 surface는 후속 단계에서 확장 가능하지만 1차 표준화 범위에서는 제외

운영 원칙:
- A2UI는 supervisor 옵션 기능이다.
- `a2a-supervisor.yml`에서 명시적으로 활성화한 경우에만 동작한다.
- 기본값은 `disabled`다.
- 비활성 시 supervisor와 하위 agent는 기존 텍스트 응답만 사용한다.
- 하위 agent는 기존 동작을 절대 깨지 않으며, 현재 단계에서는 하위 agent 응답 계약을 강제로 변경하지 않는다.

---

## 3) 공통 Envelope 계약

모든 A2UI 메시지는 아래 Envelope를 따른다.

```json
{
  "version": "1.0",
  "message": "텍스트 요약",
  "a2ui": {
    "protocolVersion": "0.8",
    "catalogId": "https://hanatour.com/a2ui/catalogs/package-product-v1",
    "messages": []
  },
  "meta": {
    "sessionId": "sup-xxx",
    "taskId": "sup-task-xxx",
    "sourceAgent": "product",
    "schemaValidated": true,
    "missingFields": []
  }
}
```

규칙:
- `a2ui.messages`는 A2UI v0.8 server-to-client 표준 메시지 배열
- 현재 구현은 custom catalog를 사용하되 wire format은 표준 A2UI를 따른다
- 불충족 시 `a2ui` 생성을 포기하고 `message`만 반환

현재 적용 정책:
- supervisor는 downstream 결과에서 구조화 데이터를 읽을 수 있을 때만 envelope 생성을 시도한다.
- 구조화 데이터가 없거나 스키마 매핑이 실패하면 기존 compose 텍스트를 그대로 반환한다.
- 이 fallback은 필수이며, A2UI 실패가 기존 응답 품질에 영향을 주면 안 된다.

---

## 3.1 supervisor/downstream 역할 분리

현재 단계의 역할은 다음과 같다.

1. downstream agent
- 기존 `response` 텍스트 응답을 그대로 유지
- 기존 A2A 동작에 영향 주지 않음
- 현재 단계에서는 A2UI 때문에 downstream 계약을 의무 변경하지 않음

2. supervisor
- 설정이 켜져 있을 때만 A2UI 렌더링 시도
- 성공 시 `chunk(text)` + `a2ui event`를 함께 전송
- 실패 시 기존 compose 결과만 전송

3. 향후 확장
- downstream이 `structuredData`를 함께 반환하면 supervisor의 A2UI 성공률이 높아짐
- 권장 확장 예시:

```json
{
  "id": "task-123",
  "status": "COMPLETED",
  "response": "기존 텍스트 응답",
  "structuredData": {
    "type": "product_detail",
    "productDetail": {
      "baseProductInfo": {},
      "itineraryInfo": {}
    }
  }
}
```

이 확장은 선택 사항이며, 적용 전에도 기존 기능은 그대로 동작해야 한다.

현재 구현 메모:
- `product` 하위 agent의 `message/send` 응답은 기존 `response` 텍스트를 그대로 유지한다.
- 같은 응답 객체에 optional `structuredData`를 추가할 수 있다.
- `tasks/get`, `tasks/list`, `tasks/cancel`의 기존 계약은 유지한다.
- supervisor는 `structuredData.productDetail`가 있을 때만 A2UI 렌더링을 시도한다.
- 구조화 데이터 추출은 공통 계약 `AgentStructuredDataExtractor` 아래 전략 방식으로 분리한다.
- 현재 실제 구현체는 `SaleProductStructuredDataExtractor`이며, `PRODUCT` scope만 처리한다.
- 이후 `ReservationStructuredDataExtractor`, `SearchStructuredDataExtractor`, `SettlementStructuredDataExtractor`를 동일 패턴으로 추가한다.
- 공통 진입점은 `CompositeAgentStructuredDataExtractor`가 맡고, 지원하는 extractor 중 첫 번째 성공 결과만 사용한다.

---

## 4) 표준 프로토콜 구조

현재 구현은 A2UI v0.8의 아래 메시지 타입만 사용한다.

1. `surfaceUpdate`
- surface의 component adjacency list 전달

2. `dataModelUpdate`
- 예약 폼 입력 상태 초기값 전달

3. `beginRendering`
- 렌더링 시작 신호와 root component 지정

클라이언트 액션은 A2UI 개념상 `userAction`에 해당하며, 현재 UI에서는 이를 기존 supervisor 텍스트 요청으로 변환해 전달한다.

### 4.1 서버 -> 클라이언트 예시

```json
{
  "version": "1.0",
  "message": "테스트 상품 상품 상세를 준비했습니다.",
  "a2ui": {
    "protocolVersion": "0.8",
    "catalogId": "https://hanatour.com/a2ui/catalogs/package-product-v1",
    "messages": [
      {
        "surfaceUpdate": {
          "surfaceId": "package-product-task-123",
          "components": [
            {
              "id": "root",
              "component": {
                "Column": {
                  "children": {
                    "explicitList": ["product_card", "reservation_form"]
                  }
                }
              }
            },
            {
              "id": "product_card",
              "component": {
                "ProductOverviewCard": {
                  "data": {
                    "productCode": "AAP331260523TG1",
                    "name": "테스트 상품"
                  }
                }
              }
            },
            {
              "id": "reservation_form",
              "component": {
                "ReservationForm": {
                  "title": { "literalString": "예약 생성" },
                  "productCode": { "literalString": "AAP331260523TG1" },
                  "fields": [],
                  "action": {
                    "name": "submit_reservation",
                    "context": []
                  }
                }
              }
            }
          ]
        }
      },
      {
        "dataModelUpdate": {
          "surfaceId": "package-product-task-123",
          "path": "reservation",
          "contents": [
            { "key": "bookerName", "valueString": "" },
            { "key": "contact", "valueString": "" },
            { "key": "headCount", "valueString": "1" },
            { "key": "birthDate", "valueString": "" }
          ]
        }
      },
      {
        "beginRendering": {
          "surfaceId": "package-product-task-123",
          "catalogId": "https://hanatour.com/a2ui/catalogs/package-product-v1",
          "root": "root"
        }
      }
    ]
  }
}
```

### 4.2 custom catalog 전략

- wire format은 표준 A2UI v0.8를 따른다.
- component catalog는 현재 프로젝트 전용 custom catalog를 사용한다.
- 현재 custom component
  - `ProductOverviewCard`
  - `ReservationForm`
- container component
  - `Column`

이 방식의 이유:
- 표준 protocol 이점 유지
- 현재 웹 클라이언트에서 빠르게 도입 가능
- 추후 공식 standard catalog 또는 공식 renderer로 이전 가능

### 4.3 클라이언트 렌더링 흐름

1. SSE `a2ui` event 수신
2. `a2ui.messages[]` 순회
3. `surfaceUpdate`로 component registry 구성
4. `dataModelUpdate`로 surface data model 초기화
5. `beginRendering.root` 기준으로 root component 렌더링
6. `ReservationForm` submit 시 action context를 해석
7. `submit_reservation`을 기존 supervisor 텍스트 프롬프트로 변환

### 4.4 예약 생성 action 변환 규칙

현재는 A2UI `userAction` 개념을 내부적으로 기존 supervisor 텍스트 요청으로 변환한다.

```text
예약생성해줘
상품코드: AAP331260523TG1
예약자: 홍길동
연락처: 010-1234-5678
인원수: 2
생년월일: 19900101
```

이 규칙은 기존 예약 생성 로직을 그대로 재사용하기 위한 호환 전략이다.

### 4.5 실패 전략

1. downstream structuredData 추출 실패
- A2UI 생성 중단
- 기존 compose 텍스트 응답 사용

2. supervisor A2UI 변환 실패
- 기존 compose 텍스트 응답 사용

3. 클라이언트 message 파싱 실패
- 카드 렌더 중단
- 텍스트 응답은 그대로 유지

4. 예약 폼 action 처리 실패
- 기존 채팅 흐름은 유지
- 사용자에게 오류 텍스트만 표시

---

## 5) 영역별 데이터 모델

## 5.1 package_result_card

### A2UI JSON 포맷

```json
{
  "$id": "package_result_card",
  "type": "object",
  "required": ["productCode", "name", "departureDate", "arrivalDate", "price", "currency"],
  "properties": {
    "productCode": { "type": "string" },
    "name": { "type": "string" },
    "departureDate": { "type": "string", "pattern": "^[0-9]{8}$" },
    "arrivalDate": { "type": "string", "pattern": "^[0-9]{8}$" },
    "departureCity": { "type": "string" },
    "arrivalCity": { "type": "string" },
    "nights": { "type": "integer" },
    "days": { "type": "integer" },
    "price": { "type": "number" },
    "currency": { "type": "string", "enum": ["KRW"] },
    "theme": { "type": "string" },
    "brand": { "type": "string" },
    "airline": { "type": "string" },
    "thumbnailUrl": { "type": "string" }
  }
}
```

### 입력 데이터 포맷(원본 기준)

```json
{
  "baseProductInfo": {
    "saleProdCd": "string",
    "saleProdNm": "string",
    "depDay": "yyyyMMdd",
    "arrDay": "yyyyMMdd",
    "depCityNm": "string",
    "arrCityNm": "string",
    "trvlNgtCnt": 0,
    "trvlDayCnt": 0,
    "adtTotlAmt": 0,
    "thmNm": "string",
    "brndNm": "string",
    "depFlgtCd": "string",
    "arrFlgtCd": "string",
    "rppdCntntInfoList": [
      {
        "rprsProdCntntUrlAdrs": "string"
      }
    ]
  }
}
```

### 매핑 규칙

- `productCode <- saleProdCd`
- `name <- saleProdNm`
- `price <- adtTotlAmt` (없으면 `adtAmt`)
- `airline <- depFlgtCd + "/" + arrFlgtCd`
- `thumbnailUrl <- rppdCntntInfoList[0].rprsProdCntntUrlAdrs` (유효 URL일 때만)

---

## 4.2 package_pricing_detail

### A2UI JSON 포맷

```json
{
  "$id": "package_pricing_detail",
  "type": "object",
  "required": ["productCode", "adultPrice", "childPrice", "infantPrice", "depositAmount"],
  "properties": {
    "productCode": { "type": "string" },
    "adultPrice": { "type": "number" },
    "childPrice": { "type": "number" },
    "infantPrice": { "type": "number" },
    "depositAmount": { "type": "number" },
    "singleSupplementText": { "type": "string" },
    "priceFixed": { "type": "boolean" },
    "childRule": { "type": "string" },
    "ageRule": { "type": "string" }
  }
}
```

### 입력 데이터 포맷(원본 기준)

```json
{
  "baseProductInfo": {
    "saleProdCd": "string",
    "adtTotlAmt": 0,
    "chdTotlAmt": 0,
    "infTotlAmt": 0,
    "dnpyTlAmt": 0,
    "snglAddAmtDesc": "string",
    "amtFixYn": "Y|N",
    "prcGdncBcVo": {
      "chdInclRoomCont": "string",
      "ageRmkCont": "string"
    }
  }
}
```

### 매핑 규칙

- `priceFixed <- (amtFixYn == "Y")`
- 금액 null/음수는 invalid 처리 후 fallback

---

## 4.3 package_itinerary_timeline

### A2UI JSON 포맷

```json
{
  "$id": "package_itinerary_timeline",
  "type": "object",
  "required": ["productCode", "days"],
  "properties": {
    "productCode": { "type": "string" },
    "days": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["day", "date"],
        "properties": {
          "day": { "type": "integer" },
          "date": { "type": "string", "pattern": "^[0-9]{8}$" },
          "dow": { "type": "string" },
          "hotelName": { "type": "string" },
          "city": { "type": "string" },
          "summary": { "type": "string" }
        }
      }
    }
  }
}
```

### 입력 데이터 포맷(원본 기준)

```json
{
  "baseProductInfo": {
    "saleProdCd": "string",
    "arrCityNm": "string"
  },
  "itineraryInfo": {
    "schdInfoList": [
      {
        "schdDay": 1,
        "strtDt": "yyyyMMdd",
        "strDow": "string",
        "htlInfoList": [
          {
            "htlKoNm": "string"
          }
        ]
      }
    ]
  }
}
```

### 매핑 규칙

- `days[].day <- schdDay`
- `days[].date <- strtDt`
- `days[].dow <- strDow`
- `days[].hotelName <- htlInfoList[0].htlKoNm` (없으면 null)
- `days[].city <- arrCityNm` (일정 도시 정보가 별도 있으면 그 값 우선)

---

## 4.4 package_notice_panel

### A2UI JSON 포맷

```json
{
  "$id": "package_notice_panel",
  "type": "object",
  "required": ["productCode", "highlights", "included", "optional", "notices"],
  "properties": {
    "productCode": { "type": "string" },
    "highlights": {
      "type": "array",
      "items": { "type": "string" }
    },
    "included": {
      "type": "array",
      "items": { "type": "string" }
    },
    "optional": {
      "type": "array",
      "items": { "type": "string" }
    },
    "notices": {
      "type": "array",
      "items": { "type": "string" }
    }
  }
}
```

### 입력 데이터 포맷(원본 기준)

```json
{
  "baseProductInfo": {
    "saleProdCd": "string",
    "exprWrdngCont2": "string",
    "bnftInfoList": [
      { "corePntTitlNm": "string", "corePntCont": "string" }
    ],
    "trvlExpnInclList": [
      { "trvlExpnClstNm": "string", "trvlExpnDesc": "string" }
    ],
    "trvlChcExpnList": [
      { "trvlExpnClstNm": "string", "trvlExpnDesc": "string" }
    ],
    "noteResInfo": {
      "noteResRmkCont": "string"
    }
  }
}
```

### 매핑 규칙

- `highlights <- exprWrdngCont2 + bnftInfoList`
- `included <- trvlExpnInclList`
- `optional <- trvlChcExpnList`
- `notices <- noteResInfo.noteResRmkCont`

---

## 5) 렌더링 설계 (TypeScript)

원칙:
- 공식 A2UI 렌더러/코어를 사용한다.
- 클라이언트는 "허용된 view만" 등록한다.
- 알 수 없는 view는 렌더하지 않고 텍스트만 표시한다.

```ts
type ViewId =
  | "package_result_card"
  | "package_pricing_detail"
  | "package_itinerary_timeline"
  | "package_notice_panel";

type UiEnvelope = {
  version: string;
  message: string;
  a2ui?: {
    schemaVersion: "0.8";
    view: ViewId;
    data: Record<string, unknown>;
    actions?: Array<Record<string, unknown>>;
  };
  meta?: {
    sessionId?: string;
    taskId?: string;
    schemaValidated?: boolean;
    missingFields?: string[];
  };
};

function renderA2uiEnvelope(env: UiEnvelope) {
  renderText(env.message);
  if (!env.a2ui) return;

  switch (env.a2ui.view) {
    case "package_result_card":
      renderPackageResultCard(env.a2ui.data);
      break;
    case "package_pricing_detail":
      renderPricingDetail(env.a2ui.data);
      break;
    case "package_itinerary_timeline":
      renderItineraryTimeline(env.a2ui.data);
      break;
    case "package_notice_panel":
      renderNoticePanel(env.a2ui.data);
      break;
    default:
      // never
      break;
  }
}
```

스트리밍:
- `event:text`는 즉시 출력
- `event:a2ui.patch`는 `seq` 순서로 반영
- 누락 seq 감지 시 재요청 또는 patch 스킵

---

## 6) 실패/복구 전략

## 6.1 서버 측 실패 전략

1. Schema 검증 실패
- 처리: `a2ui=null`로 강등 + `message`만 반환
- 로깅: `view`, `validationErrors`, `taskId`

2. 필수 필드 누락
- 처리: `meta.missingFields` 채우고 text-only 반환

3. LLM 비정상 JSON
- 처리: repair 1회 -> 실패 시 text-only

4. 데이터 타입 오류
- 처리: 숫자/날짜 강제 변환 시도 1회 -> 실패 시 fallback

5. A2UI 기능 비활성
- 처리: A2UI 생성 로직 자체를 건너뛰고 기존 compose만 실행
- 설정 위치: `host.a2a.a2ui.enabled=false`

## 6.2 클라이언트 측 실패 전략

1. Unknown view
- 처리: 렌더 스킵, 텍스트만 표시

2. Patch 순서 역전/중복
- 처리: `seq` 기반 dedupe, out-of-order drop

3. 렌더 컴포넌트 예외
- 처리: boundary로 UI 영역만 fallback, 채팅 세션 유지

---

## 7) 프롬프트 추가 계약

시스템 프롬프트에 아래 블록을 추가한다.

```text
[A2UI PRODUCT OUTPUT CONTRACT]
- Allowed views: package_result_card, package_pricing_detail, package_itinerary_timeline, package_notice_panel
- Output envelope: {version, message, a2ui, meta}
- a2ui.data must satisfy selected view schema exactly
- Do not invent missing values
- If required fields are missing, set a2ui to null and explain via message + meta.missingFields
- Dates must be yyyyMMdd, amounts must be number
```

주의:
- 현재 1차 구현은 프롬프트 기반이 아니라 프로그램 기반 매핑이 우선이다.
- 즉, supervisor 코드가 downstream payload를 읽어 카드용 JSON을 만든다.
- 프롬프트 계약은 향후 `pricing`, `timeline`, `notice`를 LLM 보조 생성으로 확장할 때 사용한다.

---

## 8) 샘플 결과 (실데이터 반영)

```json
{
  "version": "1.0",
  "message": "요청하신 패키지 상품 상세입니다.",
  "a2ui": {
    "schemaVersion": "0.8",
    "view": "package_result_card",
    "data": {
      "productCode": "AAP331260523TG1",
      "name": "테스트 11일태국 테스트 생성 상품~!",
      "departureDate": "20260523",
      "arrivalDate": "20260602",
      "departureCity": "인천",
      "arrivalCity": "방콕",
      "nights": 10,
      "days": 11,
      "price": 355000,
      "currency": "KRW",
      "theme": "밍글링 투어",
      "brand": "스탠다드",
      "airline": "TG0657/TG0658",
      "thumbnailUrl": "https://devimage.hanatour.com/usr/cms/resize/1000_0/2009/04/04/10000/8d0a9ba9-8e8f-4b64-b60b-1bf4cde3987c.gif"
    },
    "actions": [
      {
        "id": "package.view_detail",
        "label": "요금 상세",
        "payloadTemplate": {
          "productCode": "AAP331260523TG1",
          "view": "package_pricing_detail"
        }
      },
      {
        "id": "package.view_timeline",
        "label": "일정 보기",
        "payloadTemplate": {
          "productCode": "AAP331260523TG1",
          "view": "package_itinerary_timeline"
        }
      }
    ]
  },
  "meta": {
    "sessionId": "sup-example-session",
    "taskId": "sup-example-task",
    "sourceAgent": "product",
    "schemaValidated": true,
    "missingFields": []
  }
}
```

---

## 9) 운영 지표

- `a2ui_schema_fail_rate < 2%`
- `text_fallback_rate < 10%`
- `patch_apply_error_rate < 1%`
- `first_render_latency_p95 < 1500ms`

---

## 10) 구현 체크리스트

- [ ] 뷰 4종 JSON Schema 파일 생성
- [x] `host.a2a.a2ui.enabled` 게이트 추가
- [ ] 서버 검증기(A2uiValidationService) 연결
- [ ] compose 단계에서 envelope 생성/검증/fallback 연결
- [ ] SSE patch `seq` 관리 로직 추가
- [ ] 프론트 renderer registry 4종 컴포넌트 연결
- [ ] 모니터링 지표/로그 대시보드 구성
