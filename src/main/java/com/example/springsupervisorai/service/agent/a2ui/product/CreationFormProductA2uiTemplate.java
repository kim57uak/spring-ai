package com.example.springsupervisorai.service.agent.a2ui.product;

import com.example.springsupervisorai.service.agent.a2ui.common.A2uiTemplateView;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Renders a standard A2UI form for product creation inputs.
 */
@Component
public class CreationFormProductA2uiTemplate extends AbstractProductA2uiTemplate {

    @Override
    public A2uiTemplateView view() {
        return A2uiTemplateView.CREATION_FORM;
    }

    @Override
    public String defaultMessage(String productName) {
        return "요청에 맞는 입력 화면을 준비했습니다.";
    }

    @Override
    public List<String> rootChildren() {
        return children("creation_summary_card", "creation_form_card");
    }
}
