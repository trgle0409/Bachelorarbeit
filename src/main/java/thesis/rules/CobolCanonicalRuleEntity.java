package thesis.rules;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "cobol_canonical_rules",
        indexes = {
                @Index(name = "idx_canon_kind", columnList = "kind")
        }
)
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class CobolCanonicalRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "canonical_key",
            nullable = false,
            length = 64,
            unique = true)
    private String canonicalKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RuleKind kind;

    @Column(columnDefinition = "text")
    private String conditionNorm;

    @Column(columnDefinition = "text")
    private String thenNorm;

    @Column(columnDefinition = "text")
    private String elseNorm;

    @Column(nullable = false)
    private Boolean hasElse;

    @Column(nullable = false)
    private Integer occurrences;

    private Integer minComplexity;
    private Integer maxComplexity;
    private Double avgComplexity;

    @Column(nullable = false)
    private java.time.LocalDateTime createdAt;

    @Column(nullable = false)
    private java.time.LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        var now = java.time.LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (occurrences == null) occurrences = 0;
        if (hasElse == null) hasElse = Boolean.FALSE;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = java.time.LocalDateTime.now();
    }
}