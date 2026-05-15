package com.example.springsupervisorai.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HitlStateMachineTest {

    @ParameterizedTest
    @MethodSource("validTransitions")
    void transitionShouldSucceed(HitlReviewStatus current, HitlDecisionType decision, HitlReviewStatus expected) {
        assertThat(HitlStateMachine.transition(current, decision)).isEqualTo(expected);
    }

    static Stream<Arguments> validTransitions() {
        return Stream.of(
                Arguments.of(HitlReviewStatus.WAITING, HitlDecisionType.APPROVE, HitlReviewStatus.APPROVED),
                Arguments.of(HitlReviewStatus.WAITING, HitlDecisionType.CANCEL, HitlReviewStatus.CANCELED),
                Arguments.of(HitlReviewStatus.WAITING, HitlDecisionType.REVISE, HitlReviewStatus.REVISED),
                Arguments.of(HitlReviewStatus.APPROVED, HitlDecisionType.APPROVE, HitlReviewStatus.APPROVED),
                Arguments.of(HitlReviewStatus.CANCELED, HitlDecisionType.CANCEL, HitlReviewStatus.CANCELED),
                Arguments.of(HitlReviewStatus.REVISED, HitlDecisionType.REVISE, HitlReviewStatus.REVISED)
        );
    }

    @ParameterizedTest
    @MethodSource("invalidTransitions")
    void transitionShouldRejectInvalid(HitlReviewStatus current, HitlDecisionType decision) {
        assertThatThrownBy(() -> HitlStateMachine.transition(current, decision))
                .isInstanceOf(IllegalStateException.class);
    }

    static Stream<Arguments> invalidTransitions() {
        return Stream.of(
                Arguments.of(HitlReviewStatus.APPROVED, HitlDecisionType.CANCEL),
                Arguments.of(HitlReviewStatus.APPROVED, HitlDecisionType.REVISE),
                Arguments.of(HitlReviewStatus.CANCELED, HitlDecisionType.APPROVE),
                Arguments.of(HitlReviewStatus.CANCELED, HitlDecisionType.REVISE),
                Arguments.of(HitlReviewStatus.REVISED, HitlDecisionType.APPROVE),
                Arguments.of(HitlReviewStatus.REVISED, HitlDecisionType.CANCEL)
        );
    }

    @Test
    void transitionShouldRejectNullCurrent() {
        assertThatThrownBy(() -> HitlStateMachine.transition(null, HitlDecisionType.APPROVE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("terminalStates")
    void isTerminalShouldReturnTrueForNonWaiting(HitlReviewStatus status, boolean expected) {
        assertThat(HitlStateMachine.isTerminal(status)).isEqualTo(expected);
    }

    static Stream<Arguments> terminalStates() {
        return Stream.of(
                Arguments.of(HitlReviewStatus.WAITING, false),
                Arguments.of(HitlReviewStatus.APPROVED, true),
                Arguments.of(HitlReviewStatus.CANCELED, true),
                Arguments.of(HitlReviewStatus.REVISED, true),
                Arguments.of(null, false)
        );
    }
}
