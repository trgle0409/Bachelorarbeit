package thesis.rules;

import thesis.parser.ParseMode;

public record RuleCandidate(
        Long programId,
        ParseMode parseMode,
        RuleKind ruleKind,     // "IF" | "EVALUATE"
        int startLine,
        int endLine,
        String snippet
) {}