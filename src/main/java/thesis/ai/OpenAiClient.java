package thesis.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class OpenAiClient {

    private final OpenAiProperties props;
    private final ObjectMapper om;
    private final RestClient restClient;

    public OpenAiClient(OpenAiProperties props) {
        this.props = props;
        this.om = new ObjectMapper();

        if (props.apiKey() == null || props.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "OPENAI_API_KEY is not configured. Check .env import or environment variables."
            );
        }

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000); // 10 seconds
        factory.setReadTimeout(90_000);    // 90 seconds

        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(props.baseUrl() == null || props.baseUrl().isBlank()
                        ? "https://api.openai.com/v1"
                        : props.baseUrl())
                .defaultHeader("Authorization", "Bearer " + props.apiKey())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public String classifyRuleAsJson(String prompt) {
        int maxAttempts = 3;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return classifyRuleAsJsonOnce(prompt);

            } catch (OpenAiTransientException e) {
                if (attempt == maxAttempts) {
                    throw e;
                }

                long sleepMs = 2_000L * attempt;
                log.warn("Transient OpenAI error on attempt {}/{}. Retrying in {} ms: {}",
                        attempt, maxAttempts, sleepMs, e.getMessage());

                sleep(sleepMs);

            } catch (ResourceAccessException e) {
                // Includes read/connect timeout from RestClient request factory.
                if (attempt == maxAttempts) {
                    throw new OpenAiTransientException("OpenAI request timed out or connection failed", e);
                }

                long sleepMs = 2_000L * attempt;
                log.warn("OpenAI connection/timeout error on attempt {}/{}. Retrying in {} ms: {}",
                        attempt, maxAttempts, sleepMs, e.getMessage());

                sleep(sleepMs);
            }
        }

        throw new IllegalStateException("Unreachable OpenAI retry state");
    }

    private String classifyRuleAsJsonOnce(String prompt) {
        try {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("additionalProperties", false);
            schema.put("properties", Map.of(
                    "title", Map.of("type", "string"),
                    "rule_summary", Map.of("type", "string"),
                    "pattern", Map.of("type", "string"),
                    "confidence", Map.of("type", "number")
            ));
            schema.put("required", List.of("title", "rule_summary", "pattern", "confidence"));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", props.model());
            body.put("temperature", props.temperature());
            body.put("input", prompt);
            body.put("text", Map.of(
                    "format", Map.of(
                            "type", "json_schema",
                            "name", "cobol_rule_label",
                            "schema", schema,
                            "strict", true
                    )
            ));

            String raw = restClient
                    .post()
                    .uri("/responses")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            if (raw == null || raw.isBlank()) {
                throw new RuntimeException("OpenAI response body is empty");
            }

            JsonNode root = om.readTree(raw);

            JsonNode outText = root.get("output_text");
            if (outText != null && outText.isTextual() && !outText.asText().isBlank()) {
                return outText.asText();
            }

            JsonNode output = root.get("output");
            if (output != null && output.isArray()) {
                for (JsonNode outItem : output) {
                    JsonNode content = outItem.get("content");
                    if (content != null && content.isArray()) {
                        for (JsonNode c : content) {
                            JsonNode text = c.get("text");
                            if (text != null && text.isTextual() && !text.asText().isBlank()) {
                                return text.asText();
                            }
                        }
                    }
                }
            }

            throw new RuntimeException("Could not extract model text output from Responses API. Raw=" + raw);

        } catch (HttpClientErrorException.Unauthorized e) {
            throw new OpenAiUnauthorizedException("OpenAI API key is invalid or unauthorized", e);

        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();

            if (status == 429 || status >= 500) {
                throw new OpenAiTransientException(
                        "OpenAI transient HTTP error " + status + ": " + e.getResponseBodyAsString(),
                        e
                );
            }

            throw new RuntimeException(
                    "OpenAI non-retryable HTTP error " + status + ": " + e.getResponseBodyAsString(),
                    e
            );

        } catch (RestClientException e) {
            throw e;

        } catch (Exception e) {
            throw new RuntimeException("OpenAI call failed: " + e.getMessage(), e);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenAiTransientException("Interrupted while waiting to retry OpenAI request", e);
        }
    }
}