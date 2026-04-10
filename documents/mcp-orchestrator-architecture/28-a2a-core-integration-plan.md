# 28. A2A Core Integration Status (Current Version Baseline)

## Goal (Implemented)

- 기존 `/api/*-agent/*` 경로의 동작을 유지하면서 A2A 프로토콜(`/.well-known/agent.json`, `/a2a`, `/a2a/stream`)을 코어 실행 흐름에 통합한다.
- 현재 프로젝트 버전 라인(Spring AI 1.0.3 + LangGraph4j 1.8.10)을 유지한다.

## Scope

- 포함(구현):
  - A2A 전용 컨트롤러 추가(`controller.a2a.*`)
  - A2A DTO/Mapper/AgentCard Registry 추가
  - `A2ATaskStore` 기반 task lifecycle(`message/send`, `message/stream`, `tasks/get`, `tasks/cancel`, `tasks/list`)
  - `AgentOrchestrator` lifecycle 훅 연동(`RUNNING/COMPLETED/FAILED/CANCELED`)
- 제외:
  - 하위 에이전트에서 다른 에이전트로 원격 포워딩/취소 전달
  - 기존 `/api/*-agent/*` 계약 변경

## Design Rules

- `Additive only`: 기존 컨트롤러는 유지, A2A 컨트롤러만 추가
- `Boundary guard`: scope 불일치 taskId 접근 차단
- `Protocol isolation`: A2A 모델은 `a2a.*` 패키지로 격리
- `Core integration`: lifecycle 이벤트를 서비스 이후 경로에 반영하되, non-A2A 경로는 null-context로 무영향 처리

## Dependency Baseline

- 유지:
  - `org.springframework.ai:spring-ai-bom:1.0.3`
  - `org.bsc.langgraph4j:langgraph4j-bom:1.8.10`
- 추가(기획 기준):
  - `org.a2aproject.sdk:a2a-java-sdk-bom:1.0.0.Alpha4`
  - `org.a2aproject.sdk:a2a-java-sdk-spec`
  - (필요 시) `a2a-java-sdk-client`, `a2a-java-sdk-client-transport-jsonrpc`

## Compatibility Gate (Mandatory)

- 배포 차단 조건:
  - 기존 `/api/*-agent/*` 회귀 테스트 실패
  - A2A 핵심 시나리오 실패
  - scope ownership 위반 차단 테스트 실패
- 배포 방식:
  - canary -> 단계 배포
  - 이상 시 A2A 경로만 롤백

## Current Change Size (Applied)

- 신규 파일: A2A 컨트롤러/DTO/task/lifecycle/mapper/registry 계층 반영 완료
- 기존 수정 파일: `ScopedAgentChatService`, `AgentOrchestrator`, `build.gradle`, `application.yml` 등 반영 완료
- 남은 작업: A2A 호환성 회귀 자동화 강화

## Primary References

- [A2A_SUBAGENT_MINIMAL_REFACTOR_PLAN.md](/Users/dolpaks/Downloads/project/spring-ai/documents/a2a-sub_agent-architecture/A2A_SUBAGENT_MINIMAL_REFACTOR_PLAN.md)
- [16-package-and-dependency-policy.md](/Users/dolpaks/Downloads/project/spring-ai/documents/mcp-orchestrator-architecture/16-package-and-dependency-policy.md)
- [15-implementation-roadmap.md](/Users/dolpaks/Downloads/project/spring-ai/documents/mcp-orchestrator-architecture/15-implementation-roadmap.md)
