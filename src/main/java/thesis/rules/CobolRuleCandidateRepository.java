package thesis.rules;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CobolRuleCandidateRepository extends JpaRepository<CobolRuleCandidateEntity, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from CobolRuleCandidateEntity c where c.programId = :programId")
    int deleteAllByProgramId(@Param("programId") Long programId);
}