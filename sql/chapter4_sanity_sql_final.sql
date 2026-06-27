-- =====================================================================
--  sanity_checks.sql
--  Sanity-/Plausibilitätsprüfungen für die gesamte Verarbeitungspipeline
--  COBOL-Geschäftsregelextraktion (S 1 – S 8)
--
--  Ausführung:   psql -h localhost -U cobol -d cobol_rules -f sanity_checks.sql
--  Konvention:   Jede Prüfung gibt das geprüfte Pipeline-Ergebnis (S x)
--                und eine erwartete Bedingung aus. "FAIL"-Zeilen weisen
--                auf eine verletzte Strukturinvariante hin.
-- =====================================================================


-- =====================================================================
-- S 2 – Mehrstufige Syntaxvalidierung   (Tabelle: S2_cobol_parse_results.csv)
--   Ziel: Jedes Programm besitzt genau ein Parsergebnis; Statusverteilung
--         ist konsistent; nur VALID geht in Phase II.
-- =====================================================================

--S2: Programme gesamt vs. Programme mit Parsergebnis
SELECT
    (SELECT count(*) FROM cobol_programs)      AS programs_total,
    (SELECT count(*) FROM cobol_parse_results) AS parse_results_total,
    CASE WHEN (SELECT count(*) FROM cobol_programs)
        = (SELECT count(*) FROM cobol_parse_results)
             THEN 'OK' ELSE 'FAIL: jedes Programm muss genau 1 Ergebnis haben' END AS status;

--S2: kein Programm ohne Parsergebnis (verwaiste programs)
SELECT count(*) AS programs_without_result,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'FAIL' END AS status
FROM cobol_programs p
         LEFT JOIN cobol_parse_results r ON r.program_id = p.id
WHERE r.id IS NULL;

--S2: 1:1-Constraint - kein Programm mit >1 Parsergebnis
SELECT count(*) AS programs_with_multiple_results,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'FAIL' END AS status
FROM (
         SELECT program_id FROM cobol_parse_results
         GROUP BY program_id HAVING count(*) > 1
     ) d;

--S2: Statusverteilung (entspricht Tabelle 4.1 der Thesis)
SELECT status, parse_mode, count(*) AS programs,
       round(100.0 * count(*) / SUM(count(*)) OVER (), 2) AS pct
FROM cobol_parse_results
GROUP BY status, parse_mode
ORDER BY programs DESC;


-- =====================================================================
-- S 3 – Erkennung von Regelkandidaten   (Tabelle: S3_cobol_rule_candidates.csv)
--   Ziel: Kandidaten existieren nur für VALID-Programme; jeder Kandidat
--         ist IF oder EVALUATE; Zeilenbereich ist gültig.
-- =====================================================================

--S3: alle Kandidaten stammen aus VALID-Programmen
SELECT count(*) AS candidates_from_invalid_program,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'FAIL' END AS status
FROM cobol_rule_candidates c
         LEFT JOIN cobol_parse_results r ON r.program_id = c.program_id
WHERE r.status IS DISTINCT FROM 'VALID';

--S3: nur erlaubte Regeltypen (IF / EVALUATE)
SELECT count(*) AS candidates_with_invalid_kind,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'FAIL' END AS status
FROM cobol_rule_candidates
WHERE kind NOT IN ('IF', 'EVALUATE');

--S3: Zeilenbereich plausibel (start <= end, > 0)
SELECT count(*) AS candidates_with_bad_lines,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'FAIL' END AS status
FROM cobol_rule_candidates
WHERE start_line IS NULL OR end_line IS NULL
   OR start_line < 1 OR end_line < start_line;

--S3: Verteilung nach Regeltyp
SELECT kind, count(*) AS candidates
FROM cobol_rule_candidates GROUP BY kind ORDER BY candidates DESC;


