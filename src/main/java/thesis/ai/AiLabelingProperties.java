package thesis.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai.label")
public record AiLabelingProperties(
        int topN,
        int examplesPerKey,
        boolean repopulateAbstractText
) {}