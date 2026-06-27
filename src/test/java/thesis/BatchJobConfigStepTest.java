package thesis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JpaPagingItemReader;
import org.springframework.transaction.PlatformTransactionManager;
import thesis.ai.AiLabelingProcessor;
import thesis.ai.AiLabelingProperties;
import thesis.ai.AiLabelingWriter;
import thesis.batch.BatchJobConfig;
import thesis.batch.BatchMetricRepository;
import thesis.corpus.CobolProgram;
import thesis.parser.CobolParseResult;
import thesis.parser.CobolParseResultRepository;
import thesis.parser.CobolParserService;
import thesis.parser.ParseAttempt;
import thesis.parser.ParseMode;
import thesis.parser.ParseStatus;
import thesis.parser.Validated;
import thesis.rules.CanonicalKey;
import thesis.rules.CobolAbstractCanonicalRuleRepository;
import thesis.rules.CobolCanonicalRuleRepository;
import thesis.rules.CobolExtractedRule;
import thesis.rules.CobolExtractedRuleRepository;
import thesis.rules.CobolNormalizedRuleEntity;
import thesis.rules.CobolNormalizedRuleRepository;
import thesis.rules.CobolRuleCandidateEntity;
import thesis.rules.CobolRuleCandidateRepository;
import thesis.rules.RuleKind;
import thesis.rules.RuleNormalizer;
import thesis.rules.SemanticAbstraction;

import thesis.ai.*;
import thesis.parser.*;
import thesis.rules.*;

import java.util.List;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for every {@code @Bean Step} declared in {@link BatchJobConfig}:
 * parseStep, candidateStep, structuredStep, normalizeStep,
 * canonicalizeStep, abstractCanonicalizeStep and aiAnnotateAbstractCanonicalStep.
 *
 * <p>A Spring Batch {@code Step} factory method is a thin wrapper around a
 * {@link org.springframework.batch.core.step.builder.StepBuilder}. The behaviour
 * worth unit-testing is therefore split in two:
 * <ul>
 *     <li><b>Step wiring</b> &ndash; the step is created with the expected name,
 *     and (for tasklet / listener based steps) the inline tasklet and listeners
 *     perform the expected side effects (truncate, rebuild, return status).</li>
 *     <li><b>Chunk logic</b> &ndash; the real {@link ItemProcessor} and
 *     {@link ItemWriter} lambdas that each step is composed of carry the actual
 *     transformation / persistence logic and are exercised directly.</li>
 * </ul>
 *
 * The tests instantiate {@link BatchJobConfig} directly and call its factory
 * methods with mocked collaborators &mdash; no Spring context, no database.
 */
class BatchJobConfigStepTest {

    private BatchJobConfig config;

    private JobRepository jobRepository;
    private PlatformTransactionManager tx;
    private BatchMetricRepository metricRepo;

    @BeforeEach
    void setUp() {
        config = new BatchJobConfig();
        jobRepository = mock(JobRepository.class);
        tx = mock(PlatformTransactionManager.class);
        metricRepo = mock(BatchMetricRepository.class);
    }

