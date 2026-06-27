package thesis.parser;

import de.ba.antlr.cobol85.Cobol85Lexer;
import de.ba.antlr.cobol85.Cobol85Parser;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.tree.ParseTree;
import org.springframework.stereotype.Service;
import thesis.corpus.CobolProgram;
import thesis.corpus.CobolProgramRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@Slf4j
@Service
public class CobolParserService {

    private final CobolProgramRepository programRepo;
    private final CobolParseResultRepository resultRepo;
    private final CobolPreprocessor preprocessor;

    // DB safety (nếu bạn chưa đổi column sang TEXT)
    private static final int DB_MESSAGE_MAX = 2000;
    private static final int DB_TOKEN_MAX = 500;

    /**
     *
     * @param programRepo
     * @param resultRepo
     * @param preprocessor
     */

    public CobolParserService(CobolProgramRepository programRepo,
                              CobolParseResultRepository resultRepo,
                              CobolPreprocessor preprocessor) {
        this.programRepo = programRepo;
        this.resultRepo = resultRepo;
        this.preprocessor = preprocessor;
    }

    private Validated validateProgramWithModeAndSignals(CobolProgram p) {
        String raw;
        try {
            raw = Files.readString(Path.of(p.getPath()));
        } catch (IOException e) {
            ParseAttempt at = new ParseAttempt(
                    ParseStatus.IO_ERROR,
                    ParseMode.FULL,
                    e.getMessage(),
                    null, null, null, null, null,  null
            );
            return new Validated(at, null, new RuleSignals(false, false, 0, 0), null);
        }

        PreprocessResult prep = preprocessor.preprocess(raw);
        String cleaned = prep.content();
        String reason = prep.reason();

        if (cleaned == null || cleaned.isBlank()) {
            ParseAttempt at = new ParseAttempt(
                    ParseStatus.EMPTY_AFTER_CLEAN,
                    ParseMode.FULL,
                    "Empty after preprocessing",
                    null, null, null, snippetAround(cleaned, 1, 5),
                    null,
                    null
            );
            return new Validated(at, cleaned, new RuleSignals(false, false, 0, 0), reason);
        }

        // NEW: classify copybook/data-only fragments before parsing.
        // These files are not complete COBOL programs, so they should not be counted
        // as parser failures.
                if (preprocessor.looksLikeCopybookOrDataFragment(cleaned)) {
                    ParseAttempt at = new ParseAttempt(
                            ParseStatus.SKIPPED_COPYBOOK,
                            ParseMode.FULL,
                            "Skipped copybook/data-only fragment",
                            null, null, null, snippetAround(cleaned, 1, 5),
                            null,
                            null
                    );
                    return new Validated(at, cleaned, new RuleSignals(false, false, 0, 0), reason);
                }

        // NEW: classify COBOL function units before parsing.
        // The current pipeline extracts rules from programs, not standalone FUNCTION-ID units.
                if (preprocessor.looksLikeFunctionUnit(cleaned)) {
                    ParseAttempt at = new ParseAttempt(
                            ParseStatus.SKIPPED_FUNCTION_UNIT,
                            ParseMode.FULL,
                            "Skipped COBOL function unit",
                            null, null, null,
                            snippetAround(cleaned, 1, 5),
                            null,
                            null
                    );
                    return new Validated(at, cleaned, new RuleSignals(false, false, 0, 0), reason);
                }

        // FULL parse
        ParseAttempt full = tryParseToTree(cleaned, ParseMode.FULL, prep);
        if (full.status() == ParseStatus.VALID) {
            return new Validated(full, cleaned, detectRuleSignals(cleaned), reason);
        }

        // PROCEDURE_ONLY fallback
        String procOnly = preprocessor.tryExtractProcedureOnlyProgram(cleaned);
        if (procOnly != null && !procOnly.isBlank()) {
            ParseAttempt po = tryParseToTree(procOnly, ParseMode.PROCEDURE_ONLY, prep);
            if (po.status() == ParseStatus.VALID) {
                // IMPORTANT: keep po.tokens() so extractor can reconstruct snippet
                ParseAttempt ok = new ParseAttempt(
                        ParseStatus.VALID,
                        ParseMode.PROCEDURE_ONLY,
                        "OK (procedure-only fallback)",
                        null, null, null, snippetAround(procOnly, 1, 5),
                        po.tree(),
                        po.tokens()
                );
                return new Validated(ok, procOnly, detectRuleSignals(procOnly), reason);
            }
        }

        // return original failure (FULL) for diagnostics
        return new Validated(full, cleaned, detectRuleSignals(cleaned), reason);
    }

