package thesis.rules;

import org.springframework.stereotype.Component;
import thesis.parser.ParseMode;

import java.util.Locale;

@Component
public class RuleNormalizer {

    public CobolNormalizedRuleEntity normalizeEntity(CobolExtractedRule r) {

        RuleKind rk = ruleKindSafe(r.getRuleType());
        ParseMode pm = parseModeSafe(r.getParseMode());

        String conditionNorm = null; // dùng cho complexity + IF predicate
        String subjectNorm = null;   // EVALUATE subject
        String whenNorm = null;      // EVALUATE when (raw WHEN / OTHER / boolean expr)

        if (rk == RuleKind.IF) {
            conditionNorm = normPredicate(r.getConditionText());

        } else if (rk == RuleKind.EVALUATE) {

            boolean booleanMode = Boolean.TRUE.equals(r.getEvaluateBooleanMode());

            // Always preserve the EVALUATE subject, including EVALUATE TRUE.
            subjectNorm = normPredicate(r.getSubjectText());

            // Current extractor stores the effective branch predicate in whenText:
            // - EVALUATE TRUE      -> EIBAID = DFHCLEAR
            // - EVALUATE SQLCODE   -> SQLCODE = <N>
            // - WHEN OTHER         -> OTHER
            whenNorm = normPredicate(r.getWhenText());

            if ("OTHER".equals(whenNorm)) {
                conditionNorm = "OTHER";
            } else {
                conditionNorm = whenNorm;
            }
        }

        String thenT = normActionBlock(r.getThenText());
        String elseT = normActionBlock(r.getElseText());

        boolean hasElse = elseT != null && !elseT.isBlank();

        int thenCnt = countStatements(thenT);
        int elseCnt = countStatements(elseT);

        // complexity: IF dùng conditionNorm; EVALUATE dùng subject|WHEN|when
        String predForComplexity = (rk == RuleKind.IF)
                ? conditionNorm
                : joinEvalPredicate(subjectNorm, whenNorm);

        int complexity = 1;
        complexity += countToken(predForComplexity, " AND ");
        complexity += countToken(predForComplexity, " OR ");
        complexity += countToken(predForComplexity, " NOT ");
        complexity += thenCnt / 3;
        complexity += elseCnt / 3;

        return CobolNormalizedRuleEntity.builder()
                .programId(r.getProgramId())
                .parseMode(pm)
                .kind(rk)
                .startLine(r.getStartLine())
                .endLine(r.getEndLine())
                .conditionNorm(conditionNorm)
                .subjectNorm(subjectNorm)
                .whenNorm(whenNorm)
                .thenNorm(thenT)
                .elseNorm(elseT)
                .hasElse(hasElse)
                .thenStmtCount(thenCnt)
                .elseStmtCount(elseCnt)
                .complexityScore(complexity)
                .build();
    }

