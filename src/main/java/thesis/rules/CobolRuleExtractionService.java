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
public class CobolRuleExtractionService {

    private final CobolProgramRepository programRepo;
    private final CobolParseResultRepository resultRepo;
    private final CobolParserService parserService;
    private final CobolRuleCandidateRepository candRepo;

    public CobolRuleExtractionService(CobolProgramRepository programRepo,
                                      CobolParseResultRepository resultRepo,
                                      CobolParserService parserService,
                                      CobolRuleCandidateRepository candRepo) {
        this.programRepo = programRepo;
        this.resultRepo = resultRepo;
        this.parserService = parserService;
        this.candRepo = candRepo;
    }

    @Transactional // required because programRepo.streamAll() uses streaming query
    public List<RuleCandidate> extractAllCandidates() {
        List<RuleCandidate> all = new ArrayList<>();

        try (Stream<CobolProgram> stream = programRepo.streamAll()) {
            stream.forEach(p -> {
                var prOpt = resultRepo.findByProgram_Id(p.getId());
                if (prOpt.isEmpty() || prOpt.get().getStatus() != ParseStatus.VALID) return;

                // parse lại để lấy tree + tokens
                Validated vd = parserService.validateProgramWithModeAndSignals_PUBLIC(p);
                ParseAttempt a = vd.attempt();
                if (a.status() != ParseStatus.VALID || a.tree() == null) return;

                CobolRuleCandidateVisitor vis = new CobolRuleCandidateVisitor(
                        p.getId(),
                        a.mode(),
                        a.tokens()
                );
                vis.visit(a.tree());
                List<RuleCandidate> cs = vis.candidates();

                if (cs.isEmpty()) return;

                // ===== logging (samples) =====
                int sampleN = Math.min(3, cs.size());
                log.info("programId={} mode={} candidates={} (showing {})",
                        p.getId(), a.mode(), cs.size(), sampleN);

                for (int i = 0; i < sampleN; i++) {
                    RuleCandidate c = cs.get(i);
                    String snip = c.snippet();
                    if (snip != null && snip.length() > 200) {
                        snip = snip.substring(0, 200) + "...";
                    }
                    log.info("  sample#{} kind={} lines {}-{} :: {}",
                            (i + 1), c.ruleKind(), c.startLine(), c.endLine(),
                            snip == null ? "" : snip.replace("\n", " ⏎ "));
                }
                // =============================

                // ====== PERSIST (no duplicates) ======
                candRepo.deleteAllByProgramId(p.getId());

                List<CobolRuleCandidateEntity> rows = cs.stream()
                        .map(c -> CobolRuleCandidateEntity.builder()
                                .programId(c.programId())
                                .parseMode(c.parseMode())
                                .ruleKind(c.ruleKind())
                                .startLine(c.startLine())
                                .endLine(c.endLine())
                                .snippet(c.snippet())
                                .build())
                        .toList();

                candRepo.saveAll(rows);
                // ====================================

                // add to overall list ONCE
                all.addAll(cs);
            });
        }

        long ifTotal = all.stream().filter(c -> "IF".equals(c.ruleKind())).count();
        long evalTotal = all.stream().filter(c -> "EVALUATE".equals(c.ruleKind())).count();

        log.info("==== RULE CANDIDATE SUMMARY ====");
        log.info("TOTAL candidates: {}", all.size());
        log.info("IF candidates: {}", ifTotal);
        log.info("EVALUATE candidates: {}", evalTotal);
        log.info("================================");

        return all;
    }
}