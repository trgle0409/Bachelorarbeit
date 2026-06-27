package thesis.parser;

public record PreprocessResult(String content,
                               boolean looksNonCobol,
                               String reason) {}
