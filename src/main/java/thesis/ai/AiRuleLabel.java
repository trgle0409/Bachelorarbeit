package thesis.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AiRuleLabel(
        @JsonProperty("title")
        String title,

        @JsonProperty("rule_summary")
        String ruleSummary,

        @JsonProperty("pattern")
        String pattern,

        @JsonProperty("confidence")
        double confidence
) {}