package thesis.rules;

public interface NormalizedRuleExampleView {

    Long getProgramId();

    Integer getStartLine();

    Integer getEndLine();

    String getKind();

    String getConditionNorm();

    String getSubjectNorm();

    String getWhenNorm();

    String getThenNorm();

    String getElseNorm();
    String getRawSnippet();
}