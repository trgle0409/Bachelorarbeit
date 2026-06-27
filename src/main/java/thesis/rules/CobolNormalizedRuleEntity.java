package thesis.rules;

import jakarta.persistence.*;
import lombok.*;
import thesis.parser.ParseMode;

@Entity
@Table(
        name = "cobol_normalized_rules",
        indexes = {
                @Index(name = "idx_norm_program", columnList = "program_id"),
                @Index(name = "idx_norm_kind", columnList = "kind")
        }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class CobolNormalizedRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name="program_id", nullable=false)
    private Long programId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ParseMode parseMode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RuleKind kind;

    @Column(nullable=false)
    private Integer startLine;

    @Column(nullable=false)
    private Integer endLine;

    @Column(columnDefinition = "text")
    private String conditionNorm;

    @Column(columnDefinition = "text")
    private String subjectNorm;

    @Column(columnDefinition = "text")
    private String thenNorm;

    @Column(columnDefinition = "text")
    private String elseNorm;

    @Column(nullable=false)
    private Boolean hasElse;

    @Column(columnDefinition = "text")
    private String whenNorm;

    @Column(nullable=false)
    private Integer thenStmtCount;

    @Column(nullable=false)
    private Integer elseStmtCount;

    @Column(nullable=false)
    private Integer complexityScore;

    @Column(name = "canonical_key", length = 64)
    private String canonicalKey;

    @Column(name = "abstract_canonical_key", length = 128)
    private String abstractCanonicalKey;

    @Column(name = "abstract_predicate", columnDefinition = "TEXT")
    private String abstractPredicate;

    @Column(name = "abstract_then", columnDefinition = "TEXT")
    private String abstractThen;

    @Column(name = "abstract_else", columnDefinition = "TEXT")
    private String abstractElse;
}