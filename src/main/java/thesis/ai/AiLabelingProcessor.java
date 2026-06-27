package thesis.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;
import thesis.rules.CobolAbstractCanonicalRuleEntity;
import thesis.rules.CobolNormalizedRuleRepository;
import thesis.rules.NormalizedRuleExampleView;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiLabelingProcessor implements ItemProcessor<CobolAbstractCanonicalRuleEntity, CobolAiRuleLabel> {

    private final CobolNormalizedRuleRepository normalizedRuleRepository;
    private final CobolAiRuleLabelRepository aiRuleLabelRepository;
    private final OpenAiClient openAiClient;
    private final OpenAiProperties openAiProperties;
    private final AiLabelingProperties aiProps;
    private final ObjectMapper om = new ObjectMapper();

    private static final Pattern INTERNAL_OUTPUT_TOKEN = Pattern.compile(
            "(?i)(?<![A-Z0-9_-])VAR\\d+(?![A-Z0-9_-])"
                    + "|<(?:N|S|SPACE|ZERO)>"
                    + "|(?<![A-Z0-9_-])(?:EXEC_BLOCK_STUB|COPY_STUB)(?![A-Z0-9_-])"
    );

    @Override
    public CobolAiRuleLabel process(CobolAbstractCanonicalRuleEntity keyRow) throws Exception {
        String key = keyRow.getAbstractCanonicalKey();

        log.info("AI labeling start key={} kind={} occurrences={}",
                key, keyRow.getKind(), keyRow.getOccurrences());

        List<NormalizedRuleExampleView> examples = normalizedRuleRepository
                .findExamplesForAbstractKey(key, aiProps.examplesPerKey());

        if (examples.isEmpty()) {
            log.warn("AI labeling skipped key={} because no examples were found", key);
            return null;
        }

        validateAbstractRule(keyRow);

        if (aiRuleLabelRepository.existsById(key)) {
            log.info("AI labeling skipped key={} because label already exists", key);
            return null;
        }

        String prompt = buildPrompt(keyRow, examples);

        AiRuleLabel label = classifyWithOutputGuardrail(key, prompt);

        OffsetDateTime now = OffsetDateTime.now();

        return CobolAiRuleLabel.builder()
                .abstractCanonicalKey(key)
                .kind(keyRow.getKind().name())
                .title(nullToEmpty(label.title()))
                .ruleSummary(nullToEmpty(label.ruleSummary()))
                .pattern(nullToEmpty(label.pattern()))
                .confidence(label.confidence())
                .model(openAiProperties.model())
                .promptVersion(openAiProperties.promptVersion())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }


    private String buildPrompt(CobolAbstractCanonicalRuleEntity keyRow,
                               List<NormalizedRuleExampleView> examples) {

        StringBuilder sb = new StringBuilder();

        sb.append("""
            Rolle: Du bist Experte für COBOL-Wartung und Legacy-Modernisierung.
            Annotiere ein extrahiertes Entscheidungsregel-Muster aus COBOL-Programmen.
        
            Ziel:
            Erzeuge eine kurze menschenlesbare Annotation für ein
            strukturelles Regelmuster. Die konkrete semantische Interpretation
            darf ausschließlich auf den unten gezeigten COBOL-Quelltextbelegen
            beruhen.
        
            INTERNE STRUKTURDARSTELLUNG:
            Die abstrahierten Felder dienen ausschließlich zur Erkennung
            struktureller Gemeinsamkeiten. Sie dürfen nicht als
            benutzerorientierte Beschreibung übernommen werden.
            
            Repräsentation:
            - Zahlenliterale wurden durch <N> ersetzt.
            - Stringliterale wurden durch <S> ersetzt.
            - ZEROS/ZERO wurden durch <ZERO> ersetzt.
            - SPACES/SPACE wurden durch <SPACE> ersetzt.
            - Konkrete Variablenbezeichner im abstrakten Muster wurden durch
              VAR1, VAR2, ... ersetzt.
            - EXEC_BLOCK_STUB bezeichnet einen technisch neutralisierten
              eingebetteten EXEC-SQL- oder EXEC-CICS-Block. Daraus darf keine
              Fachsemantik abgeleitet werden.
        
            ZWINGENDE AUSGABEREGELN:
            1. Verwende in "title" und "rule_summary" niemals interne
               Abstraktionsbezeichner wie VAR1, VAR2, VAR3 usw.
            2. Verwende in "title" und "rule_summary" niemals Platzhalter
              oder technische Marker wie <N>, <S>, <SPACE>, <ZERO>,
              EXEC_BLOCK_STUB, COPY_STUB.
            3. Verwende nur Begriffe und fachliche Hinweise, die durch die
               gezeigten konkreten COBOL-Quelltextausschnitte belegt sind.
            4. Erfinde keine Prüfung, Funktion oder fachliche Bedeutung,
               die im Quelltext nicht sichtbar ist.
            5. Wenn die konkreten Ausschnitte keine belastbare fachliche
               Interpretation erlauben, formuliere neutral und wähle nicht
               BUSINESS_RULE_CANDIDATE allein aufgrund der Struktur.
            6. Bei unterschiedlichen Bedeutungen mehrerer Quelltextbeispiele
               desselben abstrakten Musters ist die Unsicherheit ausdrücklich
               zu berücksichtigen; im Zweifel ist OTHER angemessen.
               
            Antworte ausschließlich mit JSON in genau diesem Format:
            {
              "title":        "...",
              "rule_summary": "...",
              "pattern":      "...",
              "confidence":   0.0
            }

            Anforderungen an die Felder:
            - "title": maximal 8 Wörter, Deutsch.
            - "rule_summary": maximal 3 kurze Sätze, Deutsch.
            - "confidence": Zahl zwischen 0.0 und 1.0.
            - "pattern": genau eine der folgenden Kategorien:
        
            IDIOM_PATTERN
              Lokales technisches COBOL-Idiom ohne eigenständige fachliche
              Entscheidung.
              Beispiele: Status- oder Flag-Prüfung, Zähler-, Byte- oder
              Stringoperation, einfache SQLCODE- oder File-Status-Behandlung,
              Initialisierung oder technische Wertweitergabe.    
        
            DATA_VALIDATION
              Explizite Prüfung von Eingaben oder Pflicht-, Format- oder
              Wertebereichsbedingungen vor der Verarbeitung.
              Beispiele: NUMERIC-Prüfung, leeres Pflichtfeld, ungültiger
              Eingabecode oder Validierung einer Benutzereingabe.
        
            BUSINESS_RULE_CANDIDATE
              Potenziell fachliche Entscheidung über einen Geschäftsfall oder
              eine fachliche Größe. Diese Klasse erfordert mehr als nur fachlich
              klingende Feldnamen: Bedingung oder Aktion müssen eine fachliche
              Auswahl, Berechnung, Zulässigkeit oder Statusentscheidung erkennen
              lassen. Reine Datenübertragung, Maskenverarbeitung oder technische
              Fehlerbehandlung mit POLICY-, CUSTOMER- oder ACCOUNT-Feldern genügt
              nicht.
        
            CONTROL_FLOW_TEMPLATE
              Technisches Routing, Dispatching oder Fehler- beziehungsweise
              Ablaufsteuerung.
              Beispiele: Auswahl technischer Verarbeitungsroutinen über PERFORM
              oder GO TO, Return-Code-gesteuerte Fehlerpfade, CICS- oder
              Dateiverarbeitung und Request-ID-gesteuerte Aufrufe.
        
            OTHER
              Keine der Klassen lässt sich auf Grundlage des vorliegenden Codes
              zuverlässig begründen.
        
            Entscheidungsvorgehen:
            1. Prüft das Muster explizit die Gültigkeit, Vollständigkeit
              oder Form einer realen Eingabe vor ihrer Verarbeitung?
              Dann DATA_VALIDATION.
            2. Drückt das Muster eine belegbare fachliche Entscheidung oder
               Berechnung aus, nicht nur Datenbewegung mit fachlich benannten
               Feldern?
               Dann BUSINESS_RULE_CANDIDATE.
            3. Steuert das Muster primär technische Ablaufpfade, Handler oder
               Programmroutinen?
               Dann CONTROL_FLOW_TEMPLATE.
            4. Handelt es sich um eine lokale technische Prüfung oder Operation
               ohne fachliche Entscheidung?
               Dann IDIOM_PATTERN.
            5. Andernfalls OTHER.
        
            Abgrenzungsregeln:
            - DATA_VALIDATION prüft fehlerhafte oder unzulässige Eingaben.
            - BUSINESS_RULE_CANDIDATE trifft eine fachliche Entscheidung
              oder Berechnung auf Basis gültiger Daten.
            - CONTROL_FLOW_TEMPLATE beschreibt eine erkennbare technische
              Ablaufstruktur mit mehreren Verarbeitungspfaden.
            - IDIOM_PATTERN beschreibt ein kompaktes technisches
              Standardmuster ohne ausgeprägtes Routing.
        
            Regeln für die Antwort:
            - Erfinde keinen fachlichen Kontext.
            - Feldnamen allein sind kein ausreichender Nachweis für eine
              Geschäftsregel.
            - Bewerte EXEC_BLOCK_STUB ausschließlich als technischen Platzhalter.
            - Beschreibe verschachtelte Bedingungen nur als Bestandteil der Aktion
              des äußeren Musters.
            - Verwende eine hohe confidence nur, wenn Klasse und Begründung
              eindeutig aus dem Code hervorgehen.
            - Gib keinen Text außerhalb des JSON-Objekts aus.
            """);

        sb.append("\n---\n");
        sb.append("INTERNE STRUKTUR DES MUSTERS: ");
        sb.append("(nicht in Titel oder Zusammenfassung wiederholen):\n\n");

        sb.append("kind: ").append(keyRow.getKind()).append("\n");
        sb.append("has_else: ").append(Boolean.TRUE.equals(keyRow.getHasElse())).append("\n");

        if (keyRow.getAbstractPredicate() != null) {
            sb.append("abstract_predicate: ").append(keyRow.getAbstractPredicate()).append("\n");
        }
        if (keyRow.getAbstractThen() != null) {
            sb.append("abstract_then: ").append(keyRow.getAbstractThen()).append("\n");
        }
        if (keyRow.getAbstractElse() != null) {
            sb.append("abstract_else: ").append(keyRow.getAbstractElse()).append("\n");
        }

        sb.append("\nVORVERARBEITETE KONKRETE COBOL-QUELLTEXTBELEGE: ");
        sb.append("(einzige Grundlage für semantische Aussagen):\n");

        int exampleNo = 1;
        for (NormalizedRuleExampleView example : examples) {
            String snippet = cleanEvidenceSnippet(example.getRawSnippet());

            if (snippet == null || snippet.isBlank()) {
                continue;
            }

            sb.append("\nBeispiel ")
                    .append(exampleNo++)
                    .append(":\n```cobol\n");

            sb.append(snippet);

            sb.append("\n```\n");
        }

        return sb.toString();
    }

    private String cleanEvidenceSnippet(String rawSnippet) {
        if (rawSnippet == null || rawSnippet.isBlank()) {
            return null;
        }

        String snippet = rawSnippet.trim();

        /*
         * Make parser-safe preprocessing artifacts explicit as technical
         * placeholders instead of letting the model interpret them as
         * original business actions.
         */
        snippet = snippet.replaceAll(
                "(?m)^\\s*\\*>\\s*(EXEC BLOCK STUB|COPY STUB)\\.?\\s*$\\n?",
                ""
        );

        snippet = snippet.trim();

        return snippet.isBlank() ? null : snippet;
    }

    private String nullSafe(String s) {
        return (s == null || s.isBlank()) ? "<EMPTY>" : s;
    }

    private String nullToEmpty(String s) {
        return (s == null) ? "" : s;
    }

    private void validateAbstractRule(CobolAbstractCanonicalRuleEntity keyRow) {
        boolean hasElse = Boolean.TRUE.equals(keyRow.getHasElse());

        if (keyRow.getAbstractPredicate() == null
                || keyRow.getAbstractPredicate().isBlank()) {
            throw new IllegalStateException(
                    "Missing abstractPredicate for key=" + keyRow.getAbstractCanonicalKey()
            );
        }

        if (keyRow.getAbstractThen() == null
                || keyRow.getAbstractThen().isBlank()) {
            throw new IllegalStateException(
                    "Missing abstractThen for key=" + keyRow.getAbstractCanonicalKey()
            );
        }

        if (hasElse
                && (keyRow.getAbstractElse() == null
                || keyRow.getAbstractElse().isBlank())) {
            throw new IllegalStateException(
                    "Missing abstractElse for key=" + keyRow.getAbstractCanonicalKey()
            );
        }

        if (!hasElse
                && keyRow.getAbstractElse() != null
                && !keyRow.getAbstractElse().isBlank()) {
            throw new IllegalStateException(
                    "Unexpected abstractElse for key=" + keyRow.getAbstractCanonicalKey()
            );
        }
    }

    private static final Set<String> ALLOWED_PATTERNS = Set.of(
            "IDIOM_PATTERN",
            "CONTROL_FLOW_TEMPLATE",
            "BUSINESS_RULE_CANDIDATE",
            "DATA_VALIDATION",
            "OTHER"
    );

    private void validateLabel(AiRuleLabel label) {
        if (label == null) {
            throw new IllegalArgumentException("AI response is null");
        }

        if (label.title() == null || label.title().isBlank()) {
            throw new IllegalArgumentException("AI title is missing");
        }

        int titleWords = label.title().trim().split("\\s+").length;
        if (titleWords > 8) {
            throw new IllegalArgumentException(
                    "AI title exceeds 8 words: " + label.title()
            );
        }

        if (label.ruleSummary() == null || label.ruleSummary().isBlank()) {
            throw new IllegalArgumentException("AI rule_summary is missing");
        }

        if (!ALLOWED_PATTERNS.contains(label.pattern())) {
            throw new IllegalArgumentException(
                    "Invalid AI pattern: " + label.pattern()
            );
        }

        if (label.confidence() < 0.0 || label.confidence() > 1.0) {
            throw new IllegalArgumentException(
                    "Invalid AI confidence: " + label.confidence()
            );
        }
    }

    private AiRuleLabel classifyWithOutputGuardrail(
            String key,
            String prompt) throws Exception {

        log.info("Calling OpenAI for key={} promptChars={}", key, prompt.length());

        String json = openAiClient.classifyRuleAsJson(prompt);

        log.info("OpenAI returned for key={} responseChars={}", key, json.length());

        AiRuleLabel label = om.readValue(json, AiRuleLabel.class);
        validateLabel(label);

        if (!containsInternalOutputToken(label)) {
            return label;
        }

        log.warn("AI output for key={} contains internal abstraction token; retrying once", key);

        String correctionPrompt = prompt + """

            
            KORREKTURHINWEIS:
            Die vorherige Ausgabe enthielt interne Abstraktionsbezeichner
            oder Normalisierungsplatzhalter. Erzeuge die Annotation erneut.

            Zwingende Regeln:
            - In "title" und "rule_summary" niemals VAR1, VAR2, VAR3 usw. verwenden.
            - In "title" und "rule_summary" niemals <N>, <S>, <SPACE> oder <ZERO> verwenden.
            - Verwende stattdessen ausschließlich konkrete Begriffe, die in den
              gezeigten COBOL-Quelltextbelegen sichtbar sind.
            - Falls keine konkrete semantische Benennung möglich ist, formuliere neutral.
            - Antworte erneut ausschließlich mit gültigem JSON.
            """;

        String correctedJson = openAiClient.classifyRuleAsJson(correctionPrompt);
        AiRuleLabel correctedLabel = om.readValue(correctedJson, AiRuleLabel.class);

        validateLabel(correctedLabel);
        validateNoInternalOutputTokens(correctedLabel);

        return correctedLabel;
    }

    private boolean containsInternalOutputToken(AiRuleLabel label) {
        String humanReadableOutput =
                nullToEmpty(label.title()) + "\n" + nullToEmpty(label.ruleSummary());

        return INTERNAL_OUTPUT_TOKEN.matcher(humanReadableOutput).find();
    }

    private void validateNoInternalOutputTokens(AiRuleLabel label) {
        if (containsInternalOutputToken(label)) {
            throw new IllegalArgumentException(
                    "Human-readable AI output still contains internal abstraction tokens: "
                            + label.title() + " | " + label.ruleSummary()
            );
        }
    }
}