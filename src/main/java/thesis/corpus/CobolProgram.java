package thesis.corpus;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "cobol_programs",
        indexes = { @Index(name = "idx_cobol_programs_sourceType", columnList = "sourceType") },
        uniqueConstraints = { @UniqueConstraint(name = "uk_cobol_programs_path", columnNames = {"path"}) }
)@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CobolProgram {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Ví dụ: "GENAPP", "X-COBOL"
    @Column(nullable = false, length = 100)
    private String systemName;

    // Đường dẫn file COBOL
    @Column(nullable = false, length = 500)
    private String path;

    // Ví dụ: "GENAPP" hoặc "XCOBOL"
    @Column(nullable = false, length = 50)
    private String sourceType;

    // Sau này có thể thêm: domain, complexityMetric, v.v.
}