package thesis.rules;

import lombok.extern.slf4j.Slf4j;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import thesis.parser.ParseMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
public class CobolStructuredRuleVisitor extends de.ba.antlr.cobol85.Cobol85BaseVisitor<Void> {


    private record EvaluateBranchDraft(
            int startLine,
            int endLine,
            String subjectText,
            String conditionText,
            String whenText,
            int whenIndex,
            boolean whenOther,
            String thenText,
            boolean booleanMode,
            String rawSnippet
    ) {}

    private final Long programId;
    private final ParseMode parseMode;
    private final CommonTokenStream tokens;

    private final List<CobolExtractedRule> out = new ArrayList<>();

    public CobolStructuredRuleVisitor(Long programId, ParseMode parseMode, CommonTokenStream tokens) {
        this.programId = programId;
        this.parseMode = parseMode;
        this.tokens = tokens;
    }

    public List<CobolExtractedRule> rules() {
        return out;
    }

    // =========================
    // IF
    // =========================
    @Override

    public Void visitIfStatement(de.ba.antlr.cobol85.Cobol85Parser.IfStatementContext ctx) {
        CobolExtractedRule r = extractIf(ctx);
        if (r != null) out.add(r);
        return super.visitIfStatement(ctx);
    }

    // =========================
    // EVALUATE
    // =========================
    @Override
    public Void visitEvaluateStatement(de.ba.antlr.cobol85.Cobol85Parser.EvaluateStatementContext ctx) {
        out.addAll(extractEvaluateBranches(ctx));
        return super.visitEvaluateStatement(ctx);
    }

    // =========================
    // IF (your refined version)
    // =========================
    private CobolExtractedRule extractIf(ParserRuleContext ctx) {
        if (tokens == null || ctx.getStart() == null || ctx.getStop() == null) return null;

        int startIdx = ctx.getStart().getTokenIndex();
        int stopIdx  = ctx.getStop().getTokenIndex();

        List<Token> ts = tokens.getTokens(startIdx, stopIdx);
        if (ts == null || ts.isEmpty()) return null;

        int ifIdx = findKeyword(ts, "IF");
        if (ifIdx < 0) return null;

        Boundaries b = scanIfBoundaries(ts, ifIdx);

        String raw = tokens.getText(ctx.getSourceInterval());

        int condStart = ifIdx + 1;
        int condEnd;
        int firstActionIdx;

        if (b.thenIdx >= 0) {
            condEnd = b.thenIdx - 1;
            firstActionIdx = b.thenIdx + 1;
        } else {
            int hardStop = minPos(
                    b.elseIdx >= 0 ? b.elseIdx : Integer.MAX_VALUE,
                    b.endIfIdx >= 0 ? b.endIfIdx : Integer.MAX_VALUE,
                    b.dotIdx >= 0 ? b.dotIdx : Integer.MAX_VALUE,
                    ts.size()
            );
            int actionIdx = findFirstTopLevelAction(ts, condStart, hardStop - 1, ifIdx);
            if (actionIdx >= 0) {
                firstActionIdx = actionIdx;
                condEnd = actionIdx - 1;
            } else {
                firstActionIdx = hardStop;
                condEnd = hardStop - 1;
            }
        }

        String cond = (condStart <= condEnd) ? joinTokens(ts, condStart, condEnd) : null;

        String thenText = null;
        String elseText = null;

        int thenEnd = minPos(
                b.elseIdx >= 0 ? b.elseIdx : Integer.MAX_VALUE,
                b.endIfIdx >= 0 ? b.endIfIdx : Integer.MAX_VALUE,
                b.dotIdx >= 0 ? b.dotIdx : Integer.MAX_VALUE,
                ts.size()
        ) - 1;

        if (firstActionIdx >= 0 && firstActionIdx <= thenEnd) {
            thenText = joinTokens(ts, firstActionIdx, thenEnd);
        }

        if (b.elseIdx >= 0) {
            int elseStart = b.elseIdx + 1;
            int elseEnd = minPos(
                    b.endIfIdx >= 0 ? b.endIfIdx : Integer.MAX_VALUE,
                    b.dotIdx >= 0 ? b.dotIdx : Integer.MAX_VALUE,
                    ts.size()
            ) - 1;

            if (elseStart <= elseEnd) {
                elseText = joinTokens(ts, elseStart, elseEnd);
            }
        }

        // strip inline comments just in case
        cond = stripInlineComment(cond);
        thenText = stripInlineComment(thenText);
        elseText = stripInlineComment(elseText);

        return CobolExtractedRule.builder()
                .programId(programId)
                .parseMode(parseMode)
                .ruleType(RuleKind.IF)
                .startLine(ctx.getStart().getLine())
                .endLine(ctx.getStop().getLine())
                .conditionText(safeTrim(cond))
                .thenText(safeTrim(thenText))
                .elseText(safeTrim(elseText))
                .rawSnippet(safeTrim(raw))
                .build();
    }

