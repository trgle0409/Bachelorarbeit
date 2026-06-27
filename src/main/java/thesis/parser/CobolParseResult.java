package thesis.parser;

import jakarta.persistence.*;
import lombok.*;
import thesis.corpus.CobolProgram;

@Entity
@Table(name = "cobol_parse_results",
        indexes = { @Index(name="idx_parse_program", columnList="program_id") })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CobolParseResult {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "program_id", nullable = false, unique = true)
    private CobolProgram program;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ParseStatus status;


    @Enumerated(EnumType.STRING)
    @Column(name = "parse_mode", nullable = false, length = 30)
    private ParseMode parseMode;

    @Column(columnDefinition = "TEXT")
    private String snippet;

    @Column(columnDefinition = "text")
    private String message;

    private Integer lineNo;
    private Integer charPos;

    @Column(columnDefinition = "text")
    private String offendingToken;
}
