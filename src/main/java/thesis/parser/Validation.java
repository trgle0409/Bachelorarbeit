package thesis.parser;

public record Validation(ParseStatus status,
                         String message,
                         Integer line,
                         Integer charPos,
                         String token,
                         String preprocessReason,
                         String snippet) {}