-- =====================================================================
-- S 4 – Strukturierte Extraktion        (Tabelle: S4_cobol_extracted_rules.csv)
--   Ziel: Jede IF-Regel hat Bedingung + THEN; jede EVALUATE-Regel hat
--         Subject + WHEN + Aktion; keine Aktion im Prädikat.
-- =====================================================================

--S4: Verteilung IF vs. EVALUATE (jeder WHEN-Zweig = eigene Regel)
SELECT rule_type, count(*) AS rules,
       round(100.0 * count(*) / SUM(count(*)) OVER (), 2) AS pct
FROM cobol_extracted_rules GROUP BY rule_type ORDER BY rules DESC;

--S4: IF-Regel ohne Bedingung oder ohne Aktion (THEN)
SELECT count(*) AS if_missing_condition_or_action,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'FAIL' END AS status
FROM cobol_extracted_rules
WHERE rule_type = 'IF'
  AND (condition_text IS NULL OR btrim(condition_text) = ''
    OR then_text     IS NULL OR btrim(then_text)      = '');

--S4: EVALUATE-Regel ohne Subject / WHEN / Aktionsblock =='
SELECT count(*) AS evaluate_missing_part,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'FAIL' END AS status
FROM cobol_extracted_rules
WHERE rule_type = 'EVALUATE'
  AND (subject_text IS NULL OR btrim(subject_text) = ''
    OR when_text   IS NULL OR btrim(when_text)    = ''
    OR then_text   IS NULL OR btrim(then_text)    = '');

--S4: tatsaechliche Aktion innerhalb eines EVALUATE-Praedikats ==
--(token-sensitiv: Wortgrenze, schliesst Bezeichner wie WS-MOVE-OUTCOME aus)
SELECT count(*) AS action_in_predicate,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'PRUEFEN' END AS status
FROM cobol_extracted_rules
WHERE rule_type = 'EVALUATE'
  AND when_text ~ '(^|[^[:alnum:]_-])(MOVE|PERFORM|COMPUTE|ADD|SUBTRACT|MULTIPLY|DIVIDE)([^[:alnum:]_-]|$)';

--S4: ELSE-Konsistenz - else_text vorhanden aber als has_else=false markiert =='
--(Hinweis: has_else wird erst in S5 gesetzt; hier nur Rohextraktion)'
SELECT count(*) AS rules_with_else_text
FROM cobol_extracted_rules
WHERE else_text IS NOT NULL AND btrim(else_text) <> '';


-- =====================================================================
-- S 5 – Syntaktische Normalisierung      (Tabelle: S5_cobol_normalized_rules.csv)
--   Ziel: Literale generalisiert (<N>/<S>/<ZERO>/<SPACE>); keine
--         Reststubs; ELSE-Zuordnung stimmig; Strukturfelder vollständig.
--   (Replikat der Qualitätsprüfungen aus Anhang A.2 der Thesis.)
-- =====================================================================

--S5: Gesamtzahl normalisierter Regeln =='
SELECT count(*) AS normalized_rules FROM cobol_normalized_rules;

--S5: nicht neutralisierte COPY-Direktiven in normalisierten Regeln =='
SELECT count(*) AS leftover_copy,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'FAIL' END AS status
FROM cobol_normalized_rules
WHERE coalesce(condition_norm,'') || coalesce(subject_norm,'') ||
      coalesce(when_norm,'')      || coalesce(then_norm,'')   ||
      coalesce(else_norm,'') ~* '(^|[^[:alnum:]_-])COPY([^[:alnum:]_-]|$)';

--S5: nicht neutralisierte eingebettete EXEC-Konstrukte =='
SELECT count(*) AS leftover_exec,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'FAIL' END AS status
FROM cobol_normalized_rules
WHERE coalesce(condition_norm,'') || coalesce(subject_norm,'') ||
      coalesce(when_norm,'')      || coalesce(then_norm,'')   ||
      coalesce(else_norm,'') ~* 'EXEC[[:space:]]+(SQL|CICS)';