    private ParseAttempt tryParseToTree(String content, ParseMode mode, PreprocessResult prep) {

        CharStream input = CharStreams.fromString(content);
        Cobol85Lexer lexer = new Cobol85Lexer(input);

        CollectingErrorListener lexErr = new CollectingErrorListener();
        lexer.removeErrorListeners();
        lexer.addErrorListener(lexErr);

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();

        if (lexErr.hasErrors()) {
            var e = lexErr.first();
            return new ParseAttempt(
                    ParseStatus.LEXER_ERROR,
                    mode,
                    e.msg(),
                    e.line(),
                    e.charPos(),
                    e.offending(),
                    snippetAround(content, e.line(), 2),
                    null,
                    tokens
            );
        }

        Cobol85Parser parser = new Cobol85Parser(tokens);
        CollectingErrorListener parseErr = new CollectingErrorListener();
        parser.removeErrorListeners();
        parser.addErrorListener(parseErr);

        ParseTree tree = null;

        parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
        try {
            tree = parser.startRule();
        } catch (RuntimeException ex) {
            tokens.seek(0);
            parser.reset();
            parser.getInterpreter().setPredictionMode(PredictionMode.LL);
            try {
                tree = parser.startRule();
            } catch (RuntimeException ignore) {
            }
        }

        if (parser.getNumberOfSyntaxErrors() > 0 || parseErr.hasErrors() || tree == null) {
            var e = parseErr.first();
            if (e != null) {
                return new ParseAttempt(
                        ParseStatus.PARSER_ERROR,
                        mode,
                        e.msg(),
                        e.line(),
                        e.charPos(),
                        e.offending(),
                        snippetAround(content, e.line(), 2),
                        null,
                        tokens
                );
            }

            return new ParseAttempt(
                    ParseStatus.PARSER_ERROR,
                    mode,
                    "Syntax error(s)",
                    null,
                    null,
                    null,
                    snippetAround(content, 1, 5),
                    null,
                    tokens
            );
        }

        return new ParseAttempt(
                ParseStatus.VALID,
                mode,
                "OK",
                null,
                null,
                null,
                null,
                tree,
                tokens
        );
    }

    @Transactional
    public List<CobolProgram> filterValidProgramsAndPersistResults() {

        List<CobolProgram> valid = new ArrayList<>();
        Map<ParseStatus, Integer> stats = new EnumMap<>(ParseStatus.class);

        // ===== RULE DENSITY COUNTERS =====
        AtomicInteger totalIf = new AtomicInteger(0);
        AtomicInteger totalEval = new AtomicInteger(0);
        AtomicInteger filesWithIf = new AtomicInteger(0);
        AtomicInteger filesWithEval = new AtomicInteger(0);
        // =================================

        try (Stream<CobolProgram> stream = programRepo.streamAll()) {
            stream.forEach(p -> {
                try {
                    Validated vd = validateProgramWithModeAndSignals_PUBLIC(p);
                    ParseAttempt a = vd.attempt();

                    Validation v = new Validation(
                            a.status(),
                            a.message(),
                            a.line(),
                            a.charPos(),
                            a.token(),
            vd.preprocessReason(),
                            a.snippet()
                    );

                    CobolParseResult r = resultRepo.findByProgram_Id(p.getId())
                            .orElseGet(() -> CobolParseResult.builder().program(p).build());

                    // ensure parseMode never null
                    r.setParseMode(a.mode() != null ? a.mode() : ParseMode.FULL);

                    r.setStatus(v.status());
                    r.setMessage(trunc(v.message(), DB_MESSAGE_MAX));
                    r.setLineNo(v.line());
                    r.setCharPos(v.charPos());
                    r.setOffendingToken(trunc(v.token(), DB_TOKEN_MAX));
                    r.setSnippet(trunc(v.snippet(), 8000));

                    resultRepo.save(r);

                    stats.merge(v.status(), 1, Integer::sum);

                    if (v.status() == ParseStatus.VALID) {
                        valid.add(p);

                        // ===== RULE DENSITY UPDATE =====
                        RuleSignals s = vd.signals();

                        totalIf.addAndGet(s.ifCount());
                        totalEval.addAndGet(s.evaluateCount());

                        if (s.ifCount() > 0) filesWithIf.incrementAndGet();
                        if (s.evaluateCount() > 0) filesWithEval.incrementAndGet();
                        // =================================
                    }

                } catch (Exception ex) {
                    log.error("Persist/parse failed for programId={} path={}",
                            p.getId(), p.getPath(), ex);
                    stats.merge(ParseStatus.PARSER_ERROR, 1, Integer::sum);
                }
            });
        }

        log.info("Parse stats: {}", stats);
        log.info("Total valid: {}", valid.size());

        // ===== RULE DENSITY SUMMARY =====
        log.info("===== RULE DENSITY PRE-CHECK =====");
        log.info("VALID programs: {}", valid.size());
        log.info("Total IF count: {}", totalIf.get());
        log.info("Total EVALUATE count: {}", totalEval.get());
        log.info("Files containing IF: {}", filesWithIf.get());
        log.info("Files containing EVALUATE: {}", filesWithEval.get());

        if (!valid.isEmpty()) {
            log.info("Average IF per VALID file: {}",
                    (double) totalIf.get() / valid.size());
            log.info("Average EVALUATE per VALID file: {}",
                    (double) totalEval.get() / valid.size());
        }

        log.info("===================================");

        return valid;
    }

    private static String trunc(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 3)) + "...";
    }

    private static String snippetAround(String content, Integer oneBasedLine, int radius) {
        if (content == null || oneBasedLine == null || oneBasedLine <= 0) return null;

        String[] lines = content.split("\n", -1);
        int idx = oneBasedLine - 1;
        int from = Math.max(0, idx - radius);
        int to = Math.min(lines.length - 1, idx + radius);

        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            sb.append(String.format("%5d | %s%n", (i + 1), lines[i]));
        }
        return sb.toString();
    }

    private RuleSignals detectRuleSignals(String cleaned) {
        if (cleaned == null) return new RuleSignals(false, false, 0, 0);

        String u = cleaned.toUpperCase(Locale.ROOT);

        // cực đơn giản, đủ cho “pre-check”
        int ifCount = countOccurrences(u, " IF ");
        int evalCount = countOccurrences(u, "EVALUATE ");

        return new RuleSignals(ifCount > 0, evalCount > 0, ifCount, evalCount);
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    public Validated validateProgramWithModeAndSignals_PUBLIC(CobolProgram p) {
        return validateProgramWithModeAndSignals(p);
    }

}