    private static final class Boundaries {
        int thenIdx = -1;
        int elseIdx = -1;
        int endIfIdx = -1;
        int dotIdx = -1;
    }

    private Boundaries scanIfBoundaries(List<Token> ts, int ifIdx) {
        Boundaries b = new Boundaries();
        int level = 0;

        for (int i = ifIdx; i < ts.size(); i++) {
            String t = normKw(ts.get(i));

            if ("IF".equals(t)) { level++; continue; }

            if ("END-IF".equals(t)) {
                if (level == 1 && b.endIfIdx < 0) b.endIfIdx = i;
                level = Math.max(0, level - 1);
                continue;
            }

            if ("ELSE".equals(t) && level == 1 && b.elseIdx < 0) b.elseIdx = i;
            if ("THEN".equals(t) && level == 1 && b.thenIdx < 0) b.thenIdx = i;
            if (".".equals(t) && level == 1 && b.dotIdx < 0) b.dotIdx = i;
        }
        return b;
    }

    private int findFirstTopLevelAction(List<Token> ts, int from, int to, int ifIdx) {
        int f = Math.max(0, from);
        int t = Math.min(ts.size() - 1, to);

        int level = 0;
        for (int i = ifIdx; i <= t; i++) {
            String kw = normKw(ts.get(i));

            if (i >= f && level == 1) {
                if ("IF".equals(kw)) return i;
                if ("EVALUATE".equals(kw)) return i;
                if (isActionKeyword(kw)) return i;
            }

            if ("IF".equals(kw)) level++;
            else if ("END-IF".equals(kw)) level = Math.max(0, level - 1);
        }
        return -1;
    }

    private boolean isActionKeyword(String kw) {
        return switch (kw) {
            case "MOVE", "PERFORM", "COMPUTE", "CALL", "DISPLAY",
                 "GO", "GOBACK", "STOP", "EXIT", "CONTINUE",
                 "NEXT",                           // NEXT SENTENCE
                 "ADD", "SUBTRACT", "MULTIPLY", "DIVIDE",
                 "READ", "WRITE", "REWRITE", "DELETE",
                 "OPEN", "CLOSE", "START",
                 "SET", "INITIALIZE", "STRING", "UNSTRING",
                 "ACCEPT", "INSPECT",
                 "EXEC" -> true;
            default -> false;
        };
    }

