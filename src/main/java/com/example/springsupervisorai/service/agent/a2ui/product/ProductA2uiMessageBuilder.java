package com.example.springsupervisorai.service.agent.a2ui.product;

import com.example.springsupervisorai.service.agent.a2ui.common.A2uiTemplateView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles normalized product data into A2UI standard catalog messages.
 */
@Component
public class ProductA2uiMessageBuilder {

    private static final String STANDARD_CATALOG_ID = "https://a2ui.org/specification/v0_8/standard_catalog_definition.json";

    public List<Map<String, Object>> build(String surfaceId, ProductPresentationModel model, ProductA2uiTemplate template) {
        List<Map<String, Object>> components = buildStandardComponents(model, template);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(surfaceUpdate(surfaceId, components));
        if (template.view() == A2uiTemplateView.PACKAGE_SALE_PRODUCT_CREATE_FORM) {
            messages.add(dataModelUpdate(surfaceId, "productCreate", List.of(
                    stringEntry("saleProductCode", valueOrBlank(model.creationProductCode())),
                    stringEntry("departureStartDay", valueOrBlank(model.creationDepartureStartDay())),
                    stringEntry("departureEndDay", valueOrBlank(model.creationDepartureEndDay())),
                    booleanEntry("allTarget", Boolean.TRUE.equals(model.creationAllTarget())),
                    arrayEntry("departureDays", model.creationDepartureDays() == null ? List.of() : model.creationDepartureDays())
            )));
        } else {
            messages.add(dataModelUpdate(surfaceId, "reservation", List.of(
                    stringEntry("bookerName", ""),
                    stringEntry("headCount", "1")
            )));
        }
        messages.add(beginRendering(surfaceId, "root"));
        return List.copyOf(messages);
    }

    private Map<String, Object> surfaceUpdate(String surfaceId, List<Map<String, Object>> components) {
        return Map.of("surfaceUpdate", Map.of(
                "surfaceId", surfaceId,
                "components", components
        ));
    }

