package thesis.batch;

import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.CompositeStepExecutionListener;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.RunIdIncrementer;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JpaCursorItemReader;
import org.springframework.batch.infrastructure.item.database.JpaPagingItemReader;
import org.springframework.batch.infrastructure.item.database.builder.JpaCursorItemReaderBuilder;
import org.springframework.batch.infrastructure.item.database.builder.JpaPagingItemReaderBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import thesis.ai.*;
import thesis.corpus.CobolCorpusScanner;
import thesis.corpus.CobolProgram;
import thesis.parser.*;
import thesis.rules.*;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Configuration
public class BatchJobConfig {

    private static final boolean FULL_REIMPORT = false;         // true = delete + full import promgrams aus X-COBOL & GENAPP (danger)
    private static final boolean INCREMENTAL_IMPORT = false;    // true = only add missing file
    // =========================
    // JOB
    // =========================
    @Bean
    public Job cobolRulePipelineJob(JobRepository jobRepository,
                                    Step cleanupStep,
                                    Step parseStep,
                                    Step candidateStep,
                                    Step structuredStep,
                                    Step normalizeStep,
                                    Step canonicalizeStep,
                                    Step abstractCanonicalizeStep,
                                    Step aiAnnotateAbstractCanonicalStep,
                                    BatchMetricRepository metricRepo) {

        return new JobBuilder("cobolRulePipelineJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(new JobExecutionListener() {
                    @Override public void beforeJob(JobExecution jobExecution) {}

                    @Override public void afterJob(JobExecution jobExecution) {
                        var start = jobExecution.getStartTime();
                        var end = jobExecution.getEndTime();

                        Long duration = null;
                        if (start != null && end != null) {
                            duration = Duration.between(start, end).toMillis();
                        }

                        BatchMetric metric = BatchMetric.builder()
                                .jobName(jobExecution.getJobInstance().getJobName())
                                .jobInstanceId(jobExecution.getJobInstance().getId())
                                .jobExecutionId(jobExecution.getId())
                                .stepName(null)
                                .stepExecutionId(null)
                                .batchStatus(jobExecution.getStatus().name())
                                .exitCode(jobExecution.getExitStatus().getExitCode())
                                .startTime(start != null ? start.atZone(ZoneId.systemDefault()).toInstant() : null)
                                .endTime(end != null ? end.atZone(ZoneId.systemDefault()).toInstant() : null)
                                .durationMs(duration)
                                .createdAt(Instant.now())
                                .build();

                        metricRepo.save(metric);
                    }
                })
                .start(cleanupStep)
                .next(parseStep)
                .next(candidateStep)
                .next(structuredStep)
                .next(normalizeStep)
                .next(canonicalizeStep)
                .next(abstractCanonicalizeStep)
                .next(aiAnnotateAbstractCanonicalStep)
                .build();
    }

    // =========================
    // STEP 0: cleanup outputs (idempotent)
    // =========================
    @Bean
    public Step cleanupStep(JobRepository jobRepository,
                            PlatformTransactionManager tx,
                            CobolParseResultRepository parseResultRepo,
                            CobolCorpusScanner corpusScanner,
                            BatchMetricRepository metricRepo,
                            JdbcTemplate jdbc) {

        Tasklet tasklet = (contribution, chunkContext) -> {

            jdbc.execute("""
            TRUNCATE TABLE
                cobol_ai_rule_labels,
                cobol_abstract_canonical_rules,
                cobol_canonical_rules,
                cobol_normalized_rules,
                cobol_extracted_rules,
            RESTART IDENTITY CASCADE
        """);

            log.info("Pipeline tables truncated and identities reset.");

            if (FULL_REIMPORT) {
                parseResultRepo.deleteAllInBatch();
                corpusScanner.scanAndPersistFull();
                log.warn("Full corpus rebuild completed.");
            } else if (INCREMENTAL_IMPORT) {
                corpusScanner.scanAndPersistIncremental();
                log.info("Incremental corpus import completed.");
            }

            return RepeatStatus.FINISHED;
        };

        return new StepBuilder("cleanupStep", jobRepository)
                .tasklet(tasklet)
                .transactionManager(tx)
                .listener(stepMetricListener(metricRepo))
                .build();
    }

    // =========================
    // STEP 0.5: parse (program -> parse_result)
    // =========================
    @Bean
    public Step parseStep(JobRepository jobRepository,
                          PlatformTransactionManager tx,
                          JpaPagingItemReader<CobolProgram> programReader,
                          ItemProcessor<CobolProgram, CobolParseResult> parseProcessor,
                          ItemWriter<CobolParseResult> parseWriter,
                          BatchMetricRepository metricRepo) {

        return new StepBuilder("parseStep", jobRepository)
                .<CobolProgram, CobolParseResult>chunk(50)
                .transactionManager(tx)
                .reader(programReader)
                .processor(parseProcessor)
                .writer(parseWriter)
                .listener(stepMetricListener(metricRepo))
                .faultTolerant()
                .skip(Exception.class)
                .skipLimit(100_000)
                .build();
    }

    @Bean
    public ItemProcessor<CobolProgram, CobolParseResult> parseProcessor(
            CobolParserService parserService,
            CobolParseResultRepository parseResultRepo
    ) {
        return p -> {
            Validated vd = parserService.validateProgramWithModeAndSignals_PUBLIC(p);
            ParseAttempt a = vd != null ? vd.attempt() : null;

            ParseStatus status = (a != null && a.status() != null) ? a.status() : ParseStatus.PARSER_ERROR;
            ParseMode mode = (a != null && a.mode() != null) ? a.mode() : ParseMode.FULL;

            // UPSERT by program_id to keep one-to-one unique constraint stable
            CobolParseResult r = parseResultRepo.findByProgram_Id(p.getId())
                    .orElseGet(() -> CobolParseResult.builder().program(p).build());

            r.setProgram(p);
            r.setStatus(status);
            r.setParseMode(mode);
            r.setMessage(a != null ? a.message() : "ParseAttempt is null");
            r.setLineNo(a != null ? a.line() : null);
            r.setCharPos(a != null ? a.charPos() : null);
            r.setOffendingToken(a != null ? a.token() : null);
            r.setSnippet(trunc(a != null ? a.snippet() : null, 8000));
            return r;
        };
    }

    @Bean
    public ItemWriter<CobolParseResult> parseWriter(CobolParseResultRepository repo) {
        return chunk -> repo.saveAll(chunk.getItems());
    }

    // =========================
    // STEP 1: candidates (VALID programs only)
    // =========================
    @Bean
    public Step candidateStep(JobRepository jobRepository,
                              PlatformTransactionManager tx,
                              JpaPagingItemReader<CobolProgram> validProgramReader,
                              ItemProcessor<CobolProgram, List<CobolRuleCandidateEntity>> candidateProcessor,
                              ItemWriter<List<CobolRuleCandidateEntity>> candidateWriter,
                              BatchMetricRepository metricRepo) {

        return new StepBuilder("candidateStep", jobRepository)
                .<CobolProgram, List<CobolRuleCandidateEntity>>chunk(50)
                .transactionManager(tx)
                .reader(validProgramReader)
                .processor(candidateProcessor)
                .writer(candidateWriter)
                .listener(stepMetricListener(metricRepo))
                .faultTolerant()
                .skip(Exception.class)
                .skipLimit(100_000)
                .build();
    }

    @Bean
    public ItemProcessor<CobolProgram, List<CobolRuleCandidateEntity>> candidateProcessor(
            CobolParserService parserService
    ) {
        return p -> {
            Validated vd = parserService.validateProgramWithModeAndSignals_PUBLIC(p);
            ParseAttempt a = vd.attempt();

            // should be valid due to reader filter, but keep safe
            if (a.status() != ParseStatus.VALID || a.tree() == null || a.tokens() == null) {
                return List.of();
            }

            CobolRuleCandidateVisitor vis = new CobolRuleCandidateVisitor(
                    p.getId(),
                    a.mode(),
                    a.tokens()
            );
            vis.visit(a.tree());

            return vis.candidates()
                    .stream()
                    .map(c -> CobolRuleCandidateEntity.builder()
                        .programId(c.programId())
                        .parseMode(c.parseMode())
                        .ruleKind(c.ruleKind())
                        .startLine(c.startLine())
                        .endLine(c.endLine())
                        .snippet(c.snippet())
                        .build()
                    ).toList();
        };
    }

    @Bean
    public ItemWriter<List<CobolRuleCandidateEntity>> candidateWriter(
            CobolRuleCandidateRepository candRepo
    ) {
        return chunk -> {
            for (List<CobolRuleCandidateEntity> rows : chunk.getItems()) {
                if (rows == null || rows.isEmpty()) continue;
                candRepo.saveAll(rows);
            }
        };
    }

    // =========================
    // STEP 2: structured rules (VALID programs only)
    // =========================
    @Bean
    public Step structuredStep(JobRepository jobRepository,
                               PlatformTransactionManager tx,
                               JpaPagingItemReader<CobolProgram> validProgramReader,
                               ItemProcessor<CobolProgram, List<CobolExtractedRule>> structuredProcessor,
                               ItemWriter<List<CobolExtractedRule>> structuredWriter,
                               BatchMetricRepository metricRepo) {

        return new StepBuilder("structuredStep", jobRepository)
                .<CobolProgram, List<CobolExtractedRule>>chunk(25)
                .transactionManager(tx)
                .reader(validProgramReader)
                .processor(structuredProcessor)
                .writer(structuredWriter)
                .listener(stepMetricListener(metricRepo))
                .faultTolerant()
                .skip(Exception.class)
                .skipLimit(100_000)
                .build();
    }

    @Bean
    public ItemProcessor<CobolProgram, List<CobolExtractedRule>> structuredProcessor(
            CobolParserService parserService
    ) {
        return p -> {
            Validated vd = parserService.validateProgramWithModeAndSignals_PUBLIC(p);
            ParseAttempt a = vd.attempt();

            if (a.status() != ParseStatus.VALID || a.tree() == null || a.tokens() == null) {
                return List.of();
            }

            CobolStructuredRuleVisitor vis = new CobolStructuredRuleVisitor(
                    p.getId(),
                    a.mode(),
                    a.tokens()
            );
            vis.visit(a.tree());
            return vis.rules();
        };
    }

    @Bean
    public ItemWriter<List<CobolExtractedRule>> structuredWriter(
            CobolExtractedRuleRepository extractedRepo
    ) {
        return chunk -> {
            for (List<CobolExtractedRule> rules : chunk.getItems()) {
                if (rules == null || rules.isEmpty()) continue;
                extractedRepo.saveAll(rules);
            }
        };
    }

    // =========================
    // STEP 3: normalize (extracted_rule -> normalized_rule)
    // =========================
    @Bean
    public Step normalizeStep(JobRepository jobRepository,
                              PlatformTransactionManager tx,
                              JpaPagingItemReader<CobolExtractedRule> extractedRuleReader,
                              ItemProcessor<CobolExtractedRule, CobolNormalizedRuleEntity> normalizeProcessor,
                              ItemWriter<CobolNormalizedRuleEntity> normalizeWriter,
                              BatchMetricRepository metricRepo) {

        return new StepBuilder("normalizeStep", jobRepository)
                .<CobolExtractedRule, CobolNormalizedRuleEntity>chunk(200)
                .transactionManager(tx)
                .reader(extractedRuleReader)
                .processor(normalizeProcessor)
                .writer(normalizeWriter)
                .listener(stepMetricListener(metricRepo))
                .faultTolerant()
                .skip(Exception.class)
                .skipLimit(100_000)
                .build();
    }

    @Bean
    public ItemProcessor<CobolExtractedRule, CobolNormalizedRuleEntity> normalizeProcessor(
            RuleNormalizer normalizer
    ) {
        return normalizer::normalizeEntity;
    }

    @Bean
    public ItemWriter<CobolNormalizedRuleEntity> normalizeWriter(
            CobolNormalizedRuleRepository normRepo
    ) {
        return chunk -> normRepo.saveAll(chunk.getItems());
    }

    // =========================
    // STEP 4: canonicalize (rebuild canonical table from normalized)
    // =========================
    @Bean
    public Step canonicalizeStep(JobRepository jobRepository,
                                 PlatformTransactionManager tx,
                                 JpaPagingItemReader<CobolNormalizedRuleEntity> normalizedRuleReader,
                                 ItemProcessor<CobolNormalizedRuleEntity, CobolNormalizedRuleEntity> canonicalizeProcessor,
                                 ItemWriter<CobolNormalizedRuleEntity> canonicalizeWriter,
                                 CobolCanonicalRuleRepository canonRepo,
                                 BatchMetricRepository metricRepo) {

        StepExecutionListener metrics = stepMetricListener(metricRepo);

        StepExecutionListener rebuild = new StepExecutionListener() {
            @Override public void beforeStep(StepExecution stepExecution) {}

            @Override
            public @Nullable ExitStatus afterStep(StepExecution stepExecution) {
                canonRepo.deleteAllInBatch();
                canonRepo.rebuildFromNormalized();
                log.info("Canonical table rebuilt from normalized rules.");
                return stepExecution.getExitStatus();
            }
        };

        CompositeStepExecutionListener composite = new CompositeStepExecutionListener();
        composite.register(metrics);
        composite.register(rebuild);

        return new StepBuilder("canonicalizeStep", jobRepository)
                .<CobolNormalizedRuleEntity, CobolNormalizedRuleEntity>chunk(200)
                .transactionManager(tx)
                .reader(normalizedRuleReader)
                .processor(canonicalizeProcessor)
                .writer(canonicalizeWriter)
                .listener(composite)
                .faultTolerant()
                .skip(Exception.class)
                .skipLimit(100_000)
                .build();
    }

    @Bean
    public ItemProcessor<CobolNormalizedRuleEntity, CobolNormalizedRuleEntity> canonicalizeProcessor() {
        return n -> {
            String pred = (n.getKind() == RuleKind.IF)
                    ? n.getConditionNorm()
                    : ((n.getSubjectNorm() == null ? "" : n.getSubjectNorm())
                    + "|WHEN|"
                    + (n.getWhenNorm() == null ? "" : n.getWhenNorm()));

            String key = CanonicalKey.of(
                    n.getKind(),
                    pred,
                    n.getThenNorm(),
                    n.getElseNorm(),
                    Boolean.TRUE.equals(n.getHasElse())
            );

            n.setCanonicalKey(key);

            return n;
        };
    }

    @Bean
    public ItemWriter<CobolNormalizedRuleEntity> canonicalizeWriter(
            CobolNormalizedRuleRepository normRepo
    ) {
        return chunk -> normRepo.saveAll(chunk.getItems());
    }

    // =========================
    // STEP 5: abstract canonicalize
    // =========================

    @Bean
    public Step abstractCanonicalizeStep(JobRepository jobRepository,
                                         PlatformTransactionManager tx,
                                         JpaPagingItemReader<CobolNormalizedRuleEntity> normalizedRuleReader,
                                         ItemProcessor<CobolNormalizedRuleEntity, CobolNormalizedRuleEntity> abstractKeyProcessor,
                                         ItemWriter<CobolNormalizedRuleEntity> abstractKeyWriter,
                                         CobolAbstractCanonicalRuleRepository absRepo,
                                         CobolNormalizedRuleRepository normRepo,
                                         BatchMetricRepository metricRepo) {

        StepExecutionListener metrics = stepMetricListener(metricRepo);

        StepExecutionListener rebuild = new StepExecutionListener() {
            @Override public void beforeStep(StepExecution stepExecution) {}

            @Override
            public ExitStatus afterStep(StepExecution stepExecution) {
                absRepo.deleteAllInBatch();
                absRepo.rebuildFromNormalized();
                log.info("Abstract canonical table rebuilt from normalized rules.");
                return stepExecution.getExitStatus();
            }
        };

        CompositeStepExecutionListener composite = new CompositeStepExecutionListener();
        composite.register(metrics);
        composite.register(rebuild);

        return new StepBuilder("abstractCanonicalizeStep", jobRepository)
                .<CobolNormalizedRuleEntity, CobolNormalizedRuleEntity>chunk(200)
                .transactionManager(tx)
                .reader(normalizedRuleReader)
                .processor(abstractKeyProcessor)
                .writer(abstractKeyWriter)
                .listener(composite)
                .faultTolerant()
                .skip(Exception.class)
                .skipLimit(100_000)
                .build();
    }

    @Bean
    public ItemProcessor<CobolNormalizedRuleEntity, CobolNormalizedRuleEntity> abstractKeyProcessor() {
        return n -> {
            SemanticAbstraction.AbstractRuleParts parts;

            if (n.getKind() == RuleKind.IF) {
                parts = SemanticAbstraction.abstractIf(
                        n.getConditionNorm(),
                        n.getThenNorm(),
                        n.getElseNorm()
                );
            } else {
                parts = SemanticAbstraction.abstractEvaluateRule(
                        n.getSubjectNorm(),
                        n.getWhenNorm(),
                        n.getThenNorm(),
                        n.getElseNorm()
                );
            }

            n.setAbstractPredicate(parts.predicate());
            n.setAbstractThen(parts.thenText());
            n.setAbstractElse(parts.elseText());

            String absKey = CanonicalKey.of(
                    n.getKind(),
                    parts.predicate(),
                    parts.thenText(),
                    parts.elseText(),
                    Boolean.TRUE.equals(n.getHasElse())
            );

            n.setAbstractCanonicalKey(absKey);

            return n;
        };
    }

    @Bean
    public ItemWriter<CobolNormalizedRuleEntity> abstractKeyWriter(
            CobolNormalizedRuleRepository normRepo
    ) {
        return chunk -> normRepo.saveAll(chunk.getItems());
    }

    // =========================
    // STEP 6: AI annotation of abstract canonical rules
    // =========================

    @Bean
    public Step aiAnnotateAbstractCanonicalStep(
            JobRepository jobRepository,
            PlatformTransactionManager tx,
            JpaCursorItemReader<CobolAbstractCanonicalRuleEntity> abstractCanonicalKeyCursorReader,
            JpaPagingItemReader<CobolAbstractCanonicalRuleEntity> abstractCanonicalKeyPagingReader,
            AiLabelingProcessor processor,
            AiLabelingWriter writer,
            AiLabelingProperties aiProps,
            BatchMetricRepository metricRepo
    ) {
        var reader = aiProps.repopulateAbstractText()
                ? abstractCanonicalKeyPagingReader
                : abstractCanonicalKeyCursorReader;

        return new StepBuilder("aiAnnotateAbstractCanonicalStep", jobRepository)
                .<CobolAbstractCanonicalRuleEntity, CobolAiRuleLabel>chunk(10)
                .transactionManager(tx)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .listener(stepMetricListener(metricRepo))
                .faultTolerant()
                .retry(OpenAiTransientException.class)
                .retryLimit(5)
                .skip(OpenAiTransientException.class)
                .skip(Exception.class)
                .skipLimit(100_000)
                .build();
    }

    @Bean
    @StepScope
    public JpaCursorItemReader<CobolAbstractCanonicalRuleEntity> abstractCanonicalKeyCursorReader(
            EntityManagerFactory emf
    ) {
        return new JpaCursorItemReaderBuilder<CobolAbstractCanonicalRuleEntity>()
                .name("abstractCanonicalKeyCursorReader")
                .entityManagerFactory(emf)
                .queryString("""
                select r
                from CobolAbstractCanonicalRuleEntity r
                where not exists (
                    select 1
                    from CobolAiRuleLabel l
                    where l.abstractCanonicalKey = r.abstractCanonicalKey
                )
                order by r.occurrences desc, r.abstractCanonicalKey asc
            """)
                .build();
    }

    @Bean
    @StepScope
    public JpaPagingItemReader<CobolAbstractCanonicalRuleEntity> abstractCanonicalKeyPagingReader(
            EntityManagerFactory emf
    ) {
        return new JpaPagingItemReaderBuilder<CobolAbstractCanonicalRuleEntity>()
                .name("abstractCanonicalKeyPagingReader")
                .entityManagerFactory(emf)
                .queryString("""
                select r
                from CobolAbstractCanonicalRuleEntity r
                where r.abstractPredicate is null
                order by r.occurrences desc, r.abstractCanonicalKey asc
            """)
                .pageSize(200)
                .build();
    }




    // =========================
    // READERS
    // =========================
    @Bean
    public JpaPagingItemReader<CobolProgram> programReader(EntityManagerFactory emf) {
        JpaPagingItemReader<CobolProgram> reader = new JpaPagingItemReader<>(emf);
        reader.setName("programReader");
        reader.setPageSize(50);
        reader.setQueryString("""
            select p
            from CobolProgram p
            order by p.id
        """);
        return reader;
    }

    @Bean
    public JpaPagingItemReader<CobolProgram> validProgramReader(EntityManagerFactory emf) {
        JpaPagingItemReader<CobolProgram> reader = new JpaPagingItemReader<>(emf);
        reader.setName("validProgramReader");
        reader.setPageSize(50);
        reader.setQueryString("""
            select p
            from CobolProgram p
            join CobolParseResult r on r.program.id = p.id
            where r.status = thesis.parser.ParseStatus.VALID
            order by p.id
        """);
        return reader;
    }

    @Bean
    public JpaPagingItemReader<CobolExtractedRule> extractedRuleReader(EntityManagerFactory emf) {
        JpaPagingItemReader<CobolExtractedRule> reader = new JpaPagingItemReader<>(emf);
        reader.setName("extractedRuleReader");
        reader.setPageSize(200);
        reader.setQueryString("select r from CobolExtractedRule r order by r.id");
        return reader;
    }

    @Bean
    public JpaPagingItemReader<CobolNormalizedRuleEntity> normalizedRuleReader(EntityManagerFactory emf) {
        JpaPagingItemReader<CobolNormalizedRuleEntity> reader = new JpaPagingItemReader<>(emf);
        reader.setName("normalizedRuleReader");
        reader.setPageSize(200);
        reader.setQueryString("select n from CobolNormalizedRuleEntity n order by n.id");
        return reader;
    }

    // =========================
    // Listener
    // =========================
    private StepExecutionListener stepMetricListener(BatchMetricRepository metricRepo) {
        return new StepExecutionListener() {
            @Override public void beforeStep(StepExecution stepExecution) {}

            @Override
            public @Nullable ExitStatus afterStep(StepExecution stepExecution) {
                var start = stepExecution.getStartTime();
                var end = stepExecution.getEndTime();

                Long duration = null;
                if (start != null && end != null) {
                    duration = Duration.between(start, end).toMillis();
                }

                BatchMetric metric = BatchMetric.builder()
                        .jobName(stepExecution.getJobExecution().getJobInstance().getJobName())
                        .jobInstanceId(stepExecution.getJobExecution().getJobInstance().getId())
                        .jobExecutionId(stepExecution.getJobExecution().getId())
                        .stepName(stepExecution.getStepName())
                        .stepExecutionId(stepExecution.getId())
                        .batchStatus(stepExecution.getStatus().name())
                        .exitCode(stepExecution.getExitStatus().getExitCode())
                        .startTime(start != null ? start.atZone(ZoneId.systemDefault()).toInstant() : null)
                        .endTime(end != null ? end.atZone(ZoneId.systemDefault()).toInstant() : null)
                        .durationMs(duration)
                        .readCount((long) stepExecution.getReadCount())
                        .writeCount((long) stepExecution.getWriteCount())
                        .filterCount((long) stepExecution.getFilterCount())
                        .readSkipCount((long) stepExecution.getReadSkipCount())
                        .processSkipCount((long) stepExecution.getProcessSkipCount())
                        .writeSkipCount((long) stepExecution.getWriteSkipCount())
                        .commitCount((long) stepExecution.getCommitCount())
                        .rollbackCount((long) stepExecution.getRollbackCount())
                        .createdAt(Instant.now())
                        .build();

                metricRepo.save(metric);
                return stepExecution.getExitStatus();
            }
        };
    }

    private static String trunc(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 3)) + "...";
    }
}