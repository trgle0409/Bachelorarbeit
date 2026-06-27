package thesis.batch;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BatchMetricRepository extends JpaRepository<BatchMetric, Long> {
}