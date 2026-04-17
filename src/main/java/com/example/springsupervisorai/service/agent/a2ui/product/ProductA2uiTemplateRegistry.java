package com.example.springsupervisorai.service.agent.a2ui.product;

import com.example.springsupervisorai.service.agent.a2ui.common.A2uiTemplateView;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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

    public ProductA2uiTemplate resolve(A2uiTemplateView view) {
        ProductA2uiTemplate template = templates.get(view);
        if (template != null) {
            return template;
        }
        ProductA2uiTemplate fallback = templates.get(A2uiTemplateView.SUMMARY);
        if (fallback == null) {
            throw new IllegalStateException("Missing SUMMARY product A2UI template");
        }
        return fallback;
    }
}
