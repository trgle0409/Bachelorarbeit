package thesis.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiLabelingWriter implements ItemWriter<CobolAiRuleLabel> {

    private final CobolAiRuleLabelRepository repo;

    @Override
    public void write(Chunk<? extends CobolAiRuleLabel> chunk) {
        repo.saveAll(chunk.getItems());
    }
}