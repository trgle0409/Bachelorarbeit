package thesis.batch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Slf4j
@Configuration
public class BatchMonitoringConfig {

    @Bean
    public JobExecutionListener jobMetricsListener() {
        return new JobExecutionListener() {
            private long startNanos;

            @Override
            public void beforeJob(JobExecution jobExecution) {
                startNanos = System.nanoTime();
                log.info("=== JOB START === name={} params={}",
                        jobExecution.getJobInstance().getJobName(),
                        jobExecution.getJobParameters());
            }

            @Override
            public void afterJob(JobExecution jobExecution) {
                long tookMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

                // Tổng hợp metrics của tất cả steps
                int totalRead = 0, totalWrite = 0, totalSkip = 0;
                long totalCommit = 0, totalRollback = 0;

                for (StepExecution s : jobExecution.getStepExecutions()) {
                    totalRead += s.getReadCount();
                    totalWrite += s.getWriteCount();
                    totalSkip += (s.getReadSkipCount() + s.getProcessSkipCount() + s.getWriteSkipCount());
                    totalCommit += s.getCommitCount();
                    totalRollback += s.getRollbackCount();
                }

                log.info("=== JOB END === status={} exit={} tookMs={} totalRead={} totalWrite={} totalSkip={} commits={} rollbacks={}",
                        jobExecution.getStatus(),
                        jobExecution.getExitStatus().getExitCode(),
                        tookMs,
                        totalRead, totalWrite, totalSkip,
                        totalCommit, totalRollback
                );
            }
        };
    }

    @Bean
    public StepExecutionListener stepMetricsListener() {
        return new StepExecutionListener() {
            private long startNanos;

            @Override
            public void beforeStep(StepExecution stepExecution) {
                startNanos = System.nanoTime();
                log.info("--- STEP START --- name={}", stepExecution.getStepName());
            }

            @Override
            public ExitStatus afterStep(StepExecution stepExecution) {
                long tookMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

                int skip = Math.toIntExact(stepExecution.getReadSkipCount()
                        + stepExecution.getProcessSkipCount()
                        + stepExecution.getWriteSkipCount());

                log.info("--- STEP END --- name={} status={} exit={} tookMs={} read={} write={} filter={} skip(read/process/write)={}/{}/{} commits={} rollbacks={}",
                        stepExecution.getStepName(),
                        stepExecution.getStatus(),
                        stepExecution.getExitStatus().getExitCode(),
                        tookMs,
                        stepExecution.getReadCount(),
                        stepExecution.getWriteCount(),
                        stepExecution.getFilterCount(),
                        stepExecution.getReadSkipCount(),
                        stepExecution.getProcessSkipCount(),
                        stepExecution.getWriteSkipCount(),
                        stepExecution.getCommitCount(),
                        stepExecution.getRollbackCount()
                );

                return stepExecution.getExitStatus();
            }
        };
    }
}