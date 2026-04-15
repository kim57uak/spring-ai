package com.example.springsupervisorai.service.agent.a2ui.product;

import java.util.ArrayList;
import java.util.List;

abstract class AbstractProductA2uiTemplate implements ProductA2uiTemplate {

    @Override
    public boolean requiresSummaryCoreFields() {
        return false;
    }

    protected List<String> children(String... sectionIds) {
        List<String> children = new ArrayList<>();
        for (String sectionId : sectionIds) {
            children.add(sectionId);
        }
        return List.copyOf(children);
    }
}
