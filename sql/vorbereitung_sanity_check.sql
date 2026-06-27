-- =============================================================================
-- vorverarbeitung_sanity_checks.sql
-- Nachweisabfragen zum Anhang:
--   "Übersicht der Vorverarbeitungstransformationen"
--
-- Zweck:
--   Rekonstruktion der SQL-basierten Befunde im Abschnitt
--   "Ergänzende Qualitätsprüfungen persistierter Verarbeitungsergebnisse".
--
-- Wichtige Abgrenzung:
--   Die Tabelle der Vorverarbeitungskategorien (Gruppe A / Gruppe B)
--   dokumentiert implementierte bzw. bewusst nicht implementierte
--   Codebehandlungen. Da der nach S 1 bereinigte Quelltext nicht als eigener
--   Zwischenstand persistiert wird, kann diese Tabelle nicht aus der
--   Datenbank rekonstruiert werden. Die folgenden Queries prüfen ausschließlich
--   persistierte Parser- und Regelergebnisse.
--
-- Ausgabedateien:
--   results/ergebnis/vorverarbeitung_sanity_checks.csv
--   results/ergebnis/vorverarbeitung_exec_stub_befund.csv       (optional)
--   results/ergebnis/parserfehler_anzahl.csv                     (optional)
--   results/ergebnis/parserfehler_gesamt_fuer_sichtung.csv       (optional)
--
-- Voraussetzung:
--   - PostgreSQL / psql
--   - Die CSV-Exporte aus results/pipeline/ wurden unter folgenden Namen
--     importiert:
--       cobol_programs
--       cobol_parse_results
--       cobol_normalized_rules
--
-- Hinweis:
--   Falls die Statusspalte in cobol_parse_results in Ihrem Import
--   "parse_status" statt "status" heißt, im Parserfehler-Block
--   "status" durch "parse_status" ersetzen.
-- =============================================================================

\set ON_ERROR_STOP on


-- =============================================================================
-- 0. Schema-Kontrolle
-- =============================================================================

SELECT table_name, ordinal_position, column_name, data_type
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name IN (
                     'cobol_programs',
                     'cobol_parse_results',
                     'cobol_normalized_rules'
    )
ORDER BY table_name, ordinal_position;

/*
ERWARTETES ERGEBNIS:
Die drei Tabellen sind vorhanden.

Für die Qualitätsprüfungen werden aus cobol_normalized_rules benötigt:
- kind
- has_else
- condition_norm
- subject_norm
- when_norm
- then_norm
- else_norm

Für den Parserfehler-Befund wird aus cobol_parse_results benötigt:
- status (oder alternativ parse_status)
*/


-- =============================================================================
-- 1. Tabelle im Anhang: Qualitätsprüfungen persistierter
--    normalisierter Regeln
-- =============================================================================

DROP VIEW IF EXISTS export_vorverarbeitung_sanity_checks;
CREATE TEMP VIEW export_vorverarbeitung_sanity_checks AS
WITH r AS (
    SELECT
        *,
        CONCAT_WS(' ', condition_norm, subject_norm, when_norm) AS predicate_text,
        CONCAT_WS(' ', then_norm, else_norm) AS action_text,
        CONCAT_WS(' ',
            condition_norm,
            subject_norm,
            when_norm,
            then_norm,
            else_norm
        ) AS whole_text
    FROM cobol_normalized_rules
),
checks AS (
    SELECT
        1 AS sort_order,
        'Direkt verklebte EXEC_BLOCK_STUB-Marker ohne Trennzeichen'::text AS pruefung,
        COUNT(*)::bigint AS treffer
    FROM r
    WHERE action_text ~* 'EXEC_BLOCK_STUBEXEC_BLOCK_STUB'

    UNION ALL
    SELECT
        2,
        'EXEC_BLOCK_STUB innerhalb eines Regelpraedikats',
        COUNT(*)::bigint
    FROM r
    WHERE predicate_text ~* 'EXEC_BLOCK_STUB'

    UNION ALL
    SELECT
        3,
        'Nicht neutralisierte COPY-Direktiven in normalisierten Regeln',
        COUNT(*)::bigint
    FROM r
    WHERE whole_text ~* '(^|[[:space:]])COPY([[:space:]]|[.]|$)'

    UNION ALL
    SELECT
        4,
        'Nicht neutralisierte eingebettete EXEC-Konstrukte in normalisierten Regeln',
        COUNT(*)::bigint
    FROM r
    WHERE whole_text ~* '(^|[[:space:]])EXEC[[:space:]]+(SQL|CICS)([[:space:]]|$)|END-EXEC'

    UNION ALL
    SELECT
        5,
        'Fehlerhafte Platzhalterstruktur',
        COUNT(*)::bigint
    FROM r
    WHERE whole_text ~ '(<N[^>]|<S[^>]|<SPACE[^>]|<ZERO[^>]|<<|>>)'

    UNION ALL
    SELECT
        6,
        'Fehlende IF-Bedingung oder Aktion',
        COUNT(*)::bigint
    FROM r
    WHERE kind::text = 'IF'
      AND (
            NULLIF(BTRIM(condition_norm), '') IS NULL
         OR NULLIF(BTRIM(then_norm), '') IS NULL
      )

    UNION ALL
    SELECT
        7,
        'Inkonsistente ELSE-Zuordnung',
        COUNT(*)::bigint
    FROM r
    WHERE (has_else = TRUE
           AND NULLIF(BTRIM(else_norm), '') IS NULL)
       OR (has_else = FALSE
           AND NULLIF(BTRIM(else_norm), '') IS NOT NULL)

    UNION ALL
    SELECT
        8,
        'Fehlender EVALUATE-Subject, WHEN-Zweig oder Aktionsblock',
        COUNT(*)::bigint
    FROM r
    WHERE kind::text = 'EVALUATE'
      AND (
            NULLIF(BTRIM(subject_norm), '') IS NULL
         OR NULLIF(BTRIM(when_norm), '') IS NULL
         OR NULLIF(BTRIM(then_norm), '') IS NULL
      )

    UNION ALL
    SELECT
        9,
        'Tatsaechliche Aktion innerhalb eines EVALUATE-Praedikats',
        COUNT(*)::bigint
    FROM r
    WHERE kind::text = 'EVALUATE'
      AND predicate_text ~*
          '(^|[[:space:]])(MOVE|PERFORM|ADD|SUBTRACT|MULTIPLY|DIVIDE|COMPUTE|DISPLAY|CALL)([[:space:]]|$)|(^|[[:space:]])GO[[:space:]]+TO([[:space:]]|$)'
)
SELECT
    sort_order,
    pruefung,
    treffer,
    CASE
        WHEN treffer = 0 THEN 'unauffaellig'
        ELSE 'manuell pruefen'
        END AS einordnung
