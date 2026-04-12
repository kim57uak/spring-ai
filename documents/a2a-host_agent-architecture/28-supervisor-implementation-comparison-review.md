# 28. Supervisor 구현 충실도 비교검토 및 리팩토링 보고서

## 1) 검토 기준

- 기준 문서: `17, 20, 21, 23, 25, 26, 27, 30, 31`
- 코드 범위: `src/main/java/com/example/springsupervisorai` + 공통 예외처리(`com.example.springai.advice`)
- 검증 일시: 2026-04-11
- 실행 검증:
  - `./gradlew_unix test --tests com.example.springsupervisorai.SupervisorA2aIntegrationTest --tests com.example.springsupervisorai.controller.SupervisorA2AControllerStreamingTest --tests com.example.springsupervisorai.service.SupervisorAgentServiceTest --tests com.example.springsupervisorai.service.SupervisorAgentOrchestratorCheckpointTest --tests com.example.springsupervisorai.service.agent.invoke.DefaultA2AInvocationServiceTest --rerun-tasks`
  - 결과: **BUILD SUCCESSFUL (9 tests passed)**

## 1-1) 30/31 기준 추가 검토 항목

- HITL 결정 범위: `APPROVE/CANCEL`만 지원되는지
- 데이터 생성·변경 요청의 강제 HITL 정책 적용 여부
- `Graph + Swarm State` 하이브리드 저장 경계 분리 여부
- `legacy + v1.0` A2A 메서드 호환 유지 여부

## 2) A2A 규약 준수 점검 결과

### 준수(Implemented)

- JSON-RPC 2.0 precheck 및 method 분기 처리  
  - [SupervisorA2AController.java](/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springsupervisorai/controller/SupervisorA2AController.java:86)
  - [SupervisorA2ARequestValidator.java](/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springsupervisorai/controller/SupervisorA2ARequestValidator.java:30)
- `message/send`, `message/stream`, `tasks/get`, `tasks/list`, `tasks/cancel` 지원
  - [SupervisorA2AController.java](/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springsupervisorai/controller/SupervisorA2AController.java:95)
- method/params 스키마 검증 통일
  - [SupervisorA2ARequestValidator.java](/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springsupervisorai/controller/SupervisorA2ARequestValidator.java:47)
- 라우팅 allowlist + timeout/retry + circuit-breaker
  - [a2a-supervisor.yml](/Users/dolpaks/Downloads/project/spring-ai/src/main/resources/a2a-supervisor.yml:1)
  - [DefaultA2AInvocationService.java](/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springsupervisorai/service/agent/invoke/DefaultA2AInvocationService.java:61)
- A2A 예외 코드를 GlobalExceptionHandler에서 일원화 처리
  - [GlobalExceptionHandler.java](/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springai/advice/GlobalExceptionHandler.java:74)
- stream 종료 규약(event: `chunk/error/done`) + timeout 처리
  - [SupervisorA2AController.java](/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springsupervisorai/controller/SupervisorA2AController.java:128)
  - [SupervisorA2AControllerStreamingTest.java](/Users/dolpaks/Downloads/project/spring-ai/src/test/java/com/example/springsupervisorai/controller/SupervisorA2AControllerStreamingTest.java:23)
- stream cancel 시 task 취소 반영
  - [SupervisorAgentService.java](/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springsupervisorai/service/SupervisorAgentService.java:78)
  - [SupervisorAgentServiceTest.java](/Users/dolpaks/Downloads/project/spring-ai/src/test/java/com/example/springsupervisorai/service/SupervisorAgentServiceTest.java:19)
- checkpoint payload 무결성 검증 후 resume
  - [SupervisorAgentOrchestrator.java](/Users/dolpaks/Downloads/project/spring-ai/src/main/java/com/example/springsupervisorai/service/SupervisorAgentOrchestrator.java:229)
  - [SupervisorAgentOrchestratorCheckpointTest.java](/Users/dolpaks/Downloads/project/spring-ai/src/test/java/com/example/springsupervisorai/service/SupervisorAgentOrchestratorCheckpointTest.java:23)

### 부분 준수(Partially Implemented)

