package thesis.batch;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "batch_metrics")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BatchMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String jobName;
    private Long jobExecutionId;
    private Long jobInstanceId;

    private String stepName;
    private Long stepExecutionId;

    private String batchStatus;
    private String exitCode;

    private Instant startTime;
    private Instant endTime;
    private Long durationMs;

    private Long readCount;
    private Long writeCount;
    private Long filterCount;
    private Long readSkipCount;
    private Long processSkipCount;
    private Long writeSkipCount;
    private Long commitCount;
    private Long rollbackCount;

    private Instant createdAt;
}