package thesis;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;

import java.time.LocalDateTime;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Test utility that reaches into a built {@link Step} and fires its registered
 * {@link StepExecutionListener#afterStep(StepExecution)} callback.
 *
 * <p>The {@code canonicalizeStep} and {@code abstractCanonicalizeStep} beans in
 * {@link BatchJobConfig} attach a {@code CompositeStepExecutionListener} whose
 * {@code afterStep} performs the "delete + rebuild" side effect on the canonical
 * / abstract-canonical tables. Launching the full Spring Batch runtime to observe
 * that side effect would be an integration test; instead we invoke the listener
 * directly with a fully stubbed {@link StepExecution}.
 *
 * <p>The composite listener also contains the metric listener, which navigates
 * {@code stepExecution.getJobExecution().getJobInstance()...} and reads timing /
 * counter fields. We therefore use a deeply stubbed mock graph so the test is
 * independent of the (primitive-id, no-orphan-entity) constructor rules
 * introduced in Spring Batch 6.0.
 *
 * <p>Spring Batch's {@code AbstractStep} (super-type of every concrete step,
 * including {@code TaskletStep} and the 6.0 {@code ChunkOrientedStep}) keeps its
 * composite listener in a private field named {@code stepExecutionListener};
 * we access it reflectively.
 */
final class StepListenerInvoker {

    private StepListenerInvoker() {
    }

    static void fireAfterStep(Step step) throws Exception {
        StepExecutionListener listener = extractListener(step);
        listener.afterStep(stubStepExecution(step.getName()));
    }

    /** Build a StepExecution mock that satisfies both the rebuild and metric listeners. */
    private static StepExecution stubStepExecution(String stepName) {
        StepExecution stepExecution = mock(StepExecution.class);
        JobExecution jobExecution = mock(JobExecution.class);
        JobInstance jobInstance = mock(JobInstance.class);

        lenient().when(jobInstance.getJobName()).thenReturn("cobolRulePipelineJob");
        lenient().when(jobInstance.getId()).thenReturn(1L);

        lenient().when(jobExecution.getJobInstance()).thenReturn(jobInstance);
        lenient().when(jobExecution.getId()).thenReturn(1L);

        lenient().when(stepExecution.getJobExecution()).thenReturn(jobExecution);
        lenient().when(stepExecution.getStepName()).thenReturn(stepName);
        lenient().when(stepExecution.getId()).thenReturn(1L);
        lenient().when(stepExecution.getStatus()).thenReturn(BatchStatus.COMPLETED);
        lenient().when(stepExecution.getExitStatus()).thenReturn(ExitStatus.COMPLETED);
        lenient().when(stepExecution.getStartTime()).thenReturn(LocalDateTime.now().minusSeconds(1));
        lenient().when(stepExecution.getEndTime()).thenReturn(LocalDateTime.now());

        lenient().when(stepExecution.getReadCount()).thenReturn(0L);
        lenient().when(stepExecution.getWriteCount()).thenReturn(0L);
        lenient().when(stepExecution.getFilterCount()).thenReturn(0L);
        lenient().when(stepExecution.getReadSkipCount()).thenReturn(0L);
        lenient().when(stepExecution.getProcessSkipCount()).thenReturn(0L);
        lenient().when(stepExecution.getWriteSkipCount()).thenReturn(0L);
        lenient().when(stepExecution.getCommitCount()).thenReturn(0L);
        lenient().when(stepExecution.getRollbackCount()).thenReturn(0L);

        return stepExecution;
    }

    private static StepExecutionListener extractListener(Step step) throws Exception {
        Class<?> c = step.getClass();
        while (c != null) {
            try {
                var f = c.getDeclaredField("stepExecutionListener");
                f.setAccessible(true);
                return (StepExecutionListener) f.get(step);
            } catch (NoSuchFieldException ignore) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(
                "stepExecutionListener not found on " + step.getClass()
                        + " (is this a TaskletStep/ChunkOrientedStep extending AbstractStep?)");
    }
}