FROM checks;

SELECT pruefung, treffer, einordnung
FROM export_vorverarbeitung_sanity_checks
ORDER BY sort_order;

/*
ERWARTETES ERGEBNIS:
Alle neun Prüfungen liefern Treffer = 0 und Einordnung = unauffaellig:

1 Direkt verklebte EXEC_BLOCK_STUB-Marker ohne Trennzeichen        | 0
2 EXEC_BLOCK_STUB innerhalb eines Regelpraedikats                  | 0
3 Nicht neutralisierte COPY-Direktiven in normalisierten Regeln    | 0
4 Nicht neutralisierte eingebettete EXEC-Konstrukte                | 0
5 Fehlerhafte Platzhalterstruktur                                  | 0
6 Fehlende IF-Bedingung oder Aktion                                | 0
7 Inkonsistente ELSE-Zuordnung                                     | 0
8 Fehlender EVALUATE-Subject, WHEN-Zweig oder Aktionsblock         | 0
9 Tatsaechliche Aktion innerhalb eines EVALUATE-Praedikats         | 0

Die letzte Prüfung verwendet token-sensitive Abgrenzungen und wertet
Bezeichner wie WS-MOVE-OUTCOME nicht fälschlich als MOVE-Anweisung.
*/

\copy (SELECT pruefung, treffer, einordnung FROM export_vorverarbeitung_sanity_checks ORDER BY sort_order) TO 'results/ergebnis/vorverarbeitung_sanity_checks.csv' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8');


-- =============================================================================
-- 2. Deskriptiver Befund im Text: unmittelbar aufeinanderfolgende
--    EXEC_BLOCK_STUB-Aktionen
-- =============================================================================

DROP VIEW IF EXISTS export_vorverarbeitung_exec_stub_befund;
CREATE TEMP VIEW export_vorverarbeitung_exec_stub_befund AS
WITH r AS (
    SELECT
        *,
        CONCAT_WS(' ', then_norm, else_norm) AS action_text
    FROM cobol_normalized_rules
)
SELECT *
FROM r
WHERE action_text ~* 'EXEC_BLOCK_STUB[[:space:]]+EXEC_BLOCK_STUB';

SELECT COUNT(*) AS aufeinanderfolgende_exec_block_stub_aktionen
FROM export_vorverarbeitung_exec_stub_befund;

/*
ERWARTETES ERGEBNIS:
aufeinanderfolgende_exec_block_stub_aktionen = 30

INTERPRETATION:
Dieser Wert stützt den Absatz nach der Tabelle im Anhang.
Die 30 Fälle sind ein deskriptiver Befund und werden nicht als
Strukturfehler gewertet.
*/

-- Optionaler Export der 30 Fälle zur manuellen Sichtung:
\copy (SELECT * FROM export_vorverarbeitung_exec_stub_befund) TO 'results/ergebnis/vorverarbeitung_exec_stub_befund.csv' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8');


-- =============================================================================
-- 3. Kontrollabfrage: direkt verklebte Marker versus getrennte Stubs
-- =============================================================================
-- Dieser Block verdeutlicht die Abgrenzung zwischen Strukturfehler und dem
-- zulässigen deskriptiven Befund.

