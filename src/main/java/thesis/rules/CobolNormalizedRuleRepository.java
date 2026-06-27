package thesis.rules;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CobolNormalizedRuleRepository extends JpaRepository<CobolNormalizedRuleEntity, Long> {

    @Query(value = """
    select
      n.program_id       as programId,
      n.start_line       as startLine,
      n.end_line         as endLine,
      n.kind             as kind,
      n.condition_norm   as conditionNorm,
      n.subject_norm     as subjectNorm,
      n.when_norm        as whenNorm,
      n.then_norm        as thenNorm,
      n.else_norm        as elseNorm,
      e.raw_snippet      as rawSnippet
    from cobol_normalized_rules n
    left join cobol_extracted_rules e
           on e.program_id = n.program_id
          and e.start_line = n.start_line
          and e.end_line   = n.end_line
          and e.rule_type  = n.kind
    where n.abstract_canonical_key = :key
    order by n.program_id, n.start_line, n.id
    limit :limit
    """, nativeQuery = true)
    List<NormalizedRuleExampleView> findExamplesForAbstractKey(
            @Param("key") String key,
            @Param("limit") int limit
    );
}