    // =========================
    // EVALUATE (ALSO-aware, ctx-isolated) ✅ FIXED + "CONTINUE" fallback
    // =========================extractEvaluateBranches
    private List<CobolExtractedRule> extractEvaluateBranches(ParserRuleContext ctx) {
        if (tokens == null || ctx.getStart() == null || ctx.getStop() == null) return List.of();

        int startIdx = ctx.getStart().getTokenIndex();
        int stopIdx  = ctx.getStop().getTokenIndex();

        List<Token> ts = tokens.getTokens(startIdx, stopIdx);
        if (ts == null || ts.isEmpty()) return List.of();

        // 1) Find first EVALUATE inside interval
        int evalIdx = -1;
        for (int i = 0; i < ts.size(); i++) {
            if ("EVALUATE".equals(normKw(ts.get(i)))) {
                evalIdx = i;
                break;
            }
        }
        if (evalIdx < 0) return List.of();

        // 2) Find matching END-EVALUATE (nesting-aware)
        int endEvalIdx = -1;
        int level = 0;
        for (int i = evalIdx; i < ts.size(); i++) {
            String kw = normKw(ts.get(i));
            if ("EVALUATE".equals(kw)) level++;
            else if ("END-EVALUATE".equals(kw)) {
                level--;
                if (level == 0) {
                    endEvalIdx = i;
                    break;
                }
            }
        }
        if (endEvalIdx < 0) endEvalIdx = ts.size() - 1;

        // 3) Slice exactly from EVALUATE..END-EVALUATE
        List<Token> ev = ts.subList(evalIdx, endEvalIdx + 1);

        // 4) Collect top-level WHEN positions
        List<Integer> whenPositions = new ArrayList<>();
        level = 0;
        for (int i = 0; i < ev.size(); i++) {
            String kw = normKw(ev.get(i));
            if ("EVALUATE".equals(kw)) level++;
            else if ("END-EVALUATE".equals(kw)) level--;

            if (level == 1 && "WHEN".equals(kw)) whenPositions.add(i);
        }
        if (whenPositions.isEmpty()) return List.of();

        int firstWhen = whenPositions.get(0);

        // 5) Subject between EVALUATE and first WHEN
        String subjectRaw = (firstWhen > 1) ? joinTokens(ev, 1, firstWhen - 1) : null;
        List<String> subjectList = splitAlso(subjectRaw);

        boolean booleanMode =
                !subjectList.isEmpty() &&
                        subjectList.stream()
                                .allMatch(s -> "TRUE".equalsIgnoreCase(s.trim()));

        // Bug fix 2: per-branch line numbers are derived from token metadata below,
        // keep block-level lines only as fallback for tokens with line==0.
        int blockStartLine = ctx.getStart().getLine();
        int blockEndLine   = ctx.getStop().getLine();

        List<EvaluateBranchDraft> drafts = new ArrayList<>();

        // Precompute END-EVALUATE pos inside ev (for safety)
        int endEvPos = findKeyword(ev, "END-EVALUATE");

        for (int w = 0; w < whenPositions.size(); w++) {
            int whenIdx     = whenPositions.get(w);
            int nextWhenIdx = (w + 1 < whenPositions.size())
                    ? whenPositions.get(w + 1)
                    : ev.size(); // exclusive

            int branchStart = whenIdx;
            int branchEnd   = nextWhenIdx - 1; // inclusive

            if (endEvPos >= 0) branchEnd = Math.min(branchEnd, endEvPos - 1);
            if (branchEnd < branchStart) continue;

            // Bug fix 2: derive per-branch start/end line from the actual tokens,
            // not from the enclosing ctx (which always points to the whole EVALUATE).
            int branchStartLine = ev.get(branchStart).getLine();
            int branchEndLine   = ev.get(Math.min(branchEnd, ev.size() - 1)).getLine();
            if (branchStartLine <= 0) branchStartLine = blockStartLine;
            if (branchEndLine   <= 0) branchEndLine   = blockEndLine;

            int condStart = whenIdx + 1;
            if (condStart > branchEnd) {
                continue;
            }

            /*
             * EVALUATE/WHEN does not use the THEN token as branch separator.
             * A THEN inside the branch belongs to a nested IF and must remain
             * inside the action block.
             */
            int actionIdx = findFirstActionInRange(ev, condStart, branchEnd);

            int condEnd;
            int actionFrom;

            if (actionIdx < 0) {
                // No action in this WHEN branch; action may be inherited
                // from a following consecutive WHEN branch.
                condEnd = branchEnd;
                actionFrom = branchEnd + 1;
            } else if (actionIdx == condStart) {
                // E.g. malformed/empty condition; preserve fallback handling below.
                condEnd = condStart - 1;
                actionFrom = condStart;
            } else {
                condEnd = actionIdx - 1;
                actionFrom = actionIdx;
            }

            String whenRaw = (condStart <= condEnd) ? joinTokens(ev, condStart, condEnd) : null;

            // Fallback: ensure RuleNormalizer never receives a null condition_norm
            if (whenRaw == null || whenRaw.isBlank()) {
                whenRaw = "OTHER";
            }

            List<String> whenItems = splitAlso(whenRaw);

            boolean isOther =
                    whenItems.size() == 1 &&
                            "OTHER".equalsIgnoreCase(whenItems.get(0).trim());

            String conditionText;
            if (isOther)          conditionText = "OTHER";
            else if (booleanMode) conditionText = joinWithAnd(whenItems);
            else                  conditionText = buildEvaluatePredicate(subjectList, whenItems);

            String thenText = null;
            if (actionFrom <= branchEnd) {
                thenText = joinTokens(ev, actionFrom, branchEnd);
            }

            /*
             * Each WHEN branch remains one extracted rule.
             * For human-readable evidence and LLM annotation, however, an
             * EVALUATE branch must not lose its enclosing subject. Therefore
             * rawSnippet contains a focused EVALUATE context consisting of:
             *
             *     EVALUATE <subject>
             *         <current WHEN branch>
             *     END-EVALUATE
             *
             * Sibling WHEN branches are intentionally omitted because the
             * extracted rule represents exactly the current branch.
             */
            String rawBranch = joinTokens(ev, branchStart, branchEnd);

            thenText  = stripInlineComment(thenText);
            rawBranch = stripInlineComment(rawBranch);

            /*
             * Store only the raw WHEN branch here.
             * The EVALUATE wrapper is added later, after shared-action
             * branches have been combined.
             */
            drafts.add(new EvaluateBranchDraft(
                    branchStartLine,
                    branchEndLine,
                    safeTrim(subjectRaw),
                    safeTrim(conditionText),
                    safeTrim(conditionText),
                    w,
                    isOther,
                    safeTrim(thenText),
                    booleanMode,
                    safeTrim(rawBranch)
            ));        }

        List<CobolExtractedRule> outRules = new ArrayList<>();

        for (int i = 0; i < drafts.size(); i++) {
            EvaluateBranchDraft current = drafts.get(i);

            String effectiveThen = current.thenText();
            int actionSourceIndex = i;

            /*
             * Consecutive WHEN branches may share the action block
             * of the next WHEN branch.
             */
            if (effectiveThen == null || effectiveThen.isBlank()) {
                for (int j = i + 1; j < drafts.size(); j++) {
                    String nextThen = drafts.get(j).thenText();
                    if (nextThen != null && !nextThen.isBlank()) {
                        effectiveThen = nextThen;
                        actionSourceIndex = j;
                        break;
                    }
                }
            }

            StringBuilder effectiveRawBranches = new StringBuilder();

            for (int k = i; k <= actionSourceIndex; k++) {
                String branchSnippet = drafts.get(k).rawSnippet();
                if (branchSnippet == null || branchSnippet.isBlank()) {
                    continue;
                }

                if (!effectiveRawBranches.isEmpty()) {
                    effectiveRawBranches.append(System.lineSeparator());
                }

                effectiveRawBranches.append(branchSnippet);
            }

            String effectiveRawSnippet = buildEvaluateContextSnippet(
                    current.subjectText(),
                    effectiveRawBranches.toString()
            );

            int effectiveEndLine = drafts.get(actionSourceIndex).endLine();

            outRules.add(CobolExtractedRule.builder()
                    .programId(programId)
                    .parseMode(parseMode)
                    .ruleType(RuleKind.EVALUATE)
                    .startLine(current.startLine())
                    .endLine(effectiveEndLine)
                    .subjectText(current.subjectText())
                    .conditionText(current.conditionText())
                    .whenText(current.whenText())
                    .whenIndex(current.whenIndex())
                    .isWhenOther(current.whenOther())
                    .thenText(effectiveThen)
                    .evaluateBooleanMode(current.booleanMode())
                    .rawSnippet(safeTrim(effectiveRawSnippet))
                    .build());
        }

        return outRules;
    }