    // Reflection helper: pull the configured tasklet out of a TaskletStep so we
    // can invoke it directly without launching the whole Spring Batch runtime.
    // The tasklet is held in a private 'tasklet' field on TaskletStep; we walk
    // the class hierarchy to stay robust against minor refactors.
    private static Tasklet extractTasklet(Step step) throws Exception {
        assertThat(step).isInstanceOf(TaskletStep.class);
        Class<?> c = step.getClass();
        while (c != null) {
            try {
                var f = c.getDeclaredField("tasklet");
                f.setAccessible(true);
                return (Tasklet) f.get(step);
            } catch (NoSuchFieldException ignore) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException("tasklet field not found on " + step.getClass());
    }

    private static CobolProgram program(long id) {
        return CobolProgram.builder()
                .id(id)
                .systemName("GENAPP")
                .path("/corpus/p" + id + ".cbl")
                .sourceType("GENAPP")
                .build();
    }
    // =====================================================================
    // STEP 0.5: parseStep  (+ parseProcessor / parseWriter)
    // =====================================================================
    @Nested
    @DisplayName("parseStep")
    class ParseStepTest {

        @SuppressWarnings("unchecked")
        private Step buildStep() {
            return config.parseStep(jobRepository, tx,
                    mock(JpaPagingItemReader.class),
                    mock(ItemProcessor.class),
                    mock(ItemWriter.class),
                    metricRepo);
        }

        @Test
        @DisplayName("is built with the expected step name")
        void hasExpectedName() {
            assertThat(buildStep().getName()).isEqualTo("parseStep");
        }

        @Test
        @DisplayName("processor maps a VALID parse attempt onto a new CobolParseResult (upsert miss)")
        void processorBuildsResultForNewProgram() throws Exception {
            CobolParserService parser = mock(CobolParserService.class);
            CobolParseResultRepository repo = mock(CobolParseResultRepository.class);

            CobolProgram p = program(7L);
            ParseAttempt attempt = new ParseAttempt(
                    ParseStatus.VALID, ParseMode.PROCEDURE_ONLY,
                    "OK", 12, 3, "TOKEN", "snippet-text", null, null);
            when(parser.validateProgramWithModeAndSignals_PUBLIC(p))
                    .thenReturn(new Validated(attempt, "cleaned", null, "reason"));
            when(repo.findByProgram_Id(7L)).thenReturn(Optional.empty());

            ItemProcessor<CobolProgram, CobolParseResult> processor =
                    config.parseProcessor(parser, repo);

            CobolParseResult r = processor.process(p);

            assertThat(r).isNotNull();
            assertThat(r.getProgram()).isSameAs(p);
            assertThat(r.getStatus()).isEqualTo(ParseStatus.VALID);
            assertThat(r.getParseMode()).isEqualTo(ParseMode.PROCEDURE_ONLY);
            assertThat(r.getMessage()).isEqualTo("OK");
            assertThat(r.getLineNo()).isEqualTo(12);
            assertThat(r.getCharPos()).isEqualTo(3);
            assertThat(r.getOffendingToken()).isEqualTo("TOKEN");
            assertThat(r.getSnippet()).isEqualTo("snippet-text");
        }

        @Test
        @DisplayName("processor reuses the existing result entity for an already-parsed program (upsert hit)")
        void processorReusesExistingResult() throws Exception {
            CobolParserService parser = mock(CobolParserService.class);
            CobolParseResultRepository repo = mock(CobolParseResultRepository.class);

            CobolProgram p = program(9L);
            CobolParseResult existing = CobolParseResult.builder().id(123L).program(p).build();

            ParseAttempt attempt = new ParseAttempt(
                    ParseStatus.PARSER_ERROR, ParseMode.FULL,
                    "boom", 1, 0, "X", "snip", null, null);
            when(parser.validateProgramWithModeAndSignals_PUBLIC(p))
                    .thenReturn(new Validated(attempt, null, null, null));
            when(repo.findByProgram_Id(9L)).thenReturn(Optional.of(existing));

            CobolParseResult r = config.parseProcessor(parser, repo).process(p);

            assertThat(r).isSameAs(existing);
            assertThat(r.getId()).isEqualTo(123L);
            assertThat(r.getStatus()).isEqualTo(ParseStatus.PARSER_ERROR);
        }

        @Test
        @DisplayName("processor defaults status/mode when the attempt is null")
        void processorDefaultsOnNullAttempt() throws Exception {
            CobolParserService parser = mock(CobolParserService.class);
            CobolParseResultRepository repo = mock(CobolParseResultRepository.class);

            CobolProgram p = program(1L);
            // Validated present but its attempt() is null.
            when(parser.validateProgramWithModeAndSignals_PUBLIC(p))
                    .thenReturn(new Validated(null, null, null, null));
            when(repo.findByProgram_Id(1L)).thenReturn(Optional.empty());

            CobolParseResult r = config.parseProcessor(parser, repo).process(p);

            assertThat(r.getStatus()).isEqualTo(ParseStatus.PARSER_ERROR);
            assertThat(r.getParseMode()).isEqualTo(ParseMode.FULL);
            assertThat(r.getMessage()).isEqualTo("ParseAttempt is null");
            assertThat(r.getLineNo()).isNull();
        }

        @Test
        @DisplayName("writer persists the whole chunk in a single saveAll call")
        void writerSavesChunk() throws Exception {
            CobolParseResultRepository repo = mock(CobolParseResultRepository.class);
            ItemWriter<CobolParseResult> writer = config.parseWriter(repo);

            Chunk<CobolParseResult> chunk = new Chunk<>(List.of(
                    CobolParseResult.builder().build(),
                    CobolParseResult.builder().build()));

            writer.write(chunk);

            verify(repo, times(1)).saveAll(chunk.getItems());
        }
    }

    // =====================================================================
    // STEP 1: candidateStep  (+ candidateProcessor / candidateWriter)
    // =====================================================================
    @Nested
    @DisplayName("candidateStep")
    class CandidateStepTest {

        @SuppressWarnings("unchecked")
        private Step buildStep() {
            return config.candidateStep(jobRepository, tx,
                    mock(JpaPagingItemReader.class),
                    mock(ItemProcessor.class),
                    mock(ItemWriter.class),
                    metricRepo);
        }

        @Test
        @DisplayName("is built with the expected step name")
        void hasExpectedName() {
            assertThat(buildStep().getName()).isEqualTo("candidateStep");
        }

        @Test
        @DisplayName("processor returns empty list when the program is not VALID")
        void processorReturnsEmptyForNonValid() throws Exception {
            CobolParserService parser = mock(CobolParserService.class);
            CobolProgram p = program(5L);

            ParseAttempt attempt = new ParseAttempt(
                    ParseStatus.PARSER_ERROR, ParseMode.FULL,
                    "err", null, null, null, null, null, null);
            when(parser.validateProgramWithModeAndSignals_PUBLIC(p))
                    .thenReturn(new Validated(attempt, null, null, null));

            List<CobolRuleCandidateEntity> out =
                    config.candidateProcessor(parser).process(p);

            assertThat(out).isEmpty();
        }

        @Test
        @DisplayName("processor returns empty list when tree/tokens are missing even if VALID")
        void processorReturnsEmptyWhenTreeMissing() throws Exception {
            CobolParserService parser = mock(CobolParserService.class);
            CobolProgram p = program(6L);

            // VALID but tree == null and tokens == null -> guarded, returns List.of()
            ParseAttempt attempt = new ParseAttempt(
                    ParseStatus.VALID, ParseMode.FULL,
                    "OK", null, null, null, null, null, null);
            when(parser.validateProgramWithModeAndSignals_PUBLIC(p))
                    .thenReturn(new Validated(attempt, null, null, null));

            assertThat(config.candidateProcessor(parser).process(p)).isEmpty();
        }

        @Test
        @DisplayName("writer skips null/empty rows and saves only non-empty candidate lists")
        void writerSkipsEmptyLists() throws Exception {
            CobolRuleCandidateRepository repo = mock(CobolRuleCandidateRepository.class);
            ItemWriter<List<CobolRuleCandidateEntity>> writer = config.candidateWriter(repo);

            List<CobolRuleCandidateEntity> rows = List.of(
                    CobolRuleCandidateEntity.builder()
                            .programId(1L).parseMode(ParseMode.FULL)
                            .ruleKind(RuleKind.IF).startLine(1).endLine(2)
                            .snippet("IF X ...").build());

            Chunk<List<CobolRuleCandidateEntity>> chunk = new Chunk<>(List.of(
                    List.<CobolRuleCandidateEntity>of(),  // empty -> skipped
                    rows));                               // non-empty -> saved

            writer.write(chunk);

            verify(repo, times(1)).saveAll(rows);
            verify(repo, times(1)).saveAll(any()); // only one save total
        }

        @Test
        @DisplayName("writer does nothing for an all-empty chunk")
        void writerNoSaveWhenAllEmpty() throws Exception {
            CobolRuleCandidateRepository repo = mock(CobolRuleCandidateRepository.class);
            Chunk<List<CobolRuleCandidateEntity>> chunk =
                    new Chunk<>(List.of(List.<CobolRuleCandidateEntity>of()));

            config.candidateWriter(repo).write(chunk);

            verify(repo, never()).saveAll(any());
        }
    }

    // =====================================================================
    // STEP 2: structuredStep  (+ structuredProcessor / structuredWriter)
    // =====================================================================
    @Nested
    @DisplayName("structuredStep")
    class StructuredStepTest {

        @SuppressWarnings("unchecked")
        private Step buildStep() {
            return config.structuredStep(jobRepository, tx,
                    mock(JpaPagingItemReader.class),
                    mock(ItemProcessor.class),
                    mock(ItemWriter.class),
                    metricRepo);
        }

        @Test
        @DisplayName("is built with the expected step name")
        void hasExpectedName() {
            assertThat(buildStep().getName()).isEqualTo("structuredStep");
        }

        @Test
        @DisplayName("processor returns empty list when program is not VALID")
        void processorReturnsEmptyForNonValid() throws Exception {
            CobolParserService parser = mock(CobolParserService.class);
            CobolProgram p = program(8L);

            ParseAttempt attempt = new ParseAttempt(
                    ParseStatus.LEXER_ERROR, ParseMode.FULL,
                    "lex", null, null, null, null, null, null);
            when(parser.validateProgramWithModeAndSignals_PUBLIC(p))
                    .thenReturn(new Validated(attempt, null, null, null));

            assertThat(config.structuredProcessor(parser).process(p)).isEmpty();
        }

        @Test
        @DisplayName("writer skips empty rule lists and saves non-empty ones")
        void writerSkipsEmptyRuleLists() throws Exception {
            CobolExtractedRuleRepository repo = mock(CobolExtractedRuleRepository.class);
            ItemWriter<List<CobolExtractedRule>> writer = config.structuredWriter(repo);

            List<CobolExtractedRule> rules = List.of(
                    CobolExtractedRule.builder()
                            .programId(1L).parseMode(ParseMode.FULL)
                            .ruleType(RuleKind.IF).startLine(1).endLine(5)
                            .conditionText("X = 1").build());

            Chunk<List<CobolExtractedRule>> chunk = new Chunk<>(List.of(
                    List.<CobolExtractedRule>of(),
                    rules));

            writer.write(chunk);

            verify(repo, times(1)).saveAll(rules);
            verify(repo, times(1)).saveAll(any());
        }
    }

    // =====================================================================
    // STEP 3: normalizeStep  (+ normalizeProcessor / normalizeWriter)
    // =====================================================================
    @Nested
    @DisplayName("normalizeStep")
    class NormalizeStepTest {

        @SuppressWarnings("unchecked")
        private Step buildStep() {
            return config.normalizeStep(jobRepository, tx,
                    mock(JpaPagingItemReader.class),
                    mock(ItemProcessor.class),
                    mock(ItemWriter.class),
                    metricRepo);
        }

        @Test
        @DisplayName("is built with the expected step name")
        void hasExpectedName() {
            assertThat(buildStep().getName()).isEqualTo("normalizeStep");
        }

        @Test
        @DisplayName("processor delegates each item to RuleNormalizer.normalizeEntity")
        void processorDelegatesToNormalizer() throws Exception {
            RuleNormalizer normalizer = mock(RuleNormalizer.class);
            CobolExtractedRule in = CobolExtractedRule.builder()
                    .programId(3L).parseMode(ParseMode.FULL)
                    .ruleType(RuleKind.IF).startLine(1).endLine(2)
                    .conditionText("A = 1").build();
            CobolNormalizedRuleEntity out = CobolNormalizedRuleEntity.builder().build();
            when(normalizer.normalizeEntity(in)).thenReturn(out);

            ItemProcessor<CobolExtractedRule, CobolNormalizedRuleEntity> processor =
                    config.normalizeProcessor(normalizer);

            assertThat(processor.process(in)).isSameAs(out);
            verify(normalizer, times(1)).normalizeEntity(in);
        }

        @Test
        @DisplayName("processor uses the real RuleNormalizer end-to-end for an IF rule")
        void processorWithRealNormalizer() throws Exception {
            RuleNormalizer normalizer = new RuleNormalizer();
            CobolExtractedRule in = CobolExtractedRule.builder()
                    .programId(3L).parseMode(ParseMode.FULL)
                    .ruleType(RuleKind.IF).startLine(10).endLine(20)
                    .conditionText("CUSTOMER-STATUS IS EQUAL TO 'A'")
                    .thenText("MOVE 'B' TO RESULT-CODE")
                    .build();

            CobolNormalizedRuleEntity out =
                    config.normalizeProcessor(normalizer).process(in);

            assertThat(out.getKind()).isEqualTo(RuleKind.IF);
            assertThat(out.getProgramId()).isEqualTo(3L);
            // " IS EQUAL TO " -> " = " and the literal 'A' -> <S>
            assertThat(out.getConditionNorm()).isEqualTo("CUSTOMER-STATUS = <S>");
            assertThat(out.getThenNorm()).isEqualTo("MOVE <S> TO RESULT-CODE");
            assertThat(out.getHasElse()).isFalse();
        }

        @Test
        @DisplayName("writer persists the chunk via saveAll")
        void writerSavesChunk() throws Exception {
            CobolNormalizedRuleRepository repo = mock(CobolNormalizedRuleRepository.class);
            Chunk<CobolNormalizedRuleEntity> chunk = new Chunk<>(List.of(
                    CobolNormalizedRuleEntity.builder().build()));

            config.normalizeWriter(repo).write(chunk);

            verify(repo, times(1)).saveAll(chunk.getItems());
        }
    }

    // =====================================================================
    // STEP 4: canonicalizeStep  (+ canonicalizeProcessor / canonicalizeWriter)
    // =====================================================================
    @Nested
    @DisplayName("canonicalizeStep")
    class CanonicalizeStepTest {

        @SuppressWarnings("unchecked")
        private Step buildStep(CobolCanonicalRuleRepository canonRepo) {
            return config.canonicalizeStep(jobRepository, tx,
                    mock(JpaPagingItemReader.class),
                    mock(ItemProcessor.class),
                    mock(ItemWriter.class),
                    canonRepo, metricRepo);
        }

        @Test
        @DisplayName("is built with the expected step name")
        void hasExpectedName() {
            assertThat(buildStep(mock(CobolCanonicalRuleRepository.class)).getName())
                    .isEqualTo("canonicalizeStep");
        }

        @Test
        @DisplayName("processor computes the canonical key for an IF rule from conditionNorm")
        void processorComputesIfKey() throws Exception {
            CobolNormalizedRuleEntity n = CobolNormalizedRuleEntity.builder()
                    .kind(RuleKind.IF)
                    .conditionNorm("VAR1 = <S>")
                    .thenNorm("MOVE <S> TO VAR2")
                    .elseNorm(null)
                    .hasElse(false)
                    .build();

            CobolNormalizedRuleEntity out =
                    config.canonicalizeProcessor().process(n);

            String expected = CanonicalKey.of(
                    RuleKind.IF, "VAR1 = <S>", "MOVE <S> TO VAR2", null, false);
            assertThat(out).isSameAs(n);
            assertThat(out.getCanonicalKey()).isEqualTo(expected);
        }

        @Test
        @DisplayName("processor builds an EVALUATE predicate as subject|WHEN|when")
        void processorComputesEvaluateKey() throws Exception {
            CobolNormalizedRuleEntity n = CobolNormalizedRuleEntity.builder()
                    .kind(RuleKind.EVALUATE)
                    .subjectNorm("SQLCODE")
                    .whenNorm("= <N>")
                    .thenNorm("PERFORM X")
                    .elseNorm("PERFORM Y")
                    .hasElse(true)
                    .build();

            CobolNormalizedRuleEntity out =
                    config.canonicalizeProcessor().process(n);

            String expectedPred = "SQLCODE" + "|WHEN|" + "= <N>";
            String expected = CanonicalKey.of(
                    RuleKind.EVALUATE, expectedPred, "PERFORM X", "PERFORM Y", true);
            assertThat(out.getCanonicalKey()).isEqualTo(expected);
        }

        @Test
        @DisplayName("processor tolerates null subject/when for EVALUATE")
        void processorEvaluateNullParts() throws Exception {
            CobolNormalizedRuleEntity n = CobolNormalizedRuleEntity.builder()
                    .kind(RuleKind.EVALUATE)
                    .subjectNorm(null)
                    .whenNorm(null)
                    .thenNorm(null)
                    .elseNorm(null)
                    .hasElse(false)
                    .build();

            CobolNormalizedRuleEntity out =
                    config.canonicalizeProcessor().process(n);

            String expected = CanonicalKey.of(
                    RuleKind.EVALUATE, "|WHEN|", null, null, false);
            assertThat(out.getCanonicalKey()).isEqualTo(expected);
        }

        @Test
        @DisplayName("writer persists the chunk via saveAll")
        void writerSavesChunk() throws Exception {
            CobolNormalizedRuleRepository repo = mock(CobolNormalizedRuleRepository.class);
            Chunk<CobolNormalizedRuleEntity> chunk = new Chunk<>(List.of(
                    CobolNormalizedRuleEntity.builder().build()));

            config.canonicalizeWriter(repo).write(chunk);

            verify(repo, times(1)).saveAll(chunk.getItems());
        }

        @Test
        @DisplayName("the rebuild listener deletes then rebuilds the canonical table after the step")
        void rebuildListenerRebuildsCanonicalTable() throws Exception {
            CobolCanonicalRuleRepository canonRepo = mock(CobolCanonicalRuleRepository.class);
            Step step = buildStep(canonRepo);

            // The composite step-execution listener is registered on the step;
            // fire afterStep on the TaskletStep's listener and assert the rebuild.
            StepListenerInvoker.fireAfterStep(step);

            verify(canonRepo, times(1)).deleteAllInBatch();
            verify(canonRepo, times(1)).rebuildFromNormalized();
        }
    }

    // =====================================================================
    // STEP 5: abstractCanonicalizeStep  (+ abstractKeyProcessor / abstractKeyWriter)
    // =====================================================================
    @Nested
    @DisplayName("abstractCanonicalizeStep")
    class AbstractCanonicalizeStepTest {

        @SuppressWarnings("unchecked")
        private Step buildStep(CobolAbstractCanonicalRuleRepository absRepo) {
            return config.abstractCanonicalizeStep(jobRepository, tx,
                    mock(JpaPagingItemReader.class),
                    mock(ItemProcessor.class),
                    mock(ItemWriter.class),
                    absRepo,
                    mock(CobolNormalizedRuleRepository.class),
                    metricRepo);
        }

        @Test
        @DisplayName("is built with the expected step name")
        void hasExpectedName() {
            assertThat(buildStep(mock(CobolAbstractCanonicalRuleRepository.class)).getName())
                    .isEqualTo("abstractCanonicalizeStep");
        }

        @Test
        @DisplayName("processor populates abstract parts and key for an IF rule")
        void processorAbstractsIfRule() throws Exception {
            CobolNormalizedRuleEntity n = CobolNormalizedRuleEntity.builder()
                    .kind(RuleKind.IF)
                    .conditionNorm("CUSTOMER-STATUS = <S>")
                    .thenNorm("MOVE <S> TO RESULT-CODE")
                    .elseNorm(null)
                    .hasElse(false)
                    .build();

            CobolNormalizedRuleEntity out =
                    config.abstractKeyProcessor().process(n);

            SemanticAbstraction.AbstractRuleParts parts = SemanticAbstraction.abstractIf(
                    "CUSTOMER-STATUS = <S>", "MOVE <S> TO RESULT-CODE", null);

            assertThat(out).isSameAs(n);
            assertThat(out.getAbstractPredicate()).isEqualTo(parts.predicate());
            assertThat(out.getAbstractThen()).isEqualTo(parts.thenText());
            assertThat(out.getAbstractElse()).isEqualTo(parts.elseText());

            String expectedKey = CanonicalKey.of(
                    RuleKind.IF, parts.predicate(), parts.thenText(), parts.elseText(), false);
            assertThat(out.getAbstractCanonicalKey()).isEqualTo(expectedKey);
            // identifier should have been abstracted to a VARn token
            assertThat(out.getAbstractPredicate()).contains("VAR1");
        }

        @Test
        @DisplayName("processor uses abstractEvaluateRule for an EVALUATE rule")
        void processorAbstractsEvaluateRule() throws Exception {
            CobolNormalizedRuleEntity n = CobolNormalizedRuleEntity.builder()
                    .kind(RuleKind.EVALUATE)
                    .subjectNorm("SQLCODE")
                    .whenNorm("= <N>")
                    .thenNorm("PERFORM OK-ROUTINE")
                    .elseNorm("PERFORM ERR-ROUTINE")
                    .hasElse(true)
                    .build();

            CobolNormalizedRuleEntity out =
                    config.abstractKeyProcessor().process(n);

            SemanticAbstraction.AbstractRuleParts parts = SemanticAbstraction.abstractEvaluateRule(
                    "SQLCODE", "= <N>", "PERFORM OK-ROUTINE", "PERFORM ERR-ROUTINE");

            assertThat(out.getAbstractPredicate()).isEqualTo(parts.predicate());
            assertThat(out.getAbstractPredicate()).contains("WHEN");

            String expectedKey = CanonicalKey.of(
                    RuleKind.EVALUATE, parts.predicate(), parts.thenText(), parts.elseText(), true);
            assertThat(out.getAbstractCanonicalKey()).isEqualTo(expectedKey);
        }

        @Test
        @DisplayName("writer persists the chunk via saveAll")
        void writerSavesChunk() throws Exception {
            CobolNormalizedRuleRepository repo = mock(CobolNormalizedRuleRepository.class);
            Chunk<CobolNormalizedRuleEntity> chunk = new Chunk<>(List.of(
                    CobolNormalizedRuleEntity.builder().build()));

            config.abstractKeyWriter(repo).write(chunk);

            verify(repo, times(1)).saveAll(chunk.getItems());
        }

        @Test
        @DisplayName("the rebuild listener deletes then rebuilds the abstract table after the step")
        void rebuildListenerRebuildsAbstractTable() throws Exception {
            CobolAbstractCanonicalRuleRepository absRepo =
                    mock(CobolAbstractCanonicalRuleRepository.class);
            Step step = buildStep(absRepo);

            StepListenerInvoker.fireAfterStep(step);

            verify(absRepo, times(1)).deleteAllInBatch();
            verify(absRepo, times(1)).rebuildFromNormalized();
        }
    }

    // =====================================================================
    // STEP 6: aiAnnotateAbstractCanonicalStep
    // =====================================================================
    @Nested
    @DisplayName("aiAnnotateAbstractCanonicalStep")
    class AiAnnotateStepTest {

        @SuppressWarnings("unchecked")
        private Step buildStep(boolean repopulate) {
            AiLabelingProperties props = new AiLabelingProperties(50, 5, repopulate);
            return config.aiAnnotateAbstractCanonicalStep(jobRepository, tx,
                    mock(org.springframework.batch.infrastructure.item.database.JpaCursorItemReader.class),
                    mock(JpaPagingItemReader.class),
                    mock(AiLabelingProcessor.class),
                    mock(AiLabelingWriter.class),
                    props, metricRepo);
        }

        @Test
        @DisplayName("is built with the expected step name (cursor reader path)")
        void hasExpectedNameCursor() {
            assertThat(buildStep(false).getName())
                    .isEqualTo("aiAnnotateAbstractCanonicalStep");
        }

        @Test
        @DisplayName("is built with the expected step name (paging reader path)")
        void hasExpectedNamePaging() {
            assertThat(buildStep(true).getName())
                    .isEqualTo("aiAnnotateAbstractCanonicalStep");
        }
    }
}
