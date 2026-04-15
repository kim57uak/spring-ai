package com.example.springsupervisorai.service.agent.a2ui.product;

import java.util.List;

public interface ProductA2uiTemplate {

    A2uiTemplateView view();

    String defaultMessage(String productName);

    boolean requiresSummaryCoreFields();

    List<String> rootChildren();
}
