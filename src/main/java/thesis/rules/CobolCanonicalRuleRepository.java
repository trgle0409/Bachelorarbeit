package thesis.rules;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface CobolCanonicalRuleRepository extends JpaRepository<CobolCanonicalRuleEntity, Long> {

    java.util.Optional<CobolCanonicalRuleEntity> findByCanonicalKey(String canonicalKey);

    @Modifying
    @Transactional
    @Query(value = """
    insert into cobol_canonical_rules
      (canonical_key, kind, condition_norm, then_norm, else_norm, has_else,
       occurrences, min_complexity, max_complexity, avg_complexity, created_at, updated_at)
    select
      n.canonical_key,
      min(n.kind) as kind,
      min(n.condition_norm) as condition_norm,
      min(n.then_norm) as then_norm,
      min(n.else_norm) as else_norm,
      bool_or(n.has_else) as has_else,
      count(*) as occurrences,
      min(n.complexity_score) as min_complexity,
      max(n.complexity_score) as max_complexity,
      avg(n.complexity_score) as avg_complexity,
      now() as created_at,
      now() as updated_at
    from cobol_normalized_rules n
    where n.canonical_key is not null
    group by n.canonical_key
    on conflict (canonical_key) do update
    set
      kind = excluded.kind,
      condition_norm = excluded.condition_norm,
      then_norm = excluded.then_norm,
      else_norm = excluded.else_norm,
      has_else = excluded.has_else,
      occurrences = excluded.occurrences,
      min_complexity = excluded.min_complexity,
      max_complexity = excluded.max_complexity,
      avg_complexity = excluded.avg_complexity,
      updated_at = now()
    """, nativeQuery = true)
    void rebuildFromNormalized();
}