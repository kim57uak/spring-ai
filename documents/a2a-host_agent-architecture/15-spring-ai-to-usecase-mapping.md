# 15. Spring AI To Supervisor Usecase Mapping

## Supervisor Runtime Mapping

- `SupervisorPlanningService`
  - 사용자 질의에서 downstream agent 라우팅 계획을 생성
- `A2AInvocationService`
  - 계획에 따라 downstream A2A 호출 실행
- `SupervisorResponseComposeService`
  - 다중 downstream 결과를 최종 응답으로 합성
- `DefaultSupervisorLlmRuntime`
  - planning/compose 공통 모델 호출 포트
- `LlmCallPolicy`
  - 재시도/백오프/rate-limit 정책 통합

## Usecase

- `single-agent route`
  - 한 downstream agent만 호출해 결과 반환
- `multi-agent route`
  - 복수 downstream agent 호출 후 병합 응답 생성
- `fallback route`
  - 특정 agent 실패 시 제한된 partial result로 응답
