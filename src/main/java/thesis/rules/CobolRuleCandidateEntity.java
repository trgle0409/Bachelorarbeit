package thesis.rules;

import jakarta.persistence.*;
import lombok.*;
import thesis.parser.ParseMode;

@Entity
@Table(
        name = "cobol_rule_candidates",
        indexes = {
                @Index(name = "idx_rulecand_program", columnList = "program_id"),
                @Index(name = "idx_rulecand_kind", columnList = "kind")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CobolRuleCandidateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "program_id", nullable = false)
    private Long programId;

    @Enumerated(EnumType.STRING)
    @Column(name = "parse_mode", nullable = false, length = 30)
    private ParseMode parseMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private RuleKind ruleKind;

    @Column(name = "start_line", nullable = false)
    private Integer startLine;

    @Column(name = "end_line", nullable = false)
    private Integer endLine;

    @Column(name = "snippet", columnDefinition = "text")
    private String snippet;
}