package thesis.parser;

public record RuleSignals(boolean hasIf,
                          boolean hasEvaluate,
                          int ifCount,
                          int evaluateCount) {}