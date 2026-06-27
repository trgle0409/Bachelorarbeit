package thesis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

@Slf4j
@SpringBootApplication
public class CobolRuleExtractionApplication {

    public static void main(String[] args) {
        SpringApplication.run(CobolRuleExtractionApplication.class, args);
    }

    @Bean
    CommandLineRunner runJob(
            JobLauncher jobLauncher,
            Job cobolRulePipelineJob,
            ConfigurableApplicationContext context
    ) {
        return args -> {
            var params = new JobParametersBuilder()
                    .addLong("timestamp", System.currentTimeMillis())
                    .toJobParameters();

            var exec = jobLauncher.run(cobolRulePipelineJob, params);

            log.info("JOB finished: status={} exit={}",
                    exec.getStatus(),
                    exec.getExitStatus());

            int exitCode = exec.getStatus() == BatchStatus.COMPLETED ? 0 : 1;

            SpringApplication.exit(context, (ExitCodeGenerator) () -> exitCode);
            System.exit(exitCode);
        };
    }
}