    /**
     * Finds first “action boundary” inside a WHEN branch when there is no THEN.
     * NOTE: comment tokens are usually not visible here, so this finds real statements.
     */
    private int findFirstActionInRange(List<Token> ts, int from, int to) {
        int f = Math.max(0, from);
        int t = Math.min(ts.size() - 1, to);

        for (int i = f; i <= t; i++) {
            String kw = normKw(ts.get(i));

            // treat nested control statements as action boundary
            if ("IF".equals(kw)) return i;
            if ("EVALUATE".equals(kw)) return i;

            if (isActionKeyword(kw)) return i;
        }
        return -1;
    }

    // =========================
    // Helpers
    // =========================
    private int findKeyword(List<Token> ts, String kw) {
        String u = kw.toUpperCase(Locale.ROOT);
        for (int i = 0; i < ts.size(); i++) {
            if (normKw(ts.get(i)).equals(u)) return i;
        }
        return -1;
    }

    private int findKeywordInRange(List<Token> ts, String kw, int from, int to) {
        String u = kw.toUpperCase(Locale.ROOT);
        int f = Math.max(0, from);
        int t = Math.min(ts.size() - 1, to);
        for (int i = f; i <= t; i++) {
            if (normKw(ts.get(i)).equals(u)) return i;
        }
        return -1;
    }

