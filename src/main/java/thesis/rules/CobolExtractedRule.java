package thesis.rules;

import jakarta.persistence.*;
import lombok.*;
import thesis.parser.ParseMode;

@Entity
@Table(
        name = "cobol_extracted_rules",
        indexes = {
                @Index(name="idx_extrule_program", columnList="program_id"),
                @Index(name="idx_extrule_type", columnList="rule_type")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CobolExtractedRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name="program_id", nullable = false)
    private Long programId;

    @Enumerated(EnumType.STRING)
    @Column(name="parse_mode", nullable = false, length = 30)
    private ParseMode parseMode;

    @Enumerated(EnumType.STRING)
    @Column(name="rule_type", nullable = false, length = 30)
    private RuleKind ruleType; // IF / EVALUATE

    @Column(nullable = false)
    private Integer startLine;

    @Column(nullable = false)
    private Integer endLine;

    // IF: condition text (between IF ... THEN)
    @Column(columnDefinition = "text")
    private String conditionText;

    // EVALUATE: subject text (between EVALUATE ... WHEN)
    @Column(columnDefinition = "text")
    private String subjectText;

    // Optional: rough then/else extraction (token-based)
    @Column(columnDefinition = "text")
    private String thenText;

    @Column(columnDefinition = "text")
    private String elseText;

    // ====== EVALUATE branch ======
    private Boolean evaluateBooleanMode;

    @Column(columnDefinition = "text")
    private String whenText;

    @Column(name="when_index")
    private Integer whenIndex;

    @Column(name="is_when_other")
    private Boolean isWhenOther;

    // Always keep raw snippet for traceability
    @Column(columnDefinition = "text")
    private String rawSnippet;
}