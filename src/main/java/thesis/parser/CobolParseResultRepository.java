package thesis.parser;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CobolParseResultRepository extends JpaRepository<CobolParseResult, Long> {
    Optional<CobolParseResult> findByProgram_Id(Long programId);
}