--S5: EXEC_BLOCK_STUB innerhalb eines Regelpraedikats (Bedingung/Subject/WHEN) =='
SELECT count(*) AS stub_in_predicate,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'FAIL' END AS status
FROM cobol_normalized_rules
WHERE coalesce(condition_norm,'') || coalesce(subject_norm,'') ||
      coalesce(when_norm,'') LIKE '%EXEC_BLOCK_STUB%';

--S5: direkt verklebte EXEC_BLOCK_STUB-Marker ohne Trennzeichen
SELECT count(*) AS glued_stub,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'FAIL' END AS status
FROM cobol_normalized_rules
WHERE coalesce(then_norm,'') || coalesce(else_norm,'') ~ 'EXEC_BLOCK_STUB[^[:space:]]';

--S5: fehlerhafte Platzhalterstruktur (offene < ohne > o. ae.)
SELECT count(*) AS broken_placeholder,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'FAIL' END AS status
FROM cobol_normalized_rules
WHERE coalesce(condition_norm,'') || coalesce(subject_norm,'') ||
      coalesce(when_norm,'')      || coalesce(then_norm,'')   ||
      coalesce(else_norm,'') ~ '<[A-Za-z]*$|<>|<<|>>';

--S5: fehlende IF-Bedingung oder -Aktion
SELECT count(*) AS if_norm_incomplete,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'FAIL' END AS status
FROM cobol_normalized_rules
WHERE kind = 'IF'
  AND (condition_norm IS NULL OR btrim(condition_norm) = ''
    OR then_norm      IS NULL OR btrim(then_norm)      = '');

--S5: inkonsistente ELSE-Zuordnung (has_else widerspricht else_norm)
SELECT count(*) AS else_inconsistent,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'FAIL' END AS status
FROM cobol_normalized_rules
WHERE (has_else = TRUE  AND (else_norm IS NULL OR btrim(else_norm) = ''))
   OR (has_else = FALSE AND  else_norm IS NOT NULL AND btrim(else_norm) <> '');

--S5: fehlender EVALUATE-Subject, WHEN-Zweig oder Aktionsblock
SELECT count(*) AS eval_norm_incomplete,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'FAIL' END AS status
FROM cobol_normalized_rules
WHERE kind = 'EVALUATE'
  AND (subject_norm IS NULL OR btrim(subject_norm) = ''
    OR when_norm   IS NULL OR btrim(when_norm)    = ''
    OR then_norm   IS NULL OR btrim(then_norm)    = '');


-- =====================================================================
-- S 6 – Kanonisierung und Deduplizierung (Tabelle: S6_cobol_canonical_rules.csv)
--   Ziel: canonical_key 64-stelliger SHA-256; eindeutig; Summe der
--         occurrences == Anzahl normalisierter Regeln (keine verlorenen
--         Instanzen); keine Vermischung von kind / has_else.
-- =====================================================================

--S6: Anzahl kanonischer Muster und Reduktion
SELECT
    (SELECT count(*) FROM cobol_normalized_rules) AS normalized_rules,
    (SELECT count(*) FROM cobol_canonical_rules)  AS canonical_patterns,
    round(100.0 * (1 - (SELECT count(*) FROM cobol_canonical_rules)::numeric
                       / NULLIF((SELECT count(*) FROM cobol_normalized_rules),0)), 2)
                                                  AS reduction_pct;

--S6: canonical_key ist 64-stelliger Hex-Hash (SHA-256)
SELECT count(*) AS bad_key_format,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'FAIL' END AS status
FROM cobol_canonical_rules
WHERE canonical_key !~ '^[0-9a-f]{64}$';

--S6: canonical_key eindeutig (Unique-Constraint)
SELECT count(*) AS duplicate_keys,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'FAIL' END AS status
FROM (SELECT canonical_key FROM cobol_canonical_rules
      GROUP BY canonical_key HAVING count(*) > 1) d;

