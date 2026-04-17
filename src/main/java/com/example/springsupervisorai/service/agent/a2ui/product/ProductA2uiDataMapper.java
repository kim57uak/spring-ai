package com.example.springsupervisorai.service.agent.a2ui.product;

import com.example.springsupervisorai.model.RoutingPlan;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps product payload nodes into a stable rendering model consumed by A2UI message builders.
 */
@Component
public class ProductA2uiDataMapper {

    public Optional<ProductPresentationModel> map(JsonNode productRoot, ProductA2uiTemplate template) {
        if (isCreationFormPayload(productRoot)) {
            return mapCreationForm(productRoot);
        }
        JsonNode base = productRoot.path("baseProductInfo");
        if (!base.isObject()) {
            return Optional.empty();
        }

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
            return Optional.empty();
        }

        return Optional.of(new ProductPresentationModel(
                productCode,
                name,
                departureDate,
                arrivalDate,
                blankToNull(text(base, "depCityNm")),
                blankToNull(text(base, "arrCityNm")),
                positiveInt(base.path("trvlNgtCnt").asInt(0)),
                positiveInt(base.path("trvlDayCnt").asInt(0)),
                price,
                "KRW",
                blankToNull(text(base, "thmNm")),
                blankToNull(text(base, "brndNm")),
                blankToNull(buildAirline(base)),
                blankToNull(extractThumbnail(productRoot, base)),
                nonNegativeLong(firstLong(base, "adtTotlAmt", "adtAmt")),
                nonNegativeLong(firstLong(base, "chdTotlAmt", "chdAmt")),
                nonNegativeLong(firstLong(base, "infTotlAmt", "infAmt")),
                nonNegativeLong(firstLong(base, "dnpyTlAmt")),
                blankToNull(text(base, "snglAddAmtDesc")),
                limitItems(descriptionItems(base.path("trvlExpnInclList")), 4),
                limitItems(descriptionItems(base.path("trvlChcExpnList")), 3),
                timelineItems(productRoot.path("itineraryInfo").path("schdInfoList")),
                limitItems(noticeItems(productRoot, base), 4),
                blankToNull(productRoot.path("itineraryInfo").path("meetInfoBcVo").path("sndgMeetDt").asText("").trim()),
                blankToNull(productRoot.path("itineraryInfo").path("meetInfoBcVo").path("sndgMeetTm").asText("").trim()),
                blankToNull(productRoot.path("itineraryInfo").path("meetInfoBcVo").path("aptCd").asText("").trim()),
                null,
                null,
                null,
                null,
                List.of()
        ));
    }

    /**
     * 상품 생성 요청에 필요한 기본 필드를 typed model로 정규화한다.
     */
    private Optional<ProductPresentationModel> mapCreationForm(JsonNode productRoot) {
        String saleProductCode = blankToNull(text(productRoot, "saleProductCode"));
        String departureStartDay = blankToNull(text(productRoot, "departureStartDay"));
        String departureEndDay = blankToNull(text(productRoot, "departureEndDay"));
        List<String> departureDays = creationDepartureDays(productRoot);
        Boolean allTarget = parseYn(productRoot.path("allTarget").asText(""));

        List<String> missingFields = new ArrayList<>();
        require(saleProductCode, "saleProductCode", missingFields);
        require(departureStartDay, "departureStartDay", missingFields);
        require(departureEndDay, "departureEndDay", missingFields);
        if (!missingFields.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new ProductPresentationModel(
                saleProductCode,
                "상품 생성",
                departureStartDay,
                departureEndDay,
                null,
                null,
                null,
                null,
                null,
                "KRW",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                saleProductCode,
                departureStartDay,
                departureEndDay,
                allTarget,
                departureDays
        ));
    }

    /**
     * downstream 결과가 없을 때 routing plan 인자만으로 생성 입력 폼 seed를 만든다.
     */
    public ProductPresentationModel standaloneCreationSeed(Map<String, Object> arguments) {
        Map<String, Object> source = arguments == null ? Map.of() : arguments;
        String productCode = firstString(source, "saleProductCode", "saleProdCd", "productCode");
        String departureStartDay = firstString(source, "departureStartDay", "depStartDay", "startDay");
        String departureEndDay = firstString(source, "departureEndDay", "depEndDay", "endDay");
        Boolean allTarget = firstBoolean(source, "allTarget");
        List<String> departureDays = extractDepartureDays(source);
        return new ProductPresentationModel(
                productCode,
                "상품 생성",
                departureStartDay,
                departureEndDay,
                null,
                null,
                null,
                null,
                null,
                "KRW",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                productCode,
                departureStartDay,
                departureEndDay,
                allTarget,
                departureDays
        );
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
                    // Keep scanning candidate fields until a numeric value is found.
                }
            }
        }
        return null;
    }

    private String text(JsonNode node, String field) {
        return node.path(field).asText("").trim();
    }

    private boolean isCreationFormPayload(JsonNode node) {
        return !text(node, "saleProductCode").isBlank()
                && (!text(node, "departureStartDay").isBlank() || !text(node, "departureEndDay").isBlank());
    }

    private List<String> creationDepartureDays(JsonNode node) {
        List<String> days = new ArrayList<>();
        addDepartureDay(days, node, "mon");
        addDepartureDay(days, node, "tue");
        addDepartureDay(days, node, "wed");
        addDepartureDay(days, node, "thu");
        addDepartureDay(days, node, "fri");
        addDepartureDay(days, node, "sat");
        addDepartureDay(days, node, "sun");
        return List.copyOf(days);
    }

    private void addDepartureDay(List<String> days, JsonNode node, String field) {
        if (parseYn(node.path(field).asText("")) == Boolean.TRUE) {
            days.add(field);
        }
    }

    private Boolean parseYn(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return "Y".equalsIgnoreCase(value.trim());
    }

    public Map<String, Object> standaloneCreationArguments(List<RoutingPlan> routingPlans) {
        if (routingPlans == null) {
            return Map.of();
        }
        return routingPlans.stream()
                .filter(plan -> "product".equalsIgnoreCase(plan.agentKey()))
                .map(RoutingPlan::arguments)
                .filter(map -> map != null && !map.isEmpty())
                .findFirst()
                .map(Map::copyOf)
                .orElse(Map.of());
    }

    private String firstString(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private Boolean firstBoolean(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value instanceof Boolean bool) {
                return bool;
            }
            if (value != null) {
                String text = String.valueOf(value).trim();
                if ("Y".equalsIgnoreCase(text) || "true".equalsIgnoreCase(text)) {
                    return Boolean.TRUE;
                }
                if ("N".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
                    return Boolean.FALSE;
                }
            }
        }
        return null;
    }

    private List<String> extractDepartureDays(Map<String, Object> source) {
        Object rawDays = source.get("departureDays");
        if (rawDays instanceof List<?> list) {
            List<String> days = new ArrayList<>();
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                String text = String.valueOf(item).trim();
                if (!text.isBlank()) {
                    days.add(text);
                }
            }
            return List.copyOf(days);
        }
        List<String> days = new ArrayList<>();
        addFlagDay(days, source, "mon");
        addFlagDay(days, source, "tue");
        addFlagDay(days, source, "wed");
        addFlagDay(days, source, "thu");
        addFlagDay(days, source, "fri");
        addFlagDay(days, source, "sat");
        addFlagDay(days, source, "sun");
        return List.copyOf(days);
    }

    private void addFlagDay(List<String> days, Map<String, Object> source, String key) {
        Object raw = source.get(key);
        if (raw == null) {
            return;
        }
        String text = String.valueOf(raw).trim();
        if ("Y".equalsIgnoreCase(text) || "true".equalsIgnoreCase(text)) {
            days.add(key);
        }
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

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Long nonNegativeLong(Long value) {
        return value == null || value < 0 ? null : value;
    }

    private Integer positiveInt(int value) {
        return value > 0 ? value : null;
    }
}
