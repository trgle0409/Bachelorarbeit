package thesis.parser;

public record Validated(
        ParseAttempt attempt,
        String cleanedUsed,
        RuleSignals signals,
        String preprocessReason
) {}