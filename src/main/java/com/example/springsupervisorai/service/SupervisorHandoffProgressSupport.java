package com.example.springsupervisorai.service;

import com.example.springsupervisorai.model.HandoffDirective;
import com.example.springsupervisorai.model.HandoffValidationResult;
import com.example.springsupervisorai.model.SupervisorGraphSnapshot;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * handoff progress metadata 조립을 담당한다.
 * <p>
 * 오케스트레이터가 raw graph state map을 직접 해석하지 않도록 snapshot 기반으로 메타데이터를 계산한다.
 */
@Service
public class SupervisorHandoffProgressSupport {

    /**
     * handoff 진행 메시지에 사용할 metadata를 구성한다.
     *
     * @param snapshot typed graph snapshot
     * @param handoffPlanCount handoff로 추가된 계획 수
     * @param totalPlanCount 전체 계획 수
     * @return 사용자 progress/event log에 공통 사용 가능한 metadata
     */
    public Map<String, Object> metadata(SupervisorGraphSnapshot snapshot, long handoffPlanCount, int totalPlanCount) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("handoffEnabled", snapshot != null && snapshot.handoffEnabled());
        metadata.put("handoffPlanCount", handoffPlanCount);
        metadata.put("totalPlanCount", totalPlanCount);

        HandoffValidationResult representative = representativeValidation(snapshot == null ? List.of() : snapshot.handoffValidations());
        HandoffDirective directive = representative == null ? null : representative.directive();
        metadata.put("fromAgent", directive == null ? "" : safe(directive.fromAgentKey()));
        metadata.put("toAgent", directive == null ? "" : safe(directive.nextAgentKey()));
        metadata.put("reason", firstNonBlank(
                directive == null ? "" : safe(directive.reason()),
                representative == null ? "" : safe(representative.reasonCode())
        ));
        metadata.put("hopCount", representative == null ? 0 : representative.hopCount());
        return Map.copyOf(metadata);
    }

    private HandoffValidationResult representativeValidation(List<HandoffValidationResult> validations) {
        if (validations == null || validations.isEmpty()) {
            return null;
        }
        HandoffValidationResult first = validations.get(0);
        for (HandoffValidationResult validation : validations) {
            if (validation != null && validation.accepted()) {
                return validation;
            }
        }
        return first;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
