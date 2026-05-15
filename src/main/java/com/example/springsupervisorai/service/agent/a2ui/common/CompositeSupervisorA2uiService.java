package com.example.springsupervisorai.service.agent.a2ui.common;

import com.example.springsupervisorai.model.SupervisorPlanningContext;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Compose에서 선택된 A2UI 뷰를 소유 도메인 서비스로 라우팅한다.
 * <p>
 * 등록된 {@link SupervisorA2uiDomainService} 목록을 순회하며 주어진 뷰를
 * {@link SupervisorA2uiDomainService#supports 지원하는} 첫 번째 서비스에 위임한다.
 * 목록은 생성 시 방어적으로 복사된다.
 */
@Primary
@Component
public class CompositeSupervisorA2uiService implements SupervisorA2uiService {

    private final List<SupervisorA2uiDomainService> domainServices;

    public CompositeSupervisorA2uiService(List<SupervisorA2uiDomainService> domainServices) {
        this.domainServices = List.copyOf(domainServices);
    }

    /**
     * 첫 번째 일치하는 도메인 서비스에 위임하여 A2UI 렌더 결과를 빌드한다.
     *
     * @param context 현재 supervisor planning 컨텍스트
     */
    @Override
    public Optional<A2uiRenderResult> build(SupervisorPlanningContext context, A2uiTemplateView selectedView, String message) {
        return domainServices.stream()
                .filter(service -> service.supports(context, selectedView))
                .findFirst()
                .flatMap(service -> service.build(context, selectedView, message));
    }
}
