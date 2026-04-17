package com.example.springsupervisorai.controller;

import com.example.springsupervisorai.a2a.dto.JsonRpcRequest;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SupervisorA2ARequestValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SupervisorA2ARequestValidator validator = new SupervisorA2ARequestValidator();

    @Test
    void validateSendParamsAcceptsStandardTextMessageWithA2uiCapabilities() {
        ObjectNode message = objectMapper.createObjectNode();
        message.set("parts", objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode()
                        .put("text", "상품 상세 보여줘")
                        .put("mediaType", "text/plain")));
        ObjectNode capabilities = objectMapper.createObjectNode();
        capabilities.set("supportedCatalogIds", objectMapper.createArrayNode()
                .add("https://a2ui.org/specification/v0_8/standard_catalog_definition.json"));
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.set("a2uiClientCapabilities", capabilities);
        message.set("metadata", metadata);
        JsonRpcRequest request = new JsonRpcRequest(
                "2.0",
                "1",
                "message/stream",
                objectMapper.createObjectNode()
                        .put("model", "openai")
                        .set("message", message)
        );

        SupervisorA2ARequestValidator.ValidationResult<SupervisorA2ARequestValidator.ResolvedSendParams> result =
                validator.validateSendParams(request, objectMapper);

        assertThat(result.isError()).isFalse();
        assertThat(result.params().messageText()).isEqualTo("상품 상세 보여줘");
    }

    @Test
    void validateSendParamsAcceptsA2uiUserActionDataPart() {
        ObjectNode action = objectMapper.createObjectNode()
                .put("name", "submit_reservation")
                .put("surfaceId", "package-product-t2")
                .put("sourceComponentId", "reservation_submit")
                .set("context", objectMapper.createObjectNode()
                        .put("productCode", "AAP331260523TG1")
                        .put("bookerName", "홍길동")
                        .put("contact", "010-1111-2222")
                        .put("headCount", "2")
                        .put("birthDate", "19900101"));
        ObjectNode data = objectMapper.createObjectNode();
        data.set("userAction", action);
        ObjectNode dataPart = objectMapper.createObjectNode();
        dataPart.set("data", data);
        dataPart.put("mediaType", "application/json+a2ui");
        ObjectNode message = objectMapper.createObjectNode();
        message.set("parts", objectMapper.createArrayNode().add(dataPart));
        JsonRpcRequest request = new JsonRpcRequest(
                "2.0",
                "2",
                "message/stream",
                objectMapper.createObjectNode()
                        .put("model", "openai")
                        .set("message", message)
        );

        SupervisorA2ARequestValidator.ValidationResult<SupervisorA2ARequestValidator.ResolvedSendParams> result =
                validator.validateSendParams(request, objectMapper);

        assertThat(result.isError()).isFalse();
        assertThat(result.params().messageText()).contains("예약생성해줘");
        assertThat(result.params().messageText()).contains("상품코드: AAP331260523TG1");
        assertThat(result.params().messageText()).contains("예약자: 홍길동");
    }

    @Test
    void validateSendParamsAcceptsProductCreationA2uiUserAction() {
        ObjectNode action = objectMapper.createObjectNode()
                .put("name", "submit_product_creation")
                .put("surfaceId", "package-product-create-t2")
                .put("sourceComponentId", "creation_submit")
                .set("context", objectMapper.createObjectNode()
                        .put("saleProductCode", "AAP331260523TG1")
                        .put("departureStartDay", "20261201")
                        .put("departureEndDay", "20261202")
                        .put("allTarget", true)
                        .set("departureDays", objectMapper.createArrayNode()
                                .add("mon")
                                .add("tue")
                                .add("wed")
                                .add("thu")
                                .add("fri")
                                .add("sat")
                                .add("sun")));
        ObjectNode data = objectMapper.createObjectNode();
        data.set("userAction", action);
        ObjectNode dataPart = objectMapper.createObjectNode();
        dataPart.set("data", data);
        dataPart.put("mediaType", "application/json+a2ui");
        ObjectNode message = objectMapper.createObjectNode();
        message.set("parts", objectMapper.createArrayNode().add(dataPart));
        JsonRpcRequest request = new JsonRpcRequest(
                "2.0",
                "3",
                "message/stream",
                objectMapper.createObjectNode()
                        .put("model", "openai")
                        .set("message", message)
        );

        SupervisorA2ARequestValidator.ValidationResult<SupervisorA2ARequestValidator.ResolvedSendParams> result =
                validator.validateSendParams(request, objectMapper);

        assertThat(result.isError()).isFalse();
        assertThat(result.params().messageText()).contains("상품생성해줘");
        assertThat(result.params().messageText()).contains("상품코드: AAP331260523TG1");
        assertThat(result.params().messageText()).contains("출발시작일: 20261201");
        assertThat(result.params().messageText()).contains("출발요일: mon, tue, wed, thu, fri, sat, sun");
    }
}
