package com.nori.tc.ui.core.deploy;

import com.nori.tc.ui.core.model.UiCommandEventType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * YAML 1개를 메모리에 적재한 immutable scenario 정의입니다.
 */
public record DeployScenarioDefinition(
        int version,
        Scenario scenario,
        AppliesTo appliesTo,
        List<Step> steps
) {

    public DeployScenarioDefinition {
        if (version <= 0) {
            throw new IllegalArgumentException("scenario version must be greater than 0");
        }
        scenario = Objects.requireNonNull(scenario, "scenario is null");
        appliesTo = Objects.requireNonNull(appliesTo, "appliesTo is null");
        steps = List.copyOf(Objects.requireNonNull(steps, "steps is null"));
        validateStepUniqueness(steps);
    }

    private static void validateStepUniqueness(final List<Step> steps) {
        final Map<Integer, String> orderToCode = new LinkedHashMap<>();
        final Map<String, Integer> codeToOrder = new LinkedHashMap<>();
        for (Step step : steps) {
            if (orderToCode.putIfAbsent(step.order(), step.code()) != null) {
                throw new IllegalArgumentException("duplicated step order: " + step.order());
            }
            if (codeToOrder.putIfAbsent(step.code(), step.order()) != null) {
                throw new IllegalArgumentException("duplicated step code: " + step.code());
            }
        }
    }

    public record Scenario(
            String code,
            String name,
            String summary
    ) {
        public Scenario {
            requireText("scenario.code", code);
            requireText("scenario.name", name);
            requireText("scenario.summary", summary);
        }
    }

    public record AppliesTo(
            Set<UiCommandEventType> eventTypes,
            DeployScenarioCondition condition
    ) {
        public AppliesTo {
            if (eventTypes == null || eventTypes.isEmpty()) {
                throw new IllegalArgumentException("appliesTo.eventTypes must not be empty");
            }
            eventTypes = Set.copyOf(eventTypes);
            condition = condition == null ? DeployScenarioCondition.none() : condition;
        }

        public boolean matches(final UiCommandEventType eventType, final Set<DeployDeltaKind> deltaKinds) {
            return eventTypes.contains(eventType) && condition.matches(deltaKinds);
        }
    }

    public record Step(
            int order,
            String code,
            String name,
            DeployTargetApp targetApp,
            String description,
            DeployScenarioCondition when,
            Run run,
            Rollback rollback
    ) {
        public Step {
            if (order <= 0) {
                throw new IllegalArgumentException("step.order must be greater than 0");
            }
            requireText("step.code", code);
            requireText("step.name", name);
            targetApp = Objects.requireNonNull(targetApp, "step.targetApp is null");
            requireText("step.description", description);
            when = when == null ? DeployScenarioCondition.none() : when;
            run = Objects.requireNonNull(run, "step.run is null");
            rollback = rollback == null ? Rollback.disabled() : rollback;
        }
    }

    public record Run(
            DeployScenarioAction action,
            long timeoutMs,
            boolean replyRequired,
            DeployScenarioRetryPolicy retry
    ) {
        public Run {
            action = Objects.requireNonNull(action, "run.action is null");
            if (timeoutMs <= 0) {
                throw new IllegalArgumentException("run.timeoutMs must be greater than 0");
            }
            retry = retry == null ? DeployScenarioRetryPolicy.noRetry() : retry;
        }
    }

    public record Rollback(
            boolean enabled,
            DeployRollbackMode mode,
            DeployScenarioAction action
    ) {
        public Rollback {
            if (enabled) {
                mode = Objects.requireNonNull(mode, "rollback.mode is null");
                action = Objects.requireNonNull(action, "rollback.action is null");
            }
        }

        public static Rollback disabled() {
            return new Rollback(false, null, null);
        }
    }

    private static void requireText(final String fieldName, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
