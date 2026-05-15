package com.example.springsupervisorai.service.agent.a2ui.product;

import com.example.springsupervisorai.service.agent.a2ui.common.A2uiTemplateView;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 제품 생성 입력을 위한 표준 A2UI 폼을 렌더링한다.
 * <p>
 * 제품 코드, 출발일 설정, 대상 고객 설정 등 새로운 판매 제품 생성에 필요한
 * 폼과 요약 카드를 제공한다.
 */
@Component
public class CreationFormProductA2uiTemplate extends AbstractProductA2uiTemplate {

    @Override
    public A2uiTemplateView view() {
        return A2uiTemplateView.PACKAGE_SALE_PRODUCT_CREATE_FORM;
    }

    @Override
    public String defaultMessage(String productName) {
        return "요청에 맞는 입력 화면을 준비했습니다.";
    }

    @Override
    public List<String> rootChildren() {
        return children("package_sale_product_create_summary_card", "package_sale_product_create_form_card");
    }
}
