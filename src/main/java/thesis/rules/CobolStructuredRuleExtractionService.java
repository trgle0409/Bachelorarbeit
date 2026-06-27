package thesis.rules;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import thesis.corpus.CobolProgram;
import thesis.corpus.CobolProgramRepository;
import thesis.parser.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
@Service
public class CobolStructuredRuleExtractionService {

    private final CobolProgramRepository programRepo;
    private final CobolParseResultRepository resultRepo;
    private final CobolParserService parserService;
    private final CobolExtractedRuleRepository extractedRepo;

    public CobolStructuredRuleExtractionService(CobolProgramRepository programRepo,
                                                CobolParseResultRepository resultRepo,
                                                CobolParserService parserService,
                                                CobolExtractedRuleRepository extractedRepo) {
        this.programRepo = programRepo;
        this.resultRepo = resultRepo;
        this.parserService = parserService;
        this.extractedRepo = extractedRepo;
    }

    @Transactional
    public int extractAndPersistAll() {
        List<CobolExtractedRule> all = new ArrayList<>();

        try (Stream<CobolProgram> stream = programRepo.streamAll()) {
            stream.forEach(p -> {
                var prOpt = resultRepo.findByProgram_Id(p.getId());
                if (prOpt.isEmpty() || prOpt.get().getStatus() != ParseStatus.VALID) return;

                Validated vd = parserService.validateProgramWithModeAndSignals_PUBLIC(p);
                ParseAttempt a = vd.attempt();
                if (a.status() != ParseStatus.VALID || a.tree() == null || a.tokens() == null) return;

                CobolStructuredRuleVisitor vis = new CobolStructuredRuleVisitor(
                        p.getId(), a.mode(), a.tokens()
                );
                vis.visit(a.tree());
                List<CobolExtractedRule> rules = vis.rules();
                if (rules.isEmpty()) return;

                // no duplicates per program
                extractedRepo.deleteAllByProgramId(p.getId());
                extractedRepo.saveAll(rules);

                all.addAll(rules);

                log.info("programId={} mode={} extractedRules={}", p.getId(), a.mode(), rules.size());
            });
        }

        long ifTotal = all.stream().filter(r -> r.getRuleType() == RuleKind.IF).count();
        long evalTotal = all.stream().filter(r -> r.getRuleType() == RuleKind.EVALUATE).count();

        log.info("==== STRUCTURED RULE SUMMARY ====");
        log.info("TOTAL extracted rules: {}", all.size());
        log.info("IF rules: {}", ifTotal);
        log.info("EVALUATE rules: {}", evalTotal);
        log.info("=================================");

        return all.size();
    }
}