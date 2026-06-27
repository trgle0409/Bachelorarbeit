package thesis.ai;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;

@Entity
@Table(name = "cobol_ai_rule_labels")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class CobolAiRuleLabel {

    @Id
    @Column(name = "abstract_canonical_key", nullable = false, length = 255)
    private String abstractCanonicalKey;

    @Column(name = "kind", nullable = false, length = 50)
    private String kind;

    @Column(name = "title", columnDefinition = "text")
    private String title;

    @Column(name = "rule_summary", columnDefinition = "text")
    private String ruleSummary;

    @Column(name = "pattern", length = 200)
    private String pattern;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "model", length = 100)
    private String model;

    @Column(name = "prompt_version", length = 50)
    private String promptVersion;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}