    // =========================
    // ParseMode / RuleKind safe
    // =========================
    private ParseMode parseModeSafe(Object mode) {
        if (mode == null) return ParseMode.FULL;
        if (mode instanceof ParseMode pm) return pm;
        try {
            return ParseMode.valueOf(mode.toString().trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return ParseMode.FULL;
        }
    }

    private RuleKind ruleKindSafe(Object kind) {
        if (kind == null) return RuleKind.IF;
        if (kind instanceof RuleKind rk) return rk;
        String k = kind.toString().trim().toUpperCase(Locale.ROOT);
        if (k.contains("EVALUATE")) return RuleKind.EVALUATE;
        return RuleKind.IF;
    }

    // =========================
    // Normalization helpers
    // =========================
    private String normPredicate(String s) {
        if (s == null) return null;

        // ✅ strip inline comment marker (COBOL comment)
        int cmt = s.indexOf("*>");
        if (cmt >= 0) s = s.substring(0, cmt);

        String u = s.trim().toUpperCase(Locale.ROOT);
        u = u.replaceAll("\\s+", " ");

        // operator normalization (cẩn thận: chỉ thay khi có spacing)
        // Thứ tự quan trọng — phải replace chuỗi dài trước
        u = u.replace(" IS EQUAL TO ", " = ");
        u = u.replace(" IS NOT EQUAL TO ", " <> ");
        u = u.replace(" IS GREATER THAN OR EQUAL TO ", " >= ");
        u = u.replace(" IS LESS THAN OR EQUAL TO ", " <= ");
        u = u.replace(" IS GREATER THAN ", " > ");
        u = u.replace(" IS LESS THAN ", " < ");
        // Sau đó mới replace ngắn hơn
        u = u.replace(" EQUAL TO ", " = ");
        u = u.replace(" EQUAL ", " = ");
        u = u.replace(" GREATER THAN ", " > ");
        u = u.replace(" LESS THAN ", " < ");

        u = generalizeLiterals(u);

        return u.isBlank() ? null : u;
    }

    private String joinEvalPredicate(String subjectNorm, String whenNorm) {
        String left = subjectNorm == null ? "" : subjectNorm;
        String right = whenNorm == null ? "" : whenNorm;
        String out = (left + " | WHEN | " + right).trim();
        return out.isBlank() ? null : out;
    }

    private String normActionBlock(String s) {
        if (s == null) return null;

        String u = s.trim().toUpperCase(Locale.ROOT);
        u = u.replaceAll("\\s+", " ").trim();

        // Preserve explicit no-op branches.
        // Example:
        // IF X
        //    CONTINUE
        // ELSE
        //    ...
        // END-IF
        // In this case CONTINUE is the whole branch and should remain visible.
        if (u.matches("(?i)^CONTINUE\\s*\\.?$")) {
            return "CONTINUE";
        }

        // Parser-safe EXEC stub -> semantic normalized action.
        // Must happen BEFORE generalizeLiterals().
        u = u.replaceAll("(?i)\\bDISPLAY\\s+\"EXEC_BLOCK_STUB\"\\s*\\.?", " EXEC_BLOCK_STUB ");
        u = u.replaceAll("(?i)\\bDISPLAY\\s+'EXEC_BLOCK_STUB'\\s*\\.?", " EXEC_BLOCK_STUB ");

        // Legacy forms.
        u = u.replaceAll("(?i)\\*>\\s*EXEC\\s+BLOCK\\s+STUB\\.?\\s*", " EXEC_BLOCK_STUB ");
        u = u.replaceAll("(?i)\\bEXEC\\s+BLOCK\\s+STUB\\b", " EXEC_BLOCK_STUB ");

        // Force spacing if a previous step produced glued tokens.
        u = u.replaceAll("(?i)EXEC_BLOCK_STUB(?=[A-Z0-9_(])", "EXEC_BLOCK_STUB ");
        u = u.replaceAll("(?i)(?<=[A-Z0-9_)])EXEC_BLOCK_STUB", " EXEC_BLOCK_STUB");

        // COPY stub is not a business action.
        u = u.replaceAll("(?m)^\\*>\\s*COPY STUB\\.?\\s*$", "");
        u = u.replaceAll("(?i)\\bDISPLAY\\s+\"COPY_STUB\"\\s*\\.?", "");
        u = u.replaceAll("(?i)\\bDISPLAY\\s+'COPY_STUB'\\s*\\.?", "");

        // Remove CONTINUE only when it appears together with other actions.
        // A pure CONTINUE branch has already returned above.
        u = u.replaceAll("(?i)\\bCONTINUE\\b\\.?\\s*", "");

        u = u.replaceAll("(?m)^\\s*$\\n?", "");
        u = u.replaceAll("\\s+", " ").trim();

        u = generalizeLiterals(u);

        // Safety net after literal generalization.
        u = u.replaceAll("(?i)EXEC_BLOCK_STUB(?=[A-Z0-9_(])", "EXEC_BLOCK_STUB ");
        u = u.replaceAll("(?i)(?<=[A-Z0-9_)])EXEC_BLOCK_STUB", " EXEC_BLOCK_STUB");
        u = u.replaceAll("\\s+", " ").trim();

        return u.isBlank() ? null : u;
    }

    private int countStatements(String s) {
        if (s == null || s.isBlank()) return 0;

        String padded = " " + s + " ";

        int c = 0;
        c += countToken(padded, " MOVE ");
        c += countToken(padded, " PERFORM ");
        c += countToken(padded, " ADD ");
        c += countToken(padded, " SUBTRACT ");
        c += countToken(padded, " MULTIPLY ");
        c += countToken(padded, " DIVIDE ");
        c += countToken(padded, " COMPUTE ");
        c += countToken(padded, " DISPLAY ");
        c += countToken(padded, " CALL ");
        c += countToken(padded, " EXEC_BLOCK_STUB ");
        return c;
    }

    private int countToken(String text, String needle) {
        if (text == null || needle == null) return 0;
        int count = 0, idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /**
     * Replace literals to improve dedup:
     *  - "ABC" / 'ABC' -> <S>
     *  - 123 / -0.5    -> <N>
     *  - ZEROS/SPACES  -> <ZERO>/<SPACE>
     *
     * Double-quoted literals are handled here because CobolPreprocessor no
     * longer converts "..." to '...' (the ANTLR grammar accepts both quote
     * styles natively via STRINGLITERAL, Cobol85.g4 line 5483).
     * Order matters: the double-quote pattern runs BEFORE the single-quote
     * pattern so that a literal like "O'BRIEN" is captured as a whole and
     * not partially matched by the single-quote rule.
     */
    private String generalizeLiterals(String s) {
        if (s == null) return null;

        String u = s;

        // 1a) Double-quoted string literals: "..." or "" (escaped inner quote)
        u = u.replaceAll("\"([^\"]|\"\")*\"", "<S>");

        // 1b) Single-quoted string literals: '...' or '' (escaped inner quote)
        u = u.replaceAll("'([^']|'')*'", "<S>");

        // 2) Numeric literals with boundaries
        // Numeric literals only; do not replace numeric suffixes in COBOL identifiers
        // such as INPUT-2, WS-FIELD-01 or FLAG-88.
        u = u.replaceAll(
                "(?<![A-Z0-9_-])([+-]?\\d+(?:\\.\\d+)?)(?![A-Z0-9_-])",
                "<N>"
        );

        // 3) Figurative constants.
        // Do not normalize tokens that are already placeholders such as <SPACE> or <ZERO>.
        u = u.replaceAll("(?<!<)\\b(?:ZEROS|ZEROES|ZERO)\\b(?!>)", "<ZERO>");
        u = u.replaceAll("(?<!<)\\b(?:SPACES|SPACE)\\b(?!>)", "<SPACE>");

        // Defensive cleanup for values produced by earlier pipeline runs.
        u = u.replaceAll("<+ZERO>+", "<ZERO>");
        u = u.replaceAll("<+SPACE>+", "<SPACE>");

        u = u.replaceAll("\\s+", " ").trim();
        return u;
    }
}