package thesis.corpus;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public interface CobolProgramRepository extends JpaRepository<CobolProgram, Long> {

    // ======================================================
    // Basic lookup
    // ======================================================

    boolean existsByPath(String path);

    boolean existsByPathIgnoreCase(String path);

    Optional<CobolProgram> findByPath(String path);

    CobolProgram findFirstByOrderByIdAsc();


    // ======================================================
    // Streaming (must be used inside @Transactional service)
    // ======================================================

    @Query("select p from CobolProgram p")
    Stream<CobolProgram> streamAll();

    @Query("select p from CobolProgram p where p.sourceType = :sourceType")
    Stream<CobolProgram> streamBySourceType(@Param("sourceType") String sourceType);


    // ======================================================
    // SourceType helpers
    // ======================================================

    List<CobolProgram> findBySourceType(String sourceType);

    long countBySourceType(String sourceType);

    @Modifying
    @Transactional
    void deleteBySourceType(String sourceType);


    // ======================================================
    // VALID programs (for batch reader alternative)
    // ======================================================

    @Query("""
           select p
           from CobolProgram p
           join CobolParseResult r on r.program.id = p.id
           where r.status = thesis.parser.ParseStatus.VALID
           order by p.id
           """)
    List<CobolProgram> findValidPrograms(Pageable pageable);


    // ======================================================
    // Debug helpers
    // ======================================================

    @Query("select count(p) from CobolProgram p")
    long totalPrograms();
}