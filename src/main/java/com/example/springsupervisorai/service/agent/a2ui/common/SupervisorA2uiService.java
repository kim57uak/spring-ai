package com.example.springsupervisorai.service.agent.a2ui.common;

import com.example.springsupervisorai.model.SupervisorPlanningContext;
import java.util.Optional;

/**
 * 렌더링된 메시지와 프로토콜 페이로드를 생성하는 최상위 A2UI 렌더 서비스.
 * <p>
 * 구현체는 Compose에서 선택된 {@link A2uiTemplateView}를 받아
 * A2UI 프로토콜 계층으로 전달될 JSON 직렬화 가능 페이로드를 생성한다.
 */
public interface SupervisorA2uiService {

    /**
     * 주어진 컨텍스트와 선택된 뷰에 대한 A2UI 렌더 결과를 빌드한다.
     *
     * @param context 현재 supervisor planning 컨텍스트
     * @param selectedView compose 단계에서 선택된 템플릿 뷰
     * @param message 선택적 재정의 메시지; 비어있으면 템플릿 기본값 사용
     * @return 표시 메시지와 프로토콜 페이로드 JSON을 포함한 렌더 결과, 뷰가 지원되지 않으면 empty
     */
    Optional<A2uiRenderResult> build(SupervisorPlanningContext context, A2uiTemplateView selectedView, String message);

    /**
     * A2UI 렌더 작업 결과.
     *
     * @param message 사용자에게 표시되는 메시지
     * @param protocolPayloadJson A2UI 프로토콜 계층으로 전달될 JSON 페이로드
     */
    record A2uiRenderResult(String message, String protocolPayloadJson) {
    }
}