    private String normKw(Token tok) {
        if (tok == null) return "";
        String x = tok.getText();
        if (x == null) return "";
        x = x.trim();
        if (x.isEmpty()) return "";
        x = x.replaceAll("[,;]+$", "");
        if (".".equals(x)) return ".";
        return x.toUpperCase(Locale.ROOT);
    }

    private int minPos(int... xs) {
        int m = Integer.MAX_VALUE;
        for (int x : xs) m = Math.min(m, x);
        return m == Integer.MAX_VALUE ? -1 : m;
    }

    private String joinTokens(List<Token> ts, int from, int to) {
        if (from < 0) from = 0;
        if (to >= ts.size()) to = ts.size() - 1;
        if (from > to) return null;

        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            String x = ts.get(i).getText();
            if (x == null) continue;
            x = x.trim();
            if (x.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(x);
        }
        return sb.toString();
    }

    private String safeTrim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private String stripInlineComment(String s) {
        if (s == null) return null;

        String t = s.replaceAll("\\s+", " ").trim();

        if (t.equalsIgnoreCase("EXEC_BLOCK_STUB")
                || t.toUpperCase(Locale.ROOT).contains("EXEC BLOCK STUB")) {
            return "EXEC_BLOCK_STUB";
        }

        int idx = t.indexOf("*>");
        if (idx >= 0) {
            t = t.substring(0, idx);
        }

        t = t.replaceAll("\\s+", " ").trim();
        return t.isBlank() ? null : t;
    }

    private List<String> splitAlso(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String upper = raw.toUpperCase(Locale.ROOT);

        List<String> parts = new ArrayList<>();
        int idx = 0;
        while (true) {
            int alsoPos = upper.indexOf(" ALSO ", idx);
            if (alsoPos < 0) {
                parts.add(raw.substring(idx).trim());
                break;
            }
            parts.add(raw.substring(idx, alsoPos).trim());
            idx = alsoPos + 6;
        }

        List<String> out = new ArrayList<>();
        for (String p : parts) if (p != null && !p.isBlank()) out.add(p);
        return out;
    }

    private String joinWithAnd(List<String> items) {
        if (items == null || items.isEmpty()) return null;
        if (items.size() == 1) return items.get(0);
        return String.join(" AND ", items);
    }

    private String buildEvaluatePredicate(List<String> subjects, List<String> whens) {
        if (subjects == null || subjects.isEmpty()) return null;
        if (whens == null || whens.isEmpty()) return null;

        int count = Math.min(subjects.size(), whens.size());
        List<String> preds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            preds.add(subjects.get(i) + " = " + whens.get(i));
        }

        if (preds.size() == 1) return preds.get(0);
        return String.join(" AND ", preds);
    }

    /**
     * Build a focused source context for one extracted EVALUATE branch.
     *
     * Extraction semantics remain unchanged: one WHEN branch is one rule.
     * The additional wrapper only preserves the EVALUATE subject for
     * human-readable annotation and expert evaluation.
     *
     * Example:
     *
     *     EVALUATE WS-MOVE-IN
     *         WHEN OTHER MOVE "FAIL" TO WS-MOVE-OUTCOME
     *     END-EVALUATE
     */
    private String buildEvaluateContextSnippet(
            String subjectRaw,
            String rawBranch) {

        String subject = safeTrim(stripInlineComment(subjectRaw));
        String branch  = safeTrim(stripInlineComment(rawBranch));

        if (branch == null || branch.isBlank()) {
            return null;
        }

        if (subject == null || subject.isBlank()) {
            // Defensive fallback: do not fabricate a missing subject.
            return branch;
        }

        return "EVALUATE " + subject + "\n"
                + indentSnippet(branch, "    ") + "\n"
                + "END-EVALUATE";
    }

    /**
     * Indent all lines of a snippet while preserving possible nested lines.
     */
    private String indentSnippet(String snippet, String indentation) {
        if (snippet == null || snippet.isBlank()) {
            return snippet;
        }

        return indentation + snippet.replace("\n", "\n" + indentation);
    }
}