package thesis.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;


@ConfigurationProperties(prefix = "openai")
public record OpenAiProperties(
        String apiKey,
        String baseUrl,
        String model,
        String promptVersion,
        double temperature
) {}