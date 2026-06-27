package thesis.corpus;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;


/**
 * Đọc cấu hình corpus.root-dir
 */
@Component
@ConfigurationProperties(prefix = "corpus")
public class CorpusProperties {
    /**
     * Thư mục gốc chứa các corpus, ví dụ: /Users/.../BachelorarbeitHDA/corpus
     */
    private String rootDir;

    public String getRootDir() {
        return rootDir;
    }

    public void setRootDir(String rootDir) {
        this.rootDir = rootDir;
    }
}
