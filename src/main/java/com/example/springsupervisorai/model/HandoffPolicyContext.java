package com.example.springsupervisorai.model;

import java.util.List;

/**
 * Input model for handoff policy evaluation.
 *
 * @param planningContext current supervisor planning context
 * @param batchResults downstream results from the latest invoke batch
 */
public record HandoffPolicyContext(
        SupervisorPlanningContext planningContext,
        List<DownstreamCallResult> batchResults
) {

    /**
     * Creates a null-safe handoff policy context.
     */
    public static HandoffPolicyContext of(
            SupervisorPlanningContext planningContext,
            List<DownstreamCallResult> batchResults
    ) {
        return new HandoffPolicyContext(
                planningContext,
                batchResults == null ? List.of() : List.copyOf(batchResults)
        );
    }
}
