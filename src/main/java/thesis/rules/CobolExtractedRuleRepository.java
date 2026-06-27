package thesis.rules;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface CobolExtractedRuleRepository extends JpaRepository<CobolExtractedRule, Long> {

    @Modifying
    @Transactional
    @Query("delete from CobolExtractedRule r where r.programId = :programId")
    int deleteAllByProgramId(Long programId);
}