--S6: Summe occurrences == Anzahl normalisierter Regeln mit Schluessel
SELECT
    (SELECT coalesce(sum(occurrences),0) FROM cobol_canonical_rules)            AS sum_occurrences,
    (SELECT count(*) FROM cobol_normalized_rules WHERE canonical_key IS NOT NULL) AS normalized_with_key,
    CASE WHEN (SELECT coalesce(sum(occurrences),0) FROM cobol_canonical_rules)
        = (SELECT count(*) FROM cobol_normalized_rules WHERE canonical_key IS NOT NULL)
             THEN 'OK' ELSE 'FAIL: Regelinstanzen gingen bei Dedup verloren/doppelt' END AS status;

--S6: keine normalisierte Regel ohne canonical_key =='
SELECT count(*) AS normalized_without_key,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'FAIL' END AS status
FROM cobol_normalized_rules WHERE canonical_key IS NULL;

--S6: ein canonical_key mischt nicht kind oder has_else =='
SELECT count(*) AS key_with_mixed_kind_or_else,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'FAIL' END AS status
FROM (
         SELECT canonical_key
         FROM cobol_normalized_rules
         WHERE canonical_key IS NOT NULL
         GROUP BY canonical_key
         HAVING count(DISTINCT kind) > 1 OR count(DISTINCT has_else) > 1
     ) d;


-- =====================================================================
-- S 7 – Strukturelle Variablenabstraktion
--        (Tabelle: cobol_abstract_canonical_rules)
--   Ziel: abstract_canonical_key eindeutig; Platzhalter VAR1.. korrekt;
--         occurrences-Summe == normalisierte Regeln mit abstract_key;
--         abstrakte Muster <= kanonische Muster.
-- =====================================================================

--S7: Anzahl abstrakter Muster und Verdichtung gegenueber S6 =='
SELECT
    (SELECT count(*) FROM cobol_canonical_rules)          AS canonical_patterns,
    (SELECT count(*) FROM cobol_abstract_canonical_rules) AS abstract_patterns,
    CASE WHEN (SELECT count(*) FROM cobol_abstract_canonical_rules)
        <= (SELECT count(*) FROM cobol_canonical_rules)
             THEN 'OK' ELSE 'FAIL: abstrakt darf nicht mehr als kanonisch sein' END AS status;

--S7: abstract_canonical_key eindeutig =='
SELECT count(*) AS duplicate_abstract_keys,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'FAIL' END AS status
FROM (SELECT abstract_canonical_key FROM cobol_abstract_canonical_rules
      GROUP BY abstract_canonical_key HAVING count(*) > 1) d;

--S7: jede abstrakte Regel besitzt ein abstract_predicate =='
SELECT count(*) AS abstract_without_predicate,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'FAIL' END AS status
FROM cobol_abstract_canonical_rules
WHERE abstract_predicate IS NULL OR btrim(abstract_predicate) = '';

--S7: Platzhalter beginnen bei VAR1 und sind sequenziell (VAR ohne Ziffer ist Fehler) =='
SELECT count(*) AS broken_var_placeholder,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'FAIL' END AS status
FROM cobol_abstract_canonical_rules
WHERE coalesce(abstract_predicate,'') || coalesce(abstract_then,'') ||
      coalesce(abstract_else,'') ~ '(^|[^[:alnum:]])VAR([^0-9]|$)';

--S7: Summe occurrences == normalisierte Regeln mit abstract_canonical_key =='
SELECT
    (SELECT coalesce(sum(occurrences),0) FROM cobol_abstract_canonical_rules)            AS sum_occurrences,
    (SELECT count(*) FROM cobol_normalized_rules WHERE abstract_canonical_key IS NOT NULL) AS normalized_with_abs_key,
    CASE WHEN (SELECT coalesce(sum(occurrences),0) FROM cobol_abstract_canonical_rules)
        = (SELECT count(*) FROM cobol_normalized_rules WHERE abstract_canonical_key IS NOT NULL)
             THEN 'OK' ELSE 'FAIL' END AS status;


