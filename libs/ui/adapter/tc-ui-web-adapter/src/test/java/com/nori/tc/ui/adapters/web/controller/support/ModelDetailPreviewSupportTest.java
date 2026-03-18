package com.nori.tc.ui.adapters.web.controller.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ModelDetailPreviewSupport}의 workflow/data index preview 규칙을 검증합니다.
 */
class ModelDetailPreviewSupportTest {

    @Test
    @DisplayName("workflow_filter preview는 전체 and/or 식을 canonical 용어로 요약합니다")
    void summarizeWorkflowFilterBuildsCanonicalExpressionPreview() {
        final String workflowFilter = """
                {
                  "and": [
                    {
                      "from": "data",
                      "path": "status",
                      "comparison": "equals",
                      "expected": "ok",
                      "transforms": ["trim", "lower"]
                    },
                    {
                      "or": [
                        {
                          "from": "metadata",
                          "path": "eventType",
                          "comparison": "equals",
                          "expected": "READY"
                        },
                        {
                          "from": "data",
                          "path": "retryCount",
                          "comparison": "greater_than_or_equal",
                          "expected": 4
                        }
                      ]
                    }
                  ]
                }
                """;

        final String preview = ModelDetailPreviewSupport.summarizeWorkflowFilter(workflowFilter);

        assertEquals(
                "and(data.status {comparison=equals, expected=\"ok\", transforms=[trim, lower]}, "
                        + "or(metadata.eventType {comparison=equals, expected=\"READY\"}, "
                        + "data.retryCount {comparison=greater_than_or_equal, expected=4}))",
                preview
        );
    }

    @Test
    @DisplayName("action_data_index preview는 mdfTemplateName과 첫 번째 field spec을 canonical 용어로 요약합니다")
    void summarizeActionDataIndexBuildsCanonicalFieldPreview() {
        final String actionDataIndex = """
                {
                  "mdfTemplateName": "TOOL_CONDITION_REPLY_MES",
                  "fields": {
                    "EQPID": { "from": "data", "path": "eqpId", "transforms": ["trim", "upper"] },
                    "EVENT_TYPE": { "from": "metadata", "path": "eventType" }
                  }
                }
                """;

        final String preview = ModelDetailPreviewSupport.summarizeActionDataIndex(actionDataIndex);

        assertEquals(
                "mdfTemplateName=TOOL_CONDITION_REPLY_MES / EQPID {from=data, path=eqpId, transforms=[trim, upper]}",
                preview
        );
    }

    @Test
    @DisplayName("JSON 파싱이 불가능하면 첫 번째 텍스트 라인을 그대로 사용합니다")
    void summarizeFallsBackToFirstMeaningfulLine() {
        final String raw = """

                더블 클릭 없이 바로 보일 첫 줄
                두 번째 줄
                """;

        assertEquals("더블 클릭 없이 바로 보일 첫 줄", ModelDetailPreviewSupport.summarizeWorkflowFilter(raw));
        assertEquals("더블 클릭 없이 바로 보일 첫 줄", ModelDetailPreviewSupport.summarizeActionDataIndex(raw));
    }
}
