package thesis.rules;

import lombok.extern.slf4j.Slf4j;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.AbstractParseTreeVisitor;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.RuleNode;
import thesis.parser.ParseMode;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class CobolRuleCandidateVisitor extends AbstractParseTreeVisitor<Void> {

    private final Long programId;
    private final ParseMode mode;
    private final CommonTokenStream tokens;

    private final List<RuleCandidate> out = new ArrayList<>();

    public CobolRuleCandidateVisitor(Long programId, ParseMode mode, CommonTokenStream tokens) {
        this.programId = programId;
        this.mode = mode;
        this.tokens = tokens;
    }

    public List<RuleCandidate> candidates() {
        return out;
    }

    @Override
    public Void visit(ParseTree tree) {
        return super.visit(tree);
    }

    @Override
    public Void visitChildren(RuleNode node) {
        if (node instanceof ParserRuleContext ctx) {
            String ruleName = ctx.getClass().getSimpleName().toLowerCase();

            if (isIf(ruleName)) {
                out.add(build(RuleKind.IF, ctx));
            } else if (isEvaluate(ruleName)) {
                out.add(build(RuleKind.EVALUATE, ctx));
            }
        }
        return super.visitChildren(node);
    }

    private boolean isIf(String ruleName) {
        // tune after first run if needed
        return ruleName.contains("if") && ruleName.contains("statement");
    }

    private boolean isEvaluate(String ruleName) {
        return ruleName.contains("evaluate") && ruleName.contains("statement");
    }

    private RuleCandidate build(RuleKind ruleKind, ParserRuleContext ctx) {
        int startLine = ctx.getStart() != null ? ctx.getStart().getLine() : -1;
        int endLine = ctx.getStop() != null ? ctx.getStop().getLine() : startLine;

        String snippet = extractSnippet(ctx);
        return new RuleCandidate(programId, mode, ruleKind, startLine, endLine, snippet);
    }

    private String extractSnippet(ParserRuleContext ctx) {
        if (tokens == null || ctx.getStart() == null || ctx.getStop() == null) {
            return ctx.getText();
        }
        // keeps whitespace better than ctx.getText()
        return tokens.getText(ctx.getSourceInterval());
    }
}