-- =====================================================================
-- S 8 – LLM-basierte semantische Annotation
--        (Tabellen: cobol_ai_rule_labels[_1.._3])
--   Ziel: jedes abstrakte Muster ist annotiert; keine internen Platz-
--         halter (VAR1/<N>/<S>) in Titel/Summary; gueltige Klassen;
--         Konfidenz in [0,1].
--   Hinweis: Tabellenname ggf. an _1/_2/_3 anpassen.
-- =====================================================================

--S8: Annotationsabdeckung - abstrakte Muster vs. annotierte Muster =='
SELECT
    (SELECT count(*) FROM cobol_abstract_canonical_rules) AS abstract_patterns,
    (SELECT count(*) FROM cobol_ai_rule_labels)           AS annotated_patterns,
    CASE WHEN (SELECT count(*) FROM cobol_ai_rule_labels)
        = (SELECT count(*) FROM cobol_abstract_canonical_rules)
             THEN 'OK (vollstaendig)' ELSE 'PRUEFEN (Teilabdeckung)' END AS status;

--S8: kein annotiertes Label ohne zugehoeriges abstraktes Muster =='
SELECT count(*) AS orphan_labels,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'FAIL' END AS status
FROM cobol_ai_rule_labels l
         LEFT JOIN cobol_abstract_canonical_rules a
                   ON a.abstract_canonical_key = l.abstract_canonical_key
WHERE a.abstract_canonical_key IS NULL;

--S8: gueltige Klassifikationskategorien =='
SELECT count(*) AS invalid_classification,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'FAIL' END AS status
FROM cobol_ai_rule_labels
WHERE pattern NOT IN ('IDIOM_PATTERN','CONTROL_FLOW_TEMPLATE',
                      'BUSINESS_RULE_CANDIDATE','DATA_VALIDATION','OTHER');

--S8: Konfidenz im gueltigen Bereich [0,1] =='
SELECT count(*) AS confidence_out_of_range,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'FAIL' END AS status
FROM cobol_ai_rule_labels
WHERE confidence IS NOT NULL AND (confidence < 0 OR confidence > 1);

--S8: keine internen Platzhalter (VAR1, <N>, <S>) in Titel/Summary =='
SELECT count(*) AS labels_with_internal_tokens,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'FAIL' END AS status
FROM cobol_ai_rule_labels
WHERE coalesce(title,'') || coalesce(rule_summary,'') ~ '(VAR[0-9]+|<N>|<S>|<ZERO>|<SPACE>)';

--S8: Klassifikationsverteilung (entspricht Tabelle 4.3) =='
SELECT pattern, count(*) AS labels,
       round(100.0 * count(*) / SUM(count(*)) OVER (), 2) AS pct
FROM cobol_ai_rule_labels GROUP BY pattern ORDER BY labels DESC;


-- =====================================================================
-- Monitoring – Laufzeitmetriken           (Tabelle: batch_metrics_3_runs.csv)
--   Ziel: jeder Step abgeschlossen (COMPLETED); keine Rollbacks.
-- =====================================================================

--Monitoring: Step-Status, Read/Write/Skip pro Step (letzter Lauf) =='
SELECT step_name, batch_status, read_count, write_count,
       read_skip_count, process_skip_count, write_skip_count,
       rollback_count, duration_ms
FROM batch_metrics
WHERE step_name IS NOT NULL
ORDER BY step_execution_id DESC;

--Monitoring: Steps ohne Status COMPLETED =='
SELECT count(*) AS not_completed_steps,
       CASE WHEN count(*) = 0 THEN 'OK' ELSE 'FAIL' END AS status
FROM batch_metrics
WHERE step_name IS NOT NULL AND batch_status <> 'COMPLETED';

