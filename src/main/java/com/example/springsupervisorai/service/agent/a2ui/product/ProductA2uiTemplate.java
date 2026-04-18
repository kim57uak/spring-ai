package com.example.springsupervisorai.service.agent.a2ui.product;

import com.example.springsupervisorai.service.agent.a2ui.common.A2uiTemplateView;

import java.util.List;

public interface ProductA2uiTemplate {

    A2uiTemplateView view();

    String defaultMessage(String productName);

    boolean requiresSummaryCoreFields();

    List<String> rootChildren();
}
