package com.example.springsupervisorai.service.agent.a2ui.product;

import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
import com.example.springsupervisorai.service.agent.a2ui.common.SupervisorA2uiService;
import com.example.springsupervisorai.service.agent.result.DownstreamResultInterpreter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class DefaultSupervisorProductInfoA2uiService implements SupervisorA2uiService {

    private static final Logger logger = LoggerFactory.getLogger(DefaultSupervisorProductInfoA2uiService.class);
    private static final String STANDARD_CATALOG_ID = "https://a2ui.org/specification/v0_8/standard_catalog_definition.json";
    private final ObjectMapper objectMapper;
    private final ProductA2uiTemplateRegistry templateRegistry;

    public DefaultSupervisorProductInfoA2uiService(
            ObjectMapper objectMapper,
            ProductA2uiTemplateRegistry templateRegistry
    ) {
        this.objectMapper = objectMapper;
        this.templateRegistry = templateRegistry;
    }

    @Override
    public Optional<A2uiRenderResult> build(SupervisorPlanningContext context, A2uiTemplateView selectedView, String message) {
        if (context == null || context.getResults() == null || context.getResults().isEmpty()) {
            logger.info("Supervisor product A2UI skipped: no downstream results");
            return Optional.empty();
        }
        ProductA2uiTemplate template = templateRegistry.resolve(selectedView == null ? A2uiTemplateView.SUMMARY : selectedView);
        for (DownstreamCallResult result : context.getResults()) {
            if (!isSuccessfulProductResult(result)) {
                continue;
            }
            logger.info("Supervisor product A2UI inspecting result sessionId={}, taskId={}, agentKey={}, payloadLength={}",
                    context.getSessionId(), result.taskId(), result.agentKey(),
                    result.payload() == null ? 0 : result.payload().length());
            Optional<JsonNode> productNode = extractProductNode(result.payload());
            if (productNode.isEmpty()) {
                logger.info("Supervisor product A2UI productDetail not found sessionId={}, taskId={}",
                        context.getSessionId(), result.taskId());
                continue;
            }
            Optional<List<Map<String, Object>>> protocolMessages = buildProtocolMessages(productNode.get(), context, result, template);
            if (protocolMessages.isEmpty()) {
                logger.info("Supervisor product A2UI message build returned empty sessionId={}, taskId={}",
                        context.getSessionId(), result.taskId());
                continue;
            }
            try {
                String name = text(productNode.get().path("baseProductInfo"), "saleProdNm");
                String resolvedMessage = message == null || message.isBlank()
                        ? template.defaultMessage(name.isBlank() ? "상품" : name)
                        : message;
                logger.info("Supervisor product A2UI message built sessionId={}, taskId={}, requestedView={}",
                        context.getSessionId(), result.taskId(), template.view());
                return Optional.of(new A2uiRenderResult(resolvedMessage, objectMapper.writeValueAsString(protocolMessages.get())));
            } catch (Exception ex) {
                logger.warn("Supervisor product A2UI serialization failed sessionId={}, taskId={}, error={}",
                        context.getSessionId(), result.taskId(), ex.getMessage());
                return Optional.empty();
            }
        }
        logger.info("Supervisor product A2UI skipped: no eligible product result sessionId={}", context.getSessionId());
        return Optional.empty();
    }

    private boolean isSuccessfulProductResult(DownstreamCallResult result) {
        if (result == null || !"product".equalsIgnoreCase(result.agentKey())) {
            return false;
        }
        return DownstreamResultInterpreter.assess(result).outcome() == DownstreamResultInterpreter.Outcome.SUCCESS;
    }

    private Optional<List<Map<String, Object>>> buildProtocolMessages(
            JsonNode productRoot,
            SupervisorPlanningContext context,
            DownstreamCallResult result,
            ProductA2uiTemplate template
    ) {
        JsonNode base = productRoot.path("baseProductInfo");
        if (!base.isObject()) {
            return Optional.empty();
        }
        return buildStandardMessageSequence(productRoot, base, context, result, template);
    }

    private Optional<List<Map<String, Object>>> buildStandardMessageSequence(
            JsonNode productRoot,
            JsonNode base,
            SupervisorPlanningContext context,
            DownstreamCallResult result,
            ProductA2uiTemplate template
    ) {
        String productCode = text(base, "saleProdCd");
        String name = text(base, "saleProdNm");
        String departureDate = text(base, "depDay");
        String arrivalDate = text(base, "arrDay");
        Long price = firstLong(base, "adtTotlAmt", "adtAmt");

        List<String> missingFields = new ArrayList<>();
        require(productCode, "productCode", missingFields);
        require(name, "name", missingFields);
        if (template.requiresSummaryCoreFields()) {
            requireDate(departureDate, "departureDate", missingFields);
            requireDate(arrivalDate, "arrivalDate", missingFields);
            if (price == null || price < 0) {
                missingFields.add("price");
            }
        }
        if (!missingFields.isEmpty()) {
            logger.info("Supervisor product A2UI missing required fields sessionId={}, taskId={}, fields={}",
                    context.getSessionId(), result.taskId(), missingFields);
            return Optional.empty();
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productCode", productCode);
        data.put("name", name);
        data.put("departureDate", departureDate);
        data.put("arrivalDate", arrivalDate);
        putIfNotBlank(data, "departureCity", text(base, "depCityNm"));
        putIfNotBlank(data, "arrivalCity", text(base, "arrCityNm"));
        putIfPositive(data, "nights", base.path("trvlNgtCnt").asInt(0));
        putIfPositive(data, "days", base.path("trvlDayCnt").asInt(0));
        data.put("price", price);
        data.put("currency", "KRW");
        putIfNotBlank(data, "theme", text(base, "thmNm"));
        putIfNotBlank(data, "brand", text(base, "brndNm"));
        String airline = buildAirline(base);
        putIfNotBlank(data, "airline", airline);
        String thumbnailUrl = extractThumbnail(productRoot, base);
        putIfNotBlank(data, "thumbnailUrl", thumbnailUrl);
        putIfPositiveLong(data, "adultPrice", firstLong(base, "adtTotlAmt", "adtAmt"));
        putIfPositiveLong(data, "childPrice", firstLong(base, "chdTotlAmt", "chdAmt"));
        putIfPositiveLong(data, "infantPrice", firstLong(base, "infTotlAmt", "infAmt"));
        putIfPositiveLong(data, "depositPrice", firstLong(base, "dnpyTlAmt"));
        putIfNotBlank(data, "singleRoomNote", text(base, "snglAddAmtDesc"));
        data.put("includedItems", limitItems(descriptionItems(base.path("trvlExpnInclList")), 4));
        data.put("optionalItems", limitItems(descriptionItems(base.path("trvlChcExpnList")), 3));
        data.put("timeline", timelineItems(productRoot.path("itineraryInfo").path("schdInfoList")));
        data.put("noticeItems", limitItems(noticeItems(productRoot, base), 4));
        putIfNotBlank(data, "meetingDate", productRoot.path("itineraryInfo").path("meetInfoBcVo").path("sndgMeetDt").asText("").trim());
        putIfNotBlank(data, "meetingTime", productRoot.path("itineraryInfo").path("meetInfoBcVo").path("sndgMeetTm").asText("").trim());
        putIfNotBlank(data, "meetingAirport", productRoot.path("itineraryInfo").path("meetInfoBcVo").path("aptCd").asText("").trim());

        String surfaceId = "package-product-" + result.taskId();
        List<Map<String, Object>> components = buildStandardComponents(surfaceId, data, template);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(surfaceUpdate(surfaceId, components));
        messages.add(dataModelUpdate(surfaceId, "reservation", List.of(
                stringEntry("bookerName", ""),
                stringEntry("contact", ""),
                stringEntry("headCount", "1"),
                stringEntry("birthDate", "")
        )));
        messages.add(beginRendering(surfaceId, "root"));

        return Optional.of(List.copyOf(messages));
    }

    private Optional<JsonNode> extractProductNode(String payload) {
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode structuredDataProduct = root.path("structuredData").path("productDetail");
            if (!structuredDataProduct.isMissingNode() && !structuredDataProduct.isNull()) {
                logger.info("Supervisor product A2UI found structuredData.productDetail");
                Optional<JsonNode> structuredFound = findProductNode(structuredDataProduct);
                if (structuredFound.isPresent()) {
                    return structuredFound;
                }
            }
            logger.info("Supervisor product A2UI falling back to raw payload scan");
            return findProductNode(root);
        } catch (Exception ex) {
            logger.warn("Supervisor product A2UI payload parse failed error={}", ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<JsonNode> findProductNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Optional.empty();
        }
        if (node.isObject() && node.has("baseProductInfo") && node.path("baseProductInfo").isObject()) {
            return Optional.of(node);
        }
        if (node.isTextual()) {
            String raw = node.asText("");
            if (raw.startsWith("{") || raw.startsWith("[")) {
                try {
                    return findProductNode(objectMapper.readTree(raw));
                } catch (Exception ignored) {
                    return Optional.empty();
                }
            }
            return Optional.empty();
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                Optional<JsonNode> found = findProductNode(item);
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        }
        if (node.isObject()) {
            Iterator<JsonNode> values = node.elements();
            while (values.hasNext()) {
                Optional<JsonNode> found = findProductNode(values.next());
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
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

    private List<Map<String, Object>> buildStandardComponents(
            String surfaceId,
            Map<String, Object> data,
            ProductA2uiTemplate template
    ) {
        List<Map<String, Object>> components = new ArrayList<>();

        components.add(component("root", "Column", Map.of(
                "children", explicit(template.rootChildren()),
                "alignment", "stretch"
        )));

        addSummaryCard(components, data);
        addPricingCard(components, data);
        addTimelineCard(components, data);
        addNoticeCard(components, data);
        addReservationCard(components, data);

        return components;
    }

    private void addSummaryCard(List<Map<String, Object>> components, Map<String, Object> data) {
        List<String> summaryChildren = new ArrayList<>();
        if (hasText(data.get("thumbnailUrl"))) {
            summaryChildren.add("summary_image");
            components.add(component("summary_image", "Image", Map.of(
                    "url", literal(String.valueOf(data.get("thumbnailUrl"))),
                    "altText", literal(String.valueOf(data.getOrDefault("name", "상품 대표 이미지"))),
                    "usageHint", "largeFeature",
                    "fit", "cover"
            )));
        }
        summaryChildren.add("summary_code");
        summaryChildren.add("summary_title");
        if (hasText(data.get("theme")) || hasText(data.get("brand")) || hasText(data.get("airline"))) {
            summaryChildren.add("summary_tags");
        }
        summaryChildren.add("summary_trip");
        summaryChildren.add("summary_route");
        summaryChildren.add("summary_price");

        components.add(textComponent("summary_code", String.valueOf(data.getOrDefault("productCode", "")), "caption"));
        components.add(textComponent("summary_title", String.valueOf(data.getOrDefault("name", "상품 상세")), "h3"));
        if (summaryChildren.contains("summary_tags")) {
            components.add(textComponent(
                    "summary_tags",
                    joinNonBlank(" · ", data.get("theme"), data.get("brand"), data.get("airline")),
                    "body"
            ));
        }
        components.add(textComponent(
                "summary_trip",
                joinNonBlank(" | ",
                        displayValue(data.get("departureDate"), "-"),
                        displayValue(data.get("arrivalDate"), "-"),
                        travelPeriod(data.get("nights"), data.get("days"))
                ),
                "body"
        ));
        components.add(textComponent(
                "summary_route",
                joinNonBlank(" → ", data.get("departureCity"), data.get("arrivalCity")),
                "body"
        ));
        components.add(textComponent(
                "summary_price",
                "성인 기준가: " + formatMoney(data.get("price"), data.get("currency")),
                "h4"
        ));

        components.add(component("summary_body", "Column", Map.of(
                "children", explicit(summaryChildren),
                "alignment", "stretch"
        )));
        components.add(component("summary_card", "Card", Map.of("child", "summary_body")));
    }

    private void addPricingCard(List<Map<String, Object>> components, Map<String, Object> data) {
        List<String> pricingChildren = new ArrayList<>(List.of(
                "pricing_title",
                "pricing_adult",
                "pricing_child",
                "pricing_infant",
                "pricing_deposit"
        ));
        components.add(textComponent("pricing_title", "가격 정보", "h4"));
        components.add(textComponent("pricing_adult", "성인: " + formatMoney(data.get("adultPrice"), "KRW"), "body"));
        components.add(textComponent("pricing_child", "아동: " + formatMoney(data.get("childPrice"), "KRW"), "body"));
        components.add(textComponent("pricing_infant", "유아: " + formatMoney(data.get("infantPrice"), "KRW"), "body"));
        components.add(textComponent("pricing_deposit", "계약금: " + formatMoney(data.get("depositPrice"), "KRW"), "body"));
        if (hasText(data.get("singleRoomNote"))) {
            pricingChildren.add("pricing_single_room");
            components.add(textComponent("pricing_single_room", "1인 객실: " + data.get("singleRoomNote"), "body"));
        }
        pricingChildren.add("pricing_included_title");
        pricingChildren.add("pricing_included_list");
        pricingChildren.add("pricing_optional_title");
        pricingChildren.add("pricing_optional_list");
        components.add(textComponent("pricing_included_title", "포함 사항", "h5"));
        addTextList(components, "pricing_included_list", mapItems(data.get("includedItems"), item ->
                joinNonBlank(" ", item.get("category"), item.get("description"))));
        components.add(textComponent("pricing_optional_title", "선택 경비", "h5"));
        addTextList(components, "pricing_optional_list", mapItems(data.get("optionalItems"), item ->
                joinNonBlank(" ", item.get("category"), item.get("description"))));

        components.add(component("pricing_body", "Column", Map.of(
                "children", explicit(pricingChildren),
                "alignment", "stretch"
        )));
        components.add(component("pricing_card", "Card", Map.of("child", "pricing_body")));
    }

    private void addTimelineCard(List<Map<String, Object>> components, Map<String, Object> data) {
        List<String> timelineChildren = new ArrayList<>(List.of("timeline_title"));
        components.add(textComponent("timeline_title", "일정 정보", "h4"));
        if (hasText(data.get("meetingDate")) || hasText(data.get("meetingTime")) || hasText(data.get("meetingAirport"))) {
            timelineChildren.add("timeline_meeting");
            components.add(textComponent(
                    "timeline_meeting",
                    "미팅: " + joinNonBlank(" / ", data.get("meetingDate"), data.get("meetingTime"), data.get("meetingAirport")),
                    "body"
            ));
        }
        timelineChildren.add("timeline_list");
        addTextList(components, "timeline_list", mapItems(data.get("timeline"), item ->
                joinNonBlank(" ",
                        item.get("day") == null ? "" : item.get("day") + "일차",
                        item.get("date"),
                        item.get("dayOfWeek"),
                        hotelLabel(item)
                )));

        components.add(component("timeline_body", "Column", Map.of(
                "children", explicit(timelineChildren),
                "alignment", "stretch"
        )));
        components.add(component("timeline_card", "Card", Map.of("child", "timeline_body")));
    }

    private void addNoticeCard(List<Map<String, Object>> components, Map<String, Object> data) {
        components.add(textComponent("notice_title", "규정 및 안내", "h4"));
        addTextList(components, "notice_list", mapItems(data.get("noticeItems"), item ->
                joinNonBlank(" ", item.get("title"), item.get("content"))));
        components.add(component("notice_body", "Column", Map.of(
                "children", explicit(List.of("notice_title", "notice_list")),
                "alignment", "stretch"
        )));
        components.add(component("notice_card", "Card", Map.of("child", "notice_body")));
    }

    private void addReservationCard(List<Map<String, Object>> components, Map<String, Object> data) {
        components.add(textComponent("reservation_title", "예약 생성", "h4"));
        components.add(component("reservation_booker", "TextField", Map.of(
                "label", literal("예약자"),
                "text", Map.of("path", "/reservation/bookerName", "literalString", ""),
                "textFieldType", "shortText"
        )));
        components.add(component("reservation_contact", "TextField", Map.of(
                "label", literal("연락처"),
                "text", Map.of("path", "/reservation/contact", "literalString", ""),
                "textFieldType", "shortText"
        )));
        components.add(component("reservation_head_count", "TextField", Map.of(
                "label", literal("인원수"),
                "text", Map.of("path", "/reservation/headCount", "literalString", "1"),
                "textFieldType", "number"
        )));
        components.add(component("reservation_birth_date", "TextField", Map.of(
                "label", literal("생년월일"),
                "text", Map.of("path", "/reservation/birthDate", "literalString", ""),
                "textFieldType", "shortText"
        )));
        components.add(textComponent("reservation_submit_text", "예약 생성", "body"));
        components.add(component("reservation_submit", "Button", Map.of(
                "child", "reservation_submit_text",
                "primary", true,
                "action", Map.of(
                        "name", "submit_reservation",
                        "context", List.of(
                                contextEntry("productCode", literal(String.valueOf(data.getOrDefault("productCode", "")))),
                                contextEntry("bookerName", pathValue("/reservation/bookerName")),
                                contextEntry("contact", pathValue("/reservation/contact")),
                                contextEntry("headCount", pathValue("/reservation/headCount")),
                                contextEntry("birthDate", pathValue("/reservation/birthDate"))
                        )
                )
        )));
        components.add(component("reservation_body", "Column", Map.of(
                "children", explicit(List.of(
                        "reservation_title",
                        "reservation_booker",
                        "reservation_contact",
                        "reservation_head_count",
                        "reservation_birth_date",
                        "reservation_submit"
                )),
                "alignment", "stretch"
        )));
        components.add(component("reservation_card", "Card", Map.of("child", "reservation_body")));
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
        return Map.of("literalString", value == null ? "" : value);
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
                "valueString", value == null ? "" : value
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

    private List<Map<String, Object>> descriptionItems(JsonNode list) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (!list.isArray()) {
            return items;
        }
        for (JsonNode item : list) {
            String category = item.path("trvlExpnClstNm").asText("").trim();
            String description = item.path("trvlExpnDesc").asText("").trim();
            Map<String, Object> row = new LinkedHashMap<>();
            putIfNotBlank(row, "category", category);
            putIfNotBlank(row, "description", description);
            if (!row.isEmpty()) {
                items.add(row);
            }
        }
        return items;
    }

    private List<Map<String, Object>> timelineItems(JsonNode list) {
        List<Map<String, Object>> timeline = new ArrayList<>();
        if (!list.isArray()) {
            return timeline;
        }
        for (JsonNode item : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", item.path("schdDay").asInt(0));
            putIfNotBlank(row, "date", item.path("strtDt").asText("").trim());
            putIfNotBlank(row, "dayOfWeek", item.path("strDow").asText("").trim());
            putIfNotBlank(row, "hotelName", item.path("htlInfoList").path(0).path("htlKoNm").asText("").trim());
            putIfNotBlank(row, "hotelLocation", item.path("htlInfoList").path(0).path("locaDesc").asText("").trim());
            if (!row.isEmpty()) {
                timeline.add(row);
            }
        }
        return timeline;
    }

    private List<Map<String, Object>> noticeItems(JsonNode productRoot, JsonNode base) {
        List<Map<String, Object>> notices = new ArrayList<>();
        addNotice(notices, "안내", productRoot.path("guidInfo").path("guidRmkCont").asText("").trim());
        addNotice(notices, "인솔", productRoot.path("tcInfo").path("tcRmkCont").asText("").trim());
        addNotice(notices, "예약 유의", productRoot.path("noteResInfo").path("noteResRmkCont").asText("").trim());
        addNotice(notices, "객실", base.path("prcGdncBcVo").path("rmChagRmkCont").asText("").trim());
        addNotice(notices, "아동/유아", base.path("prcGdncBcVo").path("chdInclRoomCont").asText("").trim());
        return notices;
    }

    private void addNotice(List<Map<String, Object>> notices, String title, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("title", title);
        row.put("content", content);
        notices.add(row);
    }

    private <T> List<T> limitItems(List<T> items, int limit) {
        if (items == null || items.isEmpty() || limit <= 0) {
            return List.of();
        }
        return items.size() <= limit ? List.copyOf(items) : List.copyOf(items.subList(0, limit));
    }

    private String extractThumbnail(JsonNode productRoot, JsonNode base) {
        String fromBase = firstImageUrl(base.path("rppdCntntInfoList"));
        if (!fromBase.isBlank()) {
            return fromBase;
        }
        String fromRoot = firstImageUrl(productRoot.path("rppdCntntInfoList"));
        if (!fromRoot.isBlank()) {
            return fromRoot;
        }
        return "";
    }

    private String firstImageUrl(JsonNode list) {
        if (!list.isArray() || list.isEmpty()) {
            return "";
        }
        String fallback = "";
        for (JsonNode item : list) {
            String url = item.path("rprsProdCntntUrlAdrs").asText("").trim();
            if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                continue;
            }
            String lower = url.toLowerCase();
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")) {
                return url;
            }
            if (fallback.isBlank() && !lower.endsWith(".gif")) {
                fallback = url;
            }
            if (fallback.isBlank()) {
                fallback = url;
            }
        }
        return fallback;
    }

    private String buildAirline(JsonNode base) {
        String dep = text(base, "depFlgtCd");
        String arr = text(base, "arrFlgtCd");
        if (dep.isBlank() && arr.isBlank()) {
            return "";
        }
        if (dep.isBlank()) {
            return arr;
        }
        if (arr.isBlank()) {
            return dep;
        }
        return dep + "/" + arr;
    }

    private Long firstLong(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isNumber()) {
                return value.asLong();
            }
            if (value.isTextual()) {
                try {
                    return Long.parseLong(value.asText().trim());
                } catch (Exception ignored) {
                    // continue
                }
            }
        }
        return null;
    }

    private String text(JsonNode node, String field) {
        return node.path(field).asText("").trim();
    }

    private void require(String value, String field, List<String> missingFields) {
        if (value == null || value.isBlank()) {
            missingFields.add(field);
        }
    }

    private void requireDate(String value, String field, List<String> missingFields) {
        if (value == null || !value.matches("\\d{8}")) {
            missingFields.add(field);
        }
    }

    private void putIfNotBlank(Map<String, Object> data, String key, String value) {
        if (value != null && !value.isBlank()) {
            data.put(key, value);
        }
    }

    private void putIfPositiveLong(Map<String, Object> data, String key, Long value) {
        if (value != null && value >= 0) {
            data.put(key, value);
        }
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

    private String hotelLabel(Map<String, Object> item) {
        String hotelName = hasText(item.get("hotelName")) ? String.valueOf(item.get("hotelName")) : "";
        String hotelLocation = hasText(item.get("hotelLocation")) ? "(" + item.get("hotelLocation") + ")" : "";
        return joinNonBlank(" ", hotelName, hotelLocation);
    }

    private void putIfPositive(Map<String, Object> data, String key, int value) {
        if (value > 0) {
            data.put(key, value);
        }
    }

}
