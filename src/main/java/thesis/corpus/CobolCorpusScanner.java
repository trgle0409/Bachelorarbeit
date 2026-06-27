package thesis.corpus;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class CobolCorpusScanner {

    private final CorpusProperties properties;
    private final CobolProgramRepository repository;

    public CobolCorpusScanner(CorpusProperties properties,
                              CobolProgramRepository repository) {
        this.properties = properties;
        this.repository = repository;
    }

    /**
     * Incremental mode: chỉ thêm file mới
     */
    public List<CobolProgram> scanAndPersistIncremental() throws Exception {
        List<CobolProgram> found = scanAll();
        List<CobolProgram> newOnes = new ArrayList<>();

        for (CobolProgram p : found) {
            if (!repository.existsByPath(p.getPath())) {
                newOnes.add(p);
            }
        }

        log.info("Incremental scan: Found={}, New={}", found.size(), newOnes.size());
        return repository.saveAll(newOnes);
    }

    /**
     * Full rebuild mode: xóa hết rồi insert lại toàn bộ
     */
    public List<CobolProgram> scanAndPersistFull() throws Exception {
        List<CobolProgram> found = scanAll();

        log.warn("Full corpus rebuild. Deleting existing cobol_programs.csv...");
        repository.deleteAllInBatch();

        log.warn("Inserting {} programs...", found.size());
        return repository.saveAll(found);
    }

    private List<CobolProgram> scanAll() throws Exception {
        List<CobolProgram> collector = new ArrayList<>();
        Path root = Paths.get(properties.getRootDir());

        scanFolder(root.resolve("x-cobol"), "XCOBOL", collector);
        scanFolder(root.resolve("genapp"), "GENAPP", collector);

        return collector;
    }

    private void scanFolder(Path folder,
                            String sourceType,
                            List<CobolProgram> collector) throws Exception {

        if (!Files.exists(folder)) {
            log.warn("Corpus folder not found: {}", folder);
            return;
        }

        log.info("Scanning folder: {} (source={})", folder, sourceType);

        try (var paths = Files.walk(folder)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String lower = path.toString().toLowerCase();
                        return lower.endsWith(".cbl")
                                || lower.endsWith(".cob")
                                || lower.endsWith(".cobol");
                    })
                    .forEach(path -> {
                        Path parent = path.getParent();
                        String systemName = parent != null
                                ? parent.getFileName().toString()
                                : "unknown";

                        CobolProgram program = CobolProgram.builder()
                                .systemName(systemName)
                                .path(path.toAbsolutePath().normalize().toString())
                                .sourceType(sourceType)
                                .build();

                        collector.add(program);
                    });
        }
    }
}