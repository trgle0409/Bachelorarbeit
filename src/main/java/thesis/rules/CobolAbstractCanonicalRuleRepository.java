package thesis.rules;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface CobolAbstractCanonicalRuleRepository
        extends JpaRepository<CobolAbstractCanonicalRuleEntity, String> {

    @Modifying
    @Transactional
    @Query(value = """
    INSERT INTO cobol_abstract_canonical_rules (
        abstract_canonical_key,
        kind,
        has_else,
        occurrences,
        abstract_predicate,
        abstract_then,
        abstract_else,
        canonical_key,
        created_at,
        updated_at
    )
    SELECT
        n.abstract_canonical_key,
        n.kind,
        n.has_else,
        COUNT(*) AS occurrences,
        n.abstract_predicate,
        n.abstract_then,
        n.abstract_else,
        MIN(n.canonical_key) AS canonical_key,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    FROM cobol_normalized_rules n
    WHERE n.abstract_canonical_key IS NOT NULL
    GROUP BY
        n.abstract_canonical_key,
        n.kind,
        n.has_else,
        n.abstract_predicate,
        n.abstract_then,
        n.abstract_else
    ON CONFLICT (abstract_canonical_key)
    DO UPDATE SET
        kind               = EXCLUDED.kind,
        has_else           = EXCLUDED.has_else,
        occurrences        = EXCLUDED.occurrences,
        abstract_predicate = EXCLUDED.abstract_predicate,
        abstract_then      = EXCLUDED.abstract_then,
        abstract_else      = EXCLUDED.abstract_else,
        canonical_key      = EXCLUDED.canonical_key,
        updated_at         = CURRENT_TIMESTAMP
    """, nativeQuery = true)
    void rebuildFromNormalized();
}