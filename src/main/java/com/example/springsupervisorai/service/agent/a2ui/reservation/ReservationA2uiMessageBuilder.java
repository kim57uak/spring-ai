package com.example.springsupervisorai.service.agent.a2ui.reservation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a standard-catalog reservation creation form.
 */
@Component
public class ReservationA2uiMessageBuilder {

    private static final String STANDARD_CATALOG_ID = "https://a2ui.org/specification/v0_8/standard_catalog_definition.json";

    public List<Map<String, Object>> build(String surfaceId, ReservationPresentationModel model) {
        List<Map<String, Object>> components = new ArrayList<>();
        components.add(component("root", "Column", Map.of(
                "children", explicit(List.of("reservation_summary_card", "reservation_form_card")),
                "alignment", "stretch"
        )));
        addSummaryCard(components, model);
        addReservationFormCard(components);

        return List.of(
                surfaceUpdate(surfaceId, components),
                dataModelUpdate(surfaceId, "reservation", List.of(
                        stringEntry("productCode", valueOrBlank(model.productCode())),
                        stringEntry("bookerName", valueOrBlank(model.bookerName())),
                        stringEntry("contact", valueOrBlank(model.contact())),
                        stringEntry("headCount", valueOrBlank(model.headCount()).isBlank() ? "1" : valueOrBlank(model.headCount())),
                        stringEntry("birthDate", valueOrBlank(model.birthDate()))
                )),
                beginRendering(surfaceId, "root")
        );
    }

    private void addSummaryCard(List<Map<String, Object>> components, ReservationPresentationModel model) {
        components.add(textComponent("reservation_summary_code", valueOrBlank(model.productCode()), "caption"));
        components.add(textComponent("reservation_summary_title", "예약 생성 입력", "h3"));
        List<String> children = new ArrayList<>(List.of("reservation_summary_code", "reservation_summary_title"));
        if (!valueOrBlank(model.productName()).isBlank()) {
            components.add(textComponent("reservation_summary_name", model.productName(), "body"));
            children.add("reservation_summary_name");
        }
        components.add(component("reservation_summary_body", "Column", Map.of(
                "children", explicit(children),
                "alignment", "stretch"
        )));
        components.add(component("reservation_summary_card", "Card", Map.of("child", "reservation_summary_body")));
    }

    private void addReservationFormCard(List<Map<String, Object>> components) {
        components.add(textComponent("reservation_form_title", "예약 파라미터", "h4"));
        components.add(component("reservation_form_product_code", "TextField", Map.of(
                "label", literal("상품 코드"),
                "text", pathValue("/reservation/productCode"),
                "textFieldType", "shortText"
        )));
        components.add(component("reservation_form_booker", "TextField", Map.of(
                "label", literal("예약자"),
                "text", pathValue("/reservation/bookerName"),
                "textFieldType", "shortText"
        )));
        components.add(component("reservation_form_contact", "TextField", Map.of(
                "label", literal("연락처"),
                "text", pathValue("/reservation/contact"),
                "textFieldType", "shortText"
        )));
        components.add(component("reservation_form_head_count", "TextField", Map.of(
                "label", literal("인원수"),
                "text", pathValue("/reservation/headCount"),
                "textFieldType", "number"
        )));
        components.add(component("reservation_form_birth_date", "TextField", Map.of(
                "label", literal("생년월일"),
                "text", pathValue("/reservation/birthDate"),
                "textFieldType", "shortText"
        )));
        components.add(textComponent("reservation_form_submit_text", "예약 생성", "body"));
        components.add(component("reservation_form_submit", "Button", Map.of(
                "child", "reservation_form_submit_text",
                "primary", true,
                "action", Map.of(
                        "name", "submit_reservation",
                        "context", List.of(
                                contextEntry("productCode", pathValue("/reservation/productCode")),
                                contextEntry("bookerName", pathValue("/reservation/bookerName")),
                                contextEntry("contact", pathValue("/reservation/contact")),
                                contextEntry("headCount", pathValue("/reservation/headCount")),
                                contextEntry("birthDate", pathValue("/reservation/birthDate"))
                        )
                )
        )));
        components.add(component("reservation_form_body", "Column", Map.of(
                "children", explicit(List.of(
                        "reservation_form_title",
                        "reservation_form_product_code",
                        "reservation_form_booker",
                        "reservation_form_contact",
                        "reservation_form_head_count",
                        "reservation_form_birth_date",
                        "reservation_form_submit"
                )),
                "alignment", "stretch"
        )));
        components.add(component("reservation_form_card", "Card", Map.of("child", "reservation_form_body")));
    }

    private Map<String, Object> surfaceUpdate(String surfaceId, List<Map<String, Object>> components) {
        return Map.of("surfaceUpdate", Map.of("surfaceId", surfaceId, "components", components));
    }

    private Map<String, Object> dataModelUpdate(String surfaceId, String path, List<Map<String, Object>> contents) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("surfaceId", surfaceId);
        payload.put("path", path);
        payload.put("contents", contents);
        return Map.of("dataModelUpdate", payload);
    }

    private Map<String, Object> beginRendering(String surfaceId, String root) {
        return Map.of("beginRendering", Map.of(
                "surfaceId", surfaceId,
                "catalogId", STANDARD_CATALOG_ID,
                "root", root
        ));
    }

    private Map<String, Object> component(String id, String type, Map<String, Object> props) {
        return Map.of("id", id, "component", Map.of(type, props));
    }

    private Map<String, Object> textComponent(String id, String text, String usageHint) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("text", literal(text));
        props.put("usageHint", usageHint);
        return component(id, "Text", props);
    }

    private Map<String, Object> literal(String value) {
        return Map.of("literalString", valueOrBlank(value));
    }

    private Map<String, Object> pathValue(String path) {
        return Map.of("path", path);
    }

    private Map<String, Object> stringEntry(String key, String value) {
        return Map.of("key", key, "valueString", valueOrBlank(value));
    }

    private Map<String, Object> contextEntry(String key, Map<String, Object> value) {
        return Map.of("key", key, "value", value);
    }

    private Map<String, Object> explicit(List<String> children) {
        return Map.of("explicitList", children);
    }

    private String valueOrBlank(String value) {
        return value == null ? "" : value;
    }
}