WITH actions AS (
    SELECT CONCAT_WS(' ', then_norm, else_norm) AS action_text
    FROM cobol_normalized_rules
)
SELECT
    COUNT(*) FILTER (
        WHERE action_text ~* 'EXEC_BLOCK_STUBEXEC_BLOCK_STUB'
    ) AS direkt_verklebt_ohne_trennzeichen,
    COUNT(*) FILTER (
        WHERE action_text ~* 'EXEC_BLOCK_STUB[[:space:]]+EXEC_BLOCK_STUB'
    ) AS getrennte_aufeinanderfolgende_stubs
FROM actions;

/*
ERWARTETES ERGEBNIS:
direkt_verklebt_ohne_trennzeichen = 0
getrennte_aufeinanderfolgende_stubs = 30
*/


-- =============================================================================
-- 4. Anzahl verbleibender PARSER_ERROR-Fälle
-- =============================================================================

DROP VIEW IF EXISTS export_parserfehler_anzahl;
CREATE TEMP VIEW export_parserfehler_anzahl AS
SELECT
    status::text AS status,
    COUNT(*)::bigint AS anzahl
FROM cobol_parse_results
WHERE status::text = 'PARSER_ERROR'
GROUP BY status;

SELECT status, anzahl
FROM export_parserfehler_anzahl;

/*
ERWARTETES ERGEBNIS:
PARSER_ERROR | 258

INTERPRETATION:
Die Abfrage bestätigt die Anzahl der verbliebenen Parserfehler.
Die im Fließtext genannten Ursachen wurden stichprobenartig gesichtet;
eine vollständige quantitative Ursachenklassifikation aller 258 Fälle
wird in der Arbeit ausdrücklich nicht beansprucht.
*/

\copy (SELECT status, anzahl FROM export_parserfehler_anzahl) TO 'results/ergebnis/parserfehler_anzahl.csv' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8');


-- =============================================================================
-- 5. Parserfehlerfälle zur ergänzenden manuellen Sichtung
-- =============================================================================
-- Dieser Export erzeugt bewusst KEINE statistische Ursachenklassifikation.
-- Er stellt die vorhandenen Parserfehlerfälle für eine nachvollziehbare
-- manuelle Auswahl bzw. Sichtung bereit.
--
-- Falls cobol_parse_results keine program_id-Spalte oder cobol_programs
-- abweichende Pfadfelder besitzt, den JOIN gemäß Schema-Kontrolle anpassen.

SELECT
    pr.*,
    p.path
FROM cobol_parse_results pr
         LEFT JOIN cobol_programs p
                   ON p.id = pr.program_id
WHERE pr.status::text = 'PARSER_ERROR'
ORDER BY p.path;

/*
ERWARTETES ERGEBNIS:
258 Zeilen mit PARSER_ERROR-Fällen, sofern pro Korpuseinheit genau ein
Parse-Ergebnis persistiert wurde.

HINWEIS FÜR DIE ABGABE:
Die bereits beigefügte Datei parserfehler_stichprobe.csv sollte nur dann
als Stichprobe bezeichnet werden, wenn sie tatsächlich eine bewusst
ausgewählte Teilmenge dieser Fehlerfälle enthält. Sie ist nicht als
vollständige Häufigkeitstabelle der Fehlerursachen zu interpretieren.
*/

-- Optional: Vollständiger Export aller Parserfehlerfälle.
-- \copy (
--     SELECT pr.*, p.path
--     FROM cobol_parse_results pr
--     LEFT JOIN cobol_programs p ON p.id = pr.program_id
--     WHERE pr.status::text = 'PARSER_ERROR'
--     ORDER BY p.path
-- ) TO 'results/ergebnis/parserfehler_gesamt_fuer_sichtung.csv'
-- WITH (FORMAT csv, HEADER true, ENCODING 'UTF8');


-- =============================================================================
-- 6. Optionaler breiter Diagnosetest für verklebte EXEC_BLOCK_STUB-Tokens
-- =============================================================================
-- Nicht Bestandteil der im Anhang berichteten Tabelle. Dieser Diagnosetest
-- kann ergänzend prüfen, ob der Marker unmittelbar an sonstige alphanumerische
-- Token angefügt wurde.

WITH r AS (
    SELECT CONCAT_WS(' ', then_norm, else_norm) AS action_text
    FROM cobol_normalized_rules
)
SELECT COUNT(*) AS exec_stub_an_andere_token_verklebt
FROM r
WHERE action_text ~* 'EXEC_BLOCK_STUB[A-Z0-9_(]'
   OR action_text ~* '[A-Z0-9_)]EXEC_BLOCK_STUB';

/*
ERWARTETES ERGEBNIS:
Diese optionale Diagnose ist nicht Teil der im Anhang berichteten
Ergebnistabelle. Nur aufnehmen oder dokumentieren, wenn sie ausgeführt
und das Resultat tatsächlich geprüft wurde.
*/


-- =============================================================================
-- Ende
-- =============================================================================
