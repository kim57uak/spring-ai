package com.example.springsupervisorai.service.agent.a2ui.product;

import com.example.springsupervisorai.service.agent.a2ui.common.A2uiTemplateView;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 제품 도메인의 {@link A2uiTemplateView} 상수를 해당 {@link ProductA2uiTemplate} 구현체에 매핑하는 레지스트리.
 * <p>
 * 매핑되지 않은 뷰가 요청되면 {@link A2uiTemplateView#PACKAGE_SUMMARY}로 폴백한다.
 * 내부 맵은 Spring 주입된 모든 {@link ProductA2uiTemplate} 빈 목록으로부터 빌드된다.
 */
@Component
public class ProductA2uiTemplateRegistry {

    private final Map<A2uiTemplateView, ProductA2uiTemplate> templates;

    public ProductA2uiTemplateRegistry(List<ProductA2uiTemplate> templates) {
        EnumMap<A2uiTemplateView, ProductA2uiTemplate> map = new EnumMap<>(A2uiTemplateView.class);
        for (ProductA2uiTemplate template : templates) {
            map.put(template.view(), template);
        }
        this.templates = Map.copyOf(map);
    }

    /**
     * 주어진 뷰에 대한 템플릿을 조회한다.
     *
     * @param view 조회할 템플릿 뷰
     * @return 일치하는 템플릿, 또는 폴백으로 요약 템플릿
     */
    public ProductA2uiTemplate resolve(A2uiTemplateView view) {
        ProductA2uiTemplate template = templates.get(view);
        if (template != null) {
            return template;
        }
        // 매핑되지 않은 뷰는 요약 템플릿으로 폴백
        ProductA2uiTemplate fallback = templates.get(A2uiTemplateView.PACKAGE_SUMMARY);
        if (fallback == null) {
            throw new IllegalStateException("Missing PACKAGE_SUMMARY product A2UI template");
        }
        return fallback;
    }
}