    private Map<String, Object> dataModelUpdate(String surfaceId, String path, List<Map<String, Object>> contents) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("surfaceId", surfaceId);
        payload.put("path", path);
        payload.put("contents", contents);
        return Map.of("dataModelUpdate", payload);
    }

    private List<Map<String, Object>> buildStandardComponents(ProductPresentationModel model, ProductA2uiTemplate template) {
        List<Map<String, Object>> components = new ArrayList<>();

        components.add(component("root", "Column", Map.of(
                "children", explicit(template.rootChildren()),
                "alignment", "stretch"
        )));

        if (template.view() == A2uiTemplateView.PACKAGE_SALE_PRODUCT_CREATE_FORM) {
            addCreationSummaryCard(components, model);
            addCreationFormCard(components, model);
            return components;
        }

        addSummaryCard(components, model);
        addPricingCard(components, model);
        addTimelineCard(components, model);
        addNoticeCard(components, model);
        addReservationCard(components, model);

        return components;
    }

    private void addSummaryCard(List<Map<String, Object>> components, ProductPresentationModel model) {
        List<String> summaryChildren = new ArrayList<>();
        if (hasText(model.thumbnailUrl())) {
            summaryChildren.add("package_summary_image");
            components.add(component("package_summary_image", "Image", Map.of(
                    "url", literal(model.thumbnailUrl()),
                    "altText", literal(hasText(model.name()) ? model.name() : "상품 대표 이미지"),
                    "usageHint", "largeFeature",
                    "fit", "cover"
            )));
        }
        summaryChildren.add("package_summary_code");
        summaryChildren.add("package_summary_title");
        if (hasText(model.theme()) || hasText(model.brand()) || hasText(model.airline())) {
            summaryChildren.add("package_summary_tags");
        }
        summaryChildren.add("package_summary_trip");
        summaryChildren.add("package_summary_route");
        summaryChildren.add("package_summary_price");

        components.add(textComponent("package_summary_code", valueOrBlank(model.productCode()), "caption"));
        components.add(textComponent("package_summary_title", hasText(model.name()) ? model.name() : "상품 상세", "h3"));
        if (summaryChildren.contains("package_summary_tags")) {
            components.add(textComponent(
                    "package_summary_tags",
                    joinNonBlank(" · ", model.theme(), model.brand(), model.airline()),
                    "body"
            ));
        }
        components.add(textComponent(
                "package_summary_trip",
                joinNonBlank(" | ",
                        displayValue(model.departureDate(), "-"),
                        displayValue(model.arrivalDate(), "-"),
                        travelPeriod(model.nights(), model.days())
                ),
                "body"
        ));
        components.add(textComponent(
                "package_summary_route",
                joinNonBlank(" → ", model.departureCity(), model.arrivalCity()),
                "body"
        ));
        components.add(textComponent(
                "package_summary_price",
                "성인 기준가: " + formatMoney(model.price(), model.currency()),
                "h4"
        ));

        components.add(component("package_summary_body", "Column", Map.of(
                "children", explicit(summaryChildren),
                "alignment", "stretch"
        )));
        components.add(component("package_summary_card", "Card", Map.of("child", "package_summary_body")));
    }

    private void addPricingCard(List<Map<String, Object>> components, ProductPresentationModel model) {
        List<String> pricingChildren = new ArrayList<>(List.of(
                "package_pricing_title",
                "package_pricing_adult",
                "package_pricing_child",
                "package_pricing_infant",
                "package_pricing_deposit"
        ));
        components.add(textComponent("package_pricing_title", "가격 정보", "h4"));
        components.add(textComponent("package_pricing_adult", "성인: " + formatMoney(model.adultPrice(), "KRW"), "body"));
        components.add(textComponent("package_pricing_child", "아동: " + formatMoney(model.childPrice(), "KRW"), "body"));
        components.add(textComponent("package_pricing_infant", "유아: " + formatMoney(model.infantPrice(), "KRW"), "body"));
        components.add(textComponent("package_pricing_deposit", "계약금: " + formatMoney(model.depositPrice(), "KRW"), "body"));
        if (hasText(model.singleRoomNote())) {
            pricingChildren.add("package_pricing_single_room");
            components.add(textComponent("package_pricing_single_room", "1인 객실: " + model.singleRoomNote(), "body"));
        }
        pricingChildren.add("package_pricing_included_title");
        pricingChildren.add("package_pricing_included_list");
        pricingChildren.add("package_pricing_optional_title");
        pricingChildren.add("package_pricing_optional_list");
        components.add(textComponent("package_pricing_included_title", "포함 사항", "h5"));
        addTextList(components, "package_pricing_included_list", mapItems(model.includedItems(), item ->
                joinNonBlank(" ", item.get("category"), item.get("description"))));
        components.add(textComponent("package_pricing_optional_title", "선택 경비", "h5"));
        addTextList(components, "package_pricing_optional_list", mapItems(model.optionalItems(), item ->
                joinNonBlank(" ", item.get("category"), item.get("description"))));

        components.add(component("package_pricing_body", "Column", Map.of(
                "children", explicit(pricingChildren),
                "alignment", "stretch"
        )));
        components.add(component("package_pricing_card", "Card", Map.of("child", "package_pricing_body")));
    }

    private void addTimelineCard(List<Map<String, Object>> components, ProductPresentationModel model) {
        List<String> timelineChildren = new ArrayList<>(List.of("package_timeline_title"));
        components.add(textComponent("package_timeline_title", "일정 정보", "h4"));
        if (hasText(model.meetingDate()) || hasText(model.meetingTime()) || hasText(model.meetingAirport())) {
            timelineChildren.add("package_timeline_meeting");
            components.add(textComponent(
                    "package_timeline_meeting",
                    "미팅: " + joinNonBlank(" / ", model.meetingDate(), model.meetingTime(), model.meetingAirport()),
                    "body"
            ));
        }
        timelineChildren.add("package_timeline_list");
        addTextList(components, "package_timeline_list", mapItems(model.timeline(), item ->
                joinNonBlank(" ",
                        item.get("day") == null ? "" : item.get("day") + "일차",
                        item.get("date"),
                        item.get("dayOfWeek"),
                        hotelLabel(item)
                )));

        components.add(component("package_timeline_body", "Column", Map.of(
                "children", explicit(timelineChildren),
                "alignment", "stretch"
        )));
        components.add(component("package_timeline_card", "Card", Map.of("child", "package_timeline_body")));
    }

    private void addNoticeCard(List<Map<String, Object>> components, ProductPresentationModel model) {
        components.add(textComponent("package_notice_title", "규정 및 안내", "h4"));
        addTextList(components, "package_notice_list", mapItems(model.noticeItems(), item ->
                joinNonBlank(" ", item.get("title"), item.get("content"))));
        components.add(component("package_notice_body", "Column", Map.of(
                "children", explicit(List.of("package_notice_title", "package_notice_list")),
                "alignment", "stretch"
        )));
        components.add(component("package_notice_card", "Card", Map.of("child", "package_notice_body")));
    }

    private void addReservationCard(List<Map<String, Object>> components, ProductPresentationModel model) {
        components.add(textComponent("package_reservation_title", "예약 생성", "h4"));
        components.add(component("package_reservation_booker", "TextField", Map.of(
                "label", literal("예약자"),
                "text", Map.of("path", "/reservation/bookerName", "literalString", ""),
                "textFieldType", "shortText"
        )));
        components.add(component("package_reservation_head_count", "TextField", Map.of(
                "label", literal("인원수"),
                "text", Map.of("path", "/reservation/headCount", "literalString", "1"),
                "textFieldType", "number"
        )));
        components.add(textComponent("package_reservation_submit_text", "예약 생성", "body"));
        components.add(component("package_reservation_submit", "Button", Map.of(
                "child", "package_reservation_submit_text",
                "primary", true,
                "action", Map.of(
                        "name", "submit_reservation",
                        "context", List.of(
                                contextEntry("productCode", literal(valueOrBlank(model.productCode()))),
                                contextEntry("bookerName", pathValue("/reservation/bookerName")),
                                contextEntry("headCount", pathValue("/reservation/headCount"))
                        )
                )
        )));
        components.add(component("package_reservation_body", "Column", Map.of(
                "children", explicit(List.of(
                        "package_reservation_title",
                        "package_reservation_booker",
                        "package_reservation_head_count",
                        "package_reservation_submit"
                )),
                "alignment", "stretch"
        )));
        components.add(component("package_reservation_card", "Card", Map.of("child", "package_reservation_body")));
    }

    /**
     * 생성 화면 상단에 현재 입력 대상과 기본 가이드를 요약한다.
     */
    private void addCreationSummaryCard(List<Map<String, Object>> components, ProductPresentationModel model) {
        components.add(textComponent("package_sale_product_create_summary_code", valueOrBlank(model.creationProductCode()), "caption"));
        components.add(textComponent("package_sale_product_create_summary_title", "상품 생성 입력", "h3"));
        components.add(textComponent(
                "package_sale_product_create_summary_desc",
                "상품코드, 출발 기간, 출발 요일을 확인한 뒤 상품 생성 버튼을 실행하세요.",
                "body"
        ));
        components.add(component("package_sale_product_create_summary_body", "Column", Map.of(
                "children", explicit(List.of(
                        "package_sale_product_create_summary_code",
                        "package_sale_product_create_summary_title",
                        "package_sale_product_create_summary_desc"
                )),
                "alignment", "stretch"
        )));
        components.add(component("package_sale_product_create_summary_card", "Card", Map.of("child", "package_sale_product_create_summary_body")));
    }

    /**
     * 표준 A2UI 입력 컴포넌트만 사용해서 상품 생성 폼을 구성한다.
     */
    private void addCreationFormCard(List<Map<String, Object>> components, ProductPresentationModel model) {
        components.add(textComponent("package_sale_product_create_form_title", "생성 파라미터", "h4"));
        components.add(component("package_sale_product_create_sale_product_code", "TextField", Map.of(
                "label", literal("상품 코드"),
                "text", pathValue("/productCreate/saleProductCode"),
                "textFieldType", "shortText"
        )));
        components.add(component("package_sale_product_create_departure_start_day", "TextField", Map.of(
                "label", literal("출발 시작일"),
                "text", pathValue("/productCreate/departureStartDay"),
                "textFieldType", "shortText"
        )));
        components.add(component("package_sale_product_create_departure_end_day", "TextField", Map.of(
                "label", literal("출발 종료일"),
                "text", pathValue("/productCreate/departureEndDay"),
                "textFieldType", "shortText"
        )));
        components.add(component("package_sale_product_create_all_target", "CheckBox", Map.of(
                "label", literal("전체 대상"),
                "value", pathValue("/productCreate/allTarget")
        )));
        components.add(component("package_sale_product_create_departure_days", "MultipleChoice", Map.of(
                "variant", "checkbox",
                "selections", pathValue("/productCreate/departureDays"),
                "options", List.of(
                        choiceOption("mon", "월"),
                        choiceOption("tue", "화"),
                        choiceOption("wed", "수"),
                        choiceOption("thu", "목"),
                        choiceOption("fri", "금"),
                        choiceOption("sat", "토"),
                        choiceOption("sun", "일")
                )
        )));
        components.add(textComponent("package_sale_product_create_submit_text", "상품 생성", "body"));
        components.add(component("package_sale_product_create_submit", "Button", Map.of(
                "child", "package_sale_product_create_submit_text",
                "primary", true,
                "action", Map.of(
                        "name", "submit_product_creation",
                        "context", List.of(
                                contextEntry("saleProductCode", pathValue("/productCreate/saleProductCode")),
                                contextEntry("departureStartDay", pathValue("/productCreate/departureStartDay")),
                                contextEntry("departureEndDay", pathValue("/productCreate/departureEndDay")),
                                contextEntry("allTarget", pathValue("/productCreate/allTarget")),
                                contextEntry("departureDays", pathValue("/productCreate/departureDays"))
                        )
                )
        )));
        components.add(component("package_sale_product_create_form_body", "Column", Map.of(
                "children", explicit(List.of(
                        "package_sale_product_create_form_title",
                        "package_sale_product_create_sale_product_code",
                        "package_sale_product_create_departure_start_day",
                        "package_sale_product_create_departure_end_day",
                        "package_sale_product_create_all_target",
                        "package_sale_product_create_departure_days",
                        "package_sale_product_create_submit"
                )),
                "alignment", "stretch"
        )));
        components.add(component("package_sale_product_create_form_card", "Card", Map.of("child", "package_sale_product_create_form_body")));
    }

    private Map<String, Object> beginRendering(String surfaceId, String root) {
        return Map.of("beginRendering", Map.of(
                "surfaceId", surfaceId,
                "catalogId", STANDARD_CATALOG_ID,
                "root", root
        ));
    }

    private Map<String, Object> component(String id, String type, Map<String, Object> props) {
        return Map.of(
                "id", id,
                "component", Map.of(type, props)
        );
    }

    private Map<String, Object> literal(String value) {
        return Map.of("literalString", valueOrBlank(value));
    }

    private Map<String, Object> pathValue(String path) {
        return Map.of("path", path);
    }

    private Map<String, Object> contextEntry(String key, Map<String, Object> value) {
        return Map.of(
                "key", key,
                "value", value
        );
    }

    private Map<String, Object> stringEntry(String key, String value) {
        return Map.of(
                "key", key,
                "valueString", valueOrBlank(value)
        );
    }

    private Map<String, Object> booleanEntry(String key, boolean value) {
        return Map.of(
                "key", key,
                "valueBoolean", value
        );
    }

    private Map<String, Object> arrayEntry(String key, List<String> values) {
        return Map.of(
                "key", key,
                "valueArray", values == null ? List.of() : List.copyOf(values)
        );
    }

    private Map<String, Object> choiceOption(String value, String label) {
        return Map.of(
                "value", value,
                "label", literal(label)
        );
    }

    private Map<String, Object> textComponent(String id, String text, String usageHint) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("text", literal(text));
        if (usageHint != null && !usageHint.isBlank()) {
            props.put("usageHint", usageHint);
        }
        return component(id, "Text", props);
    }

    private Map<String, Object> explicit(List<String> children) {
        return Map.of("explicitList", children);
    }

    @SuppressWarnings("unchecked")
    private List<String> mapItems(Object raw, java.util.function.Function<Map<String, Object>, String> mapper) {
        if (!(raw instanceof List<?> items) || items.isEmpty()) {
            return List.of("정보가 없습니다.");
        }
        List<String> values = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String rendered = mapper.apply((Map<String, Object>) map);
            if (!rendered.isBlank()) {
                values.add(rendered);
            }
        }
        return values.isEmpty() ? List.of("정보가 없습니다.") : values;
    }

    private void addTextList(List<Map<String, Object>> components, String listId, List<String> items) {
        List<String> childIds = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            String childId = listId + "_item_" + i;
            childIds.add(childId);
            components.add(textComponent(childId, "• " + items.get(i), "body"));
        }
        components.add(component(listId, "List", Map.of(
                "children", explicit(childIds),
                "direction", "vertical",
                "alignment", "start"
        )));
    }

    private boolean hasText(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private String displayValue(Object value, String fallback) {
        return hasText(value) ? String.valueOf(value) : fallback;
    }

    private String travelPeriod(Object nights, Object days) {
        if (nights == null && days == null) {
            return "";
        }
        return String.valueOf(nights == null ? 0 : nights) + "박 " + String.valueOf(days == null ? 0 : days) + "일";
    }

    private String joinNonBlank(String separator, Object... values) {
        List<String> parts = new ArrayList<>();
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (!text.isBlank()) {
                parts.add(text);
            }
        }
        return String.join(separator, parts);
    }

    private String formatMoney(Object amount, Object currency) {
        if (!(amount instanceof Number number)) {
            return "-";
        }
        String rendered = String.format("%,d", number.longValue());
        if ("KRW".equals(String.valueOf(currency))) {
            return rendered + "원";
        }
        return rendered + (currency == null ? "" : " " + currency);
    }

    private String hotelLabel(Map<String, Object> item) {
        String hotelName = hasText(item.get("hotelName")) ? String.valueOf(item.get("hotelName")) : "";
        String hotelLocation = hasText(item.get("hotelLocation")) ? "(" + item.get("hotelLocation") + ")" : "";
        return joinNonBlank(" ", hotelName, hotelLocation);
    }

    private String valueOrBlank(String value) {
        return value == null ? "" : value;
    }
}