- `tasks/*` 계약 테스트: `tasks/list` 중심 검증은 존재하나 `tasks/get`, `tasks/cancel` 계약 테스트는 아직 부족
  - [SupervisorA2aIntegrationTest.java](/Users/dolpaks/Downloads/project/spring-ai/src/test/java/com/example/springsupervisorai/SupervisorA2aIntegrationTest.java:94)

### 미준수/잔여 갭(Not Yet)

- payload size guard(요청/응답 크기 제한) 미구현
- 관측성 지표(latency/failure/token/cost/downstream success rate) 미구현
- HITL review API(`tasks/review/get`, `tasks/review/decide`) 미구현
- Swarm State 전용 저장소/버전 충돌 제어 미구현

## 3) 점수 재산정 (리팩토링 반영 후)

- 총점: **84/100** (기존 70점 대비 +14)

| 항목 | 배점 | 이전 | 현재 | 반영 근거 |
|---|---:|---:|---:|---|
| 컴포넌트 스캔/경계 분리 | 10 | 9 | 9 | 유지 |
| 단일 진입점/컨트롤러 책임 분리 | 10 | 8 | 9 | validator 분리로 controller 책임 명확화 |
| 오케스트레이션 체인(P0/P1) | 15 | 13 | 13 | 유지 |
| 라우팅/호출 경계(P2) | 15 | 10 | 13 | circuit-breaker 추가 |
| 예외 일관화/민감정보 비노출 | 10 | 7 | 9 | GlobalExceptionHandler 일원화 |
| 스트리밍 규약 안정성 | 10 | 6 | 9 | timeout/error/done 규약 + 테스트 |
| 저장소/체크포인트 복구 | 10 | 7 | 9 | checkpoint integrity 검증 추가 |
| 테스트(계약/회귀) | 10 | 5 | 8 | stream/checkpoint/cancel/list 경계 테스트 확장 |
| 관측성/운영지표 | 5 | 1 | 1 | 미구현 |
| 코드 품질 정책(Javadoc/enum) | 5 | 4 | 4 | 신규 validator/config 메서드 Javadoc 보강 완료 |

## 4) 우선순위 업데이트

### 완료

- `P0-1`: circuit-breaker 도입 완료
- `P0-3`: 예외 매핑 GlobalExceptionHandler 일원화 완료
- `P1-4`: stream timeout/cancel 종료 규약 고정 완료
- `P1-5`: checkpoint 무결성 검증 완료
- `P1-6`: method별 params schema 검증 통일 완료

### 남은 우선순위

1. **P0-2 payload size guard**
- controller/request mapper/client 경계별 최대 크기 정책 추가

2. **P2-7 계약 테스트 확장**
- `tasks/get`, `tasks/cancel`, partial failure, retry 소진, malformed JSON-RPC 케이스 보강

3. **P2-8 관측성 지표**
- Micrometer 기반 latency/failure/token/cost/downstream success rate 계측 추가

## 5) 결론

현재 supervisor 구현은 A2A 핵심 계약(JSON-RPC method 처리, tasks 계열, stream 규약, 오류 표준화, 라우팅 정책)을 **실행 가능한 수준으로 준수**하고 있다.  
다만 운영 안정성 완성도를 위해 `payload size guard`와 `관측성 지표`는 다음 스프린트 우선 반영이 필요하다.

---

## 2026-04-12 동기화 메모 (30/31 반영)

- 본 문서는 `30`, `31`번 문서 기준으로 HITL/하이브리드 아키텍처 원칙을 상위 기준으로 따른다.
- 이번 차례 구현 스코프는 `APPROVE`, `CANCEL`만 포함하며 `REVISE`는 다음 단계로 이관한다.
- 상품/예약/주문 등 데이터 생성·변경(create/update/delete) 요청은 리스크 점수와 무관하게 HITL 강제 정책을 적용한다.
- A2A 계약은 `legacy` + `v1.0`을 모두 충족하는 호환 모드로 유지한다(메서드 enum 기반 관리).
- 사용자 추가정보 수집(이름/전화/이메일)은 향후 계획으로 분리하며, 입력 UX는 자연어/콤마 텍스트 수용 후 내부 구조화 원칙을 따른다.
