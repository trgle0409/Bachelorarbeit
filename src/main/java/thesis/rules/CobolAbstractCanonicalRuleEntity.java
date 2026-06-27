package thesis.rules;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(
        name = "cobol_abstract_canonical_rules",
        indexes = {
                @Index(name = "idx_abs_canon_key", columnList = "abstract_canonical_key")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_abs_canon_key", columnNames = {"abstract_canonical_key"})
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CobolAbstractCanonicalRuleEntity {

    @Id
    @Column(name = "abstract_canonical_key", nullable = false, length = 128)
    private String abstractCanonicalKey;

    @Column(name = "canonical_key", columnDefinition = "text")
    private String canonicalKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RuleKind kind;

    @Column(name = "has_else")
    private Boolean hasElse;

    @Column(name = "occurrences", nullable = false)
    private Long occurrences;

    /**
     * Human-readable abstract predicate/condition after SemanticAbstraction.
     * Example: "VAR1 = <S>" or "VAR1 > <N>"
     * Populated during AI labeling step (AiLabelingProcessor).
     */
    @Column(name = "abstract_predicate", columnDefinition = "text")
    private String abstractPredicate;

    /**
     * Human-readable abstract THEN-block after SemanticAbstraction.
     * Example: "MOVE <S> TO VAR2"
     * Populated during AI labeling step (AiLabelingProcessor).
     */
    @Column(name = "abstract_then", columnDefinition = "text")
    private String abstractThen;

    /**
     * Human-readable abstract ELSE-block after SemanticAbstraction.
     * Example: "MOVE <S> TO VAR2" or null if no ELSE.
     * Populated during AI labeling step (AiLabelingProcessor).
     */
    @Column(name = "abstract_else", columnDefinition = "text")
    private String abstractElse;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}