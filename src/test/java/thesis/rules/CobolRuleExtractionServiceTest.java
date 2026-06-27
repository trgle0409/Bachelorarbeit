package thesis.rules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import thesis.corpus.CobolProgram;
import thesis.corpus.CobolProgramRepository;
import thesis.parser.CobolParseResult;
import thesis.parser.CobolParseResultRepository;
import thesis.parser.CobolParserService;
import thesis.parser.ParseAttempt;
import thesis.parser.ParseMode;
import thesis.parser.ParseStatus;
import thesis.parser.Validated;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CobolRuleExtractionService#extractAllCandidates()}.
 *
 * <p>The visitor itself needs a real ANTLR parse tree, which is out of scope for
 * a unit test. We focus on the orchestration logic that surrounds it: which
 * programs are skipped (no parse result, non-VALID status, missing tree) and
 * that persistence is not triggered for skipped programs.
 */
class CobolRuleExtractionServiceTest {

    private CobolProgramRepository programRepo;
    private CobolParseResultRepository resultRepo;
    private CobolParserService parserService;
    private CobolRuleCandidateRepository candRepo;

    private CobolRuleExtractionService service;

    @BeforeEach
    void setUp() {
        programRepo = mock(CobolProgramRepository.class);
        resultRepo = mock(CobolParseResultRepository.class);
        parserService = mock(CobolParserService.class);
        candRepo = mock(CobolRuleCandidateRepository.class);
        service = new CobolRuleExtractionService(programRepo, resultRepo, parserService, candRepo);
    }

    private static CobolProgram program(long id) {
        return CobolProgram.builder()
                .id(id).systemName("S").path("/p" + id + ".cbl").sourceType("S").build();
    }

    private static CobolParseResult result(ParseStatus status) {
        CobolParseResult r = new CobolParseResult();
        r.setStatus(status);
        return r;
    }

    @Test
    @DisplayName("skips a program with no parse result; nothing is persisted")
    void skipsProgramWithoutParseResult() {
        CobolProgram p = program(1L);
        when(programRepo.streamAll()).thenReturn(Stream.of(p));
        when(resultRepo.findByProgram_Id(1L)).thenReturn(Optional.empty());

        List<RuleCandidate> out = service.extractAllCandidates();

        assertThat(out).isEmpty();
        verify(parserService, never()).validateProgramWithModeAndSignals_PUBLIC(any());
        verify(candRepo, never()).saveAll(any());
        verify(candRepo, never()).deleteAllByProgramId(anyLong());
    }

    @Test
    @DisplayName("skips a program whose parse result is not VALID")
    void skipsNonValidParseResult() {
        CobolProgram p = program(2L);
        when(programRepo.streamAll()).thenReturn(Stream.of(p));
        when(resultRepo.findByProgram_Id(2L))
                .thenReturn(Optional.of(result(ParseStatus.PARSER_ERROR)));

        List<RuleCandidate> out = service.extractAllCandidates();

        assertThat(out).isEmpty();
        verify(parserService, never()).validateProgramWithModeAndSignals_PUBLIC(any());
        verify(candRepo, never()).saveAll(any());
    }

    @Test
    @DisplayName("skips a VALID program whose re-parse yields no tree")
    void skipsWhenReparseHasNoTree() {
        CobolProgram p = program(3L);
        when(programRepo.streamAll()).thenReturn(Stream.of(p));
        when(resultRepo.findByProgram_Id(3L))
                .thenReturn(Optional.of(result(ParseStatus.VALID)));

        // Re-parse returns VALID status but a null tree -> guarded out before the visitor.
        ParseAttempt attempt = new ParseAttempt(
                ParseStatus.VALID, ParseMode.FULL, "OK",
                null, null, null, null, /*tree*/ null, /*tokens*/ null);
        when(parserService.validateProgramWithModeAndSignals_PUBLIC(p))
                .thenReturn(new Validated(attempt, null, null, null));

        List<RuleCandidate> out = service.extractAllCandidates();

        assertThat(out).isEmpty();
        verify(parserService).validateProgramWithModeAndSignals_PUBLIC(p);
        verify(candRepo, never()).saveAll(any());
        verify(candRepo, never()).deleteAllByProgramId(anyLong());
    }

    @Test
    @DisplayName("skips a VALID program whose re-parse status regressed to non-VALID")
    void skipsWhenReparseStatusNotValid() {
        CobolProgram p = program(4L);
        when(programRepo.streamAll()).thenReturn(Stream.of(p));
        when(resultRepo.findByProgram_Id(4L))
                .thenReturn(Optional.of(result(ParseStatus.VALID)));

        ParseAttempt attempt = new ParseAttempt(
                ParseStatus.PARSER_ERROR, ParseMode.FULL, "regressed",
                null, null, null, null, null, null);
        when(parserService.validateProgramWithModeAndSignals_PUBLIC(p))
                .thenReturn(new Validated(attempt, null, null, null));

        List<RuleCandidate> out = service.extractAllCandidates();

        assertThat(out).isEmpty();
        verify(candRepo, never()).saveAll(any());
    }

    @Test
    @DisplayName("processes a mix and only re-parses VALID programs")
    void mixedStreamOnlyValidReparsed() {
        CobolProgram valid = program(10L);
        CobolProgram invalid = program(11L);
        CobolProgram noResult = program(12L);

        when(programRepo.streamAll()).thenReturn(Stream.of(valid, invalid, noResult));

        lenient().when(resultRepo.findByProgram_Id(10L))
                .thenReturn(Optional.of(result(ParseStatus.VALID)));
        lenient().when(resultRepo.findByProgram_Id(11L))
                .thenReturn(Optional.of(result(ParseStatus.LEXER_ERROR)));
        lenient().when(resultRepo.findByProgram_Id(12L))
                .thenReturn(Optional.empty());

        // For the VALID one, return a null-tree attempt so the visitor is skipped
        // (keeps the test independent of the ANTLR grammar) but the re-parse still happens.
        ParseAttempt attempt = new ParseAttempt(
                ParseStatus.VALID, ParseMode.FULL, "OK",
                null, null, null, null, null, null);
        when(parserService.validateProgramWithModeAndSignals_PUBLIC(valid))
                .thenReturn(new Validated(attempt, null, null, null));

        List<RuleCandidate> out = service.extractAllCandidates();

        assertThat(out).isEmpty();
        // Only the VALID program is re-parsed.
        verify(parserService).validateProgramWithModeAndSignals_PUBLIC(valid);
        verify(parserService, never()).validateProgramWithModeAndSignals_PUBLIC(invalid);
        verify(parserService, never()).validateProgramWithModeAndSignals_PUBLIC(noResult);
    }

    @Test
    @DisplayName("an empty corpus stream yields an empty result with no persistence")
    void emptyCorpus() {
        when(programRepo.streamAll()).thenReturn(Stream.empty());

        List<RuleCandidate> out = service.extractAllCandidates();

        assertThat(out).isEmpty();
        verify(candRepo, never()).saveAll(any());
    }
}