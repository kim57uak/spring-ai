package com.example.springsupervisorai.service.agent.a2ui;

import com.example.springsupervisorai.model.DownstreamCallResult;
import com.example.springsupervisorai.model.SupervisorPlanningContext;
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
    private static final String CUSTOM_CATALOG_ID = "https://hanatour.com/a2ui/catalogs/package-product-v1";
    private final ObjectMapper objectMapper;

    public DefaultSupervisorProductInfoA2uiService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<A2uiRenderResult> build(SupervisorPlanningContext context) {
        if (context == null || context.getResults() == null || context.getResults().isEmpty()) {
            logger.info("Supervisor product A2UI skipped: no downstream results");
            return Optional.empty();
        }
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
            Optional<Map<String, Object>> envelope = buildEnvelope(productNode.get(), context, result);
            if (envelope.isEmpty()) {
                logger.info("Supervisor product A2UI envelope build returned empty sessionId={}, taskId={}",
                        context.getSessionId(), result.taskId());
                continue;
            }
            try {
                String message = String.valueOf(envelope.get().getOrDefault("message", "상품 상세를 준비했습니다."));
                Object a2uiValue = envelope.get().get("a2ui");
                String view = "";
                if (a2uiValue instanceof Map<?, ?> a2uiMap) {
                    Object rawView = a2uiMap.get("view");
                    view = rawView == null ? "" : String.valueOf(rawView);
                }
                logger.info("Supervisor product A2UI envelope built sessionId={}, taskId={}, view={}",
                        context.getSessionId(), result.taskId(), view);
                return Optional.of(new A2uiRenderResult(message, objectMapper.writeValueAsString(envelope.get())));
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

    private Optional<Map<String, Object>> buildEnvelope(JsonNode productRoot, SupervisorPlanningContext context, DownstreamCallResult result) {
        JsonNode base = productRoot.path("baseProductInfo");
        if (!base.isObject()) {
            return Optional.empty();
        }
        RequestedView requestedView = resolveRequestedView(context.getUserMessage());
        return switch (requestedView) {
            case PACKAGE_PRICING_DETAIL -> buildPricingEnvelope(productRoot, base, context, result);
            case PACKAGE_ITINERARY_TIMELINE -> buildTimelineEnvelope(productRoot, base, context, result);
            case PACKAGE_RESULT_CARD -> buildSummaryEnvelope(productRoot, base, context, result);
        };
    }

    private Optional<Map<String, Object>> buildSummaryEnvelope(
            JsonNode productRoot,
            JsonNode base,
            SupervisorPlanningContext context,
            DownstreamCallResult result
    ) {
        String productCode = text(base, "saleProdCd");
        String name = text(base, "saleProdNm");
        String departureDate = text(base, "depDay");
        String arrivalDate = text(base, "arrDay");
        Long price = firstLong(base, "adtTotlAmt", "adtAmt");

        List<String> missingFields = new ArrayList<>();
        require(productCode, "productCode", missingFields);
        require(name, "name", missingFields);
        requireDate(departureDate, "departureDate", missingFields);
        requireDate(arrivalDate, "arrivalDate", missingFields);
        if (price == null || price < 0) {
            missingFields.add("price");
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
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(surfaceUpdate(surfaceId, List.of(
                component("root", "Column", Map.of(
                        "children", Map.of("explicitList", List.of("product_card", "reservation_form"))
                )),
                component("product_card", "ProductOverviewCard", Map.of(
                        "data", data
                )),
                component("reservation_form", "ReservationForm", Map.of(
                        "title", literal("예약 생성"),
                        "productCode", literal(productCode),
                        "fields", List.of(
                                formField("bookerName", "예약자", "text", "홍길동", "/reservation/bookerName"),
                                formField("contact", "연락처", "text", "010-1234-5678", "/reservation/contact"),
                                formField("headCount", "인원수", "number", "1", "/reservation/headCount"),
                                formField("birthDate", "생년월일", "text", "19900101", "/reservation/birthDate")
                        ),
                        "action", Map.of(
                                "name", "submit_reservation",
                                "context", List.of(
                                        contextEntry("intent", literal("예약생성해줘")),
                                        contextEntry("productCode", literal(productCode)),
                                        contextEntry("bookerName", pathValue("/reservation/bookerName")),
                                        contextEntry("contact", pathValue("/reservation/contact")),
                                        contextEntry("headCount", pathValue("/reservation/headCount")),
                                        contextEntry("birthDate", pathValue("/reservation/birthDate"))
                                )
                        )
                ))
        )));
        messages.add(dataModelUpdate(surfaceId, "reservation", List.of(
                stringEntry("bookerName", ""),
                stringEntry("contact", ""),
                stringEntry("headCount", "1"),
                stringEntry("birthDate", "")
        )));
        messages.add(beginRendering(surfaceId, "root"));

        Map<String, Object> a2ui = new LinkedHashMap<>();
        a2ui.put("protocolVersion", "0.8");
        a2ui.put("catalogId", CUSTOM_CATALOG_ID);
        a2ui.put("messages", messages);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("sessionId", context.getSessionId());
        meta.put("taskId", result.taskId());
        meta.put("sourceAgent", result.agentKey());
        meta.put("schemaValidated", true);
        meta.put("missingFields", List.of());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("version", "1.0");
        envelope.put("message", name + " 상품 상세를 준비했습니다.");
        envelope.put("a2ui", a2ui);
        envelope.put("meta", meta);
        return Optional.of(envelope);
    }

    private Optional<Map<String, Object>> buildPricingEnvelope(
            JsonNode productRoot,
            JsonNode base,
            SupervisorPlanningContext context,
            DownstreamCallResult result
    ) {
        String productCode = text(base, "saleProdCd");
        String name = text(base, "saleProdNm");
        if (productCode.isBlank() || name.isBlank()) {
            return Optional.empty();
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productCode", productCode);
        data.put("name", name);
        putIfPositiveLong(data, "adultPrice", firstLong(base, "adtTotlAmt", "adtAmt"));
        putIfPositiveLong(data, "childPrice", firstLong(base, "chdTotlAmt", "chdAmt"));
        putIfPositiveLong(data, "infantPrice", firstLong(base, "infTotlAmt", "infAmt"));
        putIfPositiveLong(data, "depositPrice", firstLong(base, "dnpyTlAmt"));
        putIfPositiveLong(data, "singleRoomPrice", firstLong(base, "snglAddAmt"));
        putIfNotBlank(data, "singleRoomNote", text(base, "snglAddAmtDesc"));
        putIfNotBlank(data, "adultNotice", base.path("prcGdncBcVo").path("amtFixRmkCont").asText("").trim());
        data.put("includedItems", descriptionItems(base.path("trvlExpnInclList")));
        data.put("optionalItems", descriptionItems(base.path("trvlChcExpnList")));

        Map<String, Object> a2ui = new LinkedHashMap<>();
        a2ui.put("schemaVersion", "0.8");
        a2ui.put("view", "package_pricing_detail");
        a2ui.put("data", data);
        a2ui.put("actions", List.of(action("package.view_summary", "상품 요약", Map.of(
                "productCode", productCode,
                "view", "package_result_card"
        ))));

        return Optional.of(envelope(context, result, name + " 요금 상세를 준비했습니다.", a2ui));
    }

    private Optional<Map<String, Object>> buildTimelineEnvelope(
            JsonNode productRoot,
            JsonNode base,
            SupervisorPlanningContext context,
            DownstreamCallResult result
    ) {
        String productCode = text(base, "saleProdCd");
        String name = text(base, "saleProdNm");
        JsonNode scheduleList = productRoot.path("itineraryInfo").path("schdInfoList");
        if (productCode.isBlank() || name.isBlank() || !scheduleList.isArray() || scheduleList.isEmpty()) {
            return Optional.empty();
        }

        List<Map<String, Object>> timeline = new ArrayList<>();
        for (JsonNode item : scheduleList) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", item.path("schdDay").asInt(0));
            putIfNotBlank(row, "date", item.path("strtDt").asText("").trim());
            putIfNotBlank(row, "dayOfWeek", item.path("strDow").asText("").trim());
            putIfNotBlank(row, "hotelName", item.path("htlInfoList").path(0).path("htlKoNm").asText("").trim());
            putIfNotBlank(row, "hotelLocation", item.path("htlInfoList").path(0).path("locaDesc").asText("").trim());
            row.put("inFlightNight", item.path("infltNgtYn").asText("N").equalsIgnoreCase("Y"));
            timeline.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productCode", productCode);
        data.put("name", name);
        data.put("timeline", timeline);
        putIfNotBlank(data, "meetingDate", productRoot.path("itineraryInfo").path("meetInfoBcVo").path("sndgMeetDt").asText("").trim());
        putIfNotBlank(data, "meetingTime", productRoot.path("itineraryInfo").path("meetInfoBcVo").path("sndgMeetTm").asText("").trim());
        putIfNotBlank(data, "meetingAirport", productRoot.path("itineraryInfo").path("meetInfoBcVo").path("aptCd").asText("").trim());

        Map<String, Object> a2ui = new LinkedHashMap<>();
        a2ui.put("schemaVersion", "0.8");
        a2ui.put("view", "package_itinerary_timeline");
        a2ui.put("data", data);
        a2ui.put("actions", List.of(action("package.view_summary", "상품 요약", Map.of(
                "productCode", productCode,
                "view", "package_result_card"
        ))));

        return Optional.of(envelope(context, result, name + " 일정 정보를 준비했습니다.", a2ui));
    }

    private Map<String, Object> envelope(
            SupervisorPlanningContext context,
            DownstreamCallResult result,
            String message,
            Map<String, Object> a2ui
    ) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("sessionId", context.getSessionId());
        meta.put("taskId", result.taskId());
        meta.put("sourceAgent", result.agentKey());
        meta.put("schemaValidated", true);
        meta.put("missingFields", List.of());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("version", "1.0");
        envelope.put("message", message);
        envelope.put("a2ui", a2ui);
        envelope.put("meta", meta);
        return envelope;
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

    private Map<String, Object> action(String id, String label, Map<String, Object> payloadTemplate) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("id", id);
        action.put("label", label);
        action.put("payloadTemplate", payloadTemplate);
        return action;
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

    private Map<String, Object> beginRendering(String surfaceId, String root) {
        return Map.of("beginRendering", Map.of(
                "surfaceId", surfaceId,
                "catalogId", CUSTOM_CATALOG_ID,
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

    private Map<String, Object> formField(String name, String label, String inputType, String placeholder, String path) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("name", name);
        field.put("label", literal(label));
        field.put("inputType", inputType);
        field.put("placeholder", literal(placeholder));
        field.put("value", Map.of("path", path, "literalString", ""));
        return field;
    }

    private Map<String, Object> stringEntry(String key, String value) {
        return Map.of(
                "key", key,
                "valueString", value == null ? "" : value
        );
    }

    private RequestedView resolveRequestedView(String userMessage) {
        String normalized = userMessage == null ? "" : userMessage.toLowerCase();
        if (normalized.contains("package_pricing_detail") || normalized.contains("요금 상세")) {
            return RequestedView.PACKAGE_PRICING_DETAIL;
        }
        if (normalized.contains("package_itinerary_timeline") || normalized.contains("일정 보기") || normalized.contains("일정 상세")) {
            return RequestedView.PACKAGE_ITINERARY_TIMELINE;
        }
        return RequestedView.PACKAGE_RESULT_CARD;
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

    private void putIfPositive(Map<String, Object> data, String key, int value) {
        if (value > 0) {
            data.put(key, value);
        }
    }

    private enum RequestedView {
        PACKAGE_RESULT_CARD,
        PACKAGE_PRICING_DETAIL,
        PACKAGE_ITINERARY_TIMELINE
    }
}
