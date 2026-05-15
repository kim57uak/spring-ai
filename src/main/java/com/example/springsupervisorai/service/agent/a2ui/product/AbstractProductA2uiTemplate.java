package com.example.springsupervisorai.service.agent.a2ui.product;

import java.util.ArrayList;
import java.util.List;

/**
 * 제품 도메인 A2UI 템플릿의 기본 클래스.
 * <p>
 * false를 반환하는 기본 {@link #requiresSummaryCoreFields()}와
 * 정렬된 섹션 식별자 목록을 빌드하는 편의 메서드 {@link #children(String...)}를 제공한다.
 */
abstract class AbstractProductA2uiTemplate implements ProductA2uiTemplate {

    @Override
    public boolean requiresSummaryCoreFields() {
        return false;
    }

    /**
     * 가변 인자로부터 불변 섹션 식별자 목록을 빌드한다.
     *
     * @param sectionIds 포함할 섹션 ID
     * @return 불변 섹션 ID 목록
     */
    protected List<String> children(String... sectionIds) {
        List<String> children = new ArrayList<>();
        for (String sectionId : sectionIds) {
            children.add(sectionId);
        }
        return List.copyOf(children);
    }
}
