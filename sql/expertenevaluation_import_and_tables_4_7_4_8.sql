-- =============================================================================
-- expertenevaluation_import_and_tables_4_7_4_8.sql
-- PostgreSQL / psql
-- Zweck:
--   Import der Datei expertenevaluation_ergebnisse_raw.csv und
--   Reproduktion der Tabellen 4.7 und 4.8 der Bachelorarbeit.
--
-- Voraussetzung fuer Tabelle 4.8:
--   Die bereits verwendete Stichprobe liegt in
--   expert_eval_example_primary_run_1_30_stability
--   mit den Spalten sample_no, pattern und kind vor.
--
-- =============================================================================

BEGIN;

-- ---------------------------------------------------------------------------
-- 1. Persistente Tabelle fuer die Rohbewertungen
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS expert_eval_ratings_raw (
                                                       sample_no              integer      NOT NULL CHECK (sample_no BETWEEN 1 AND 30),
    evaluator              varchar(2)   NOT NULL CHECK (evaluator IN ('E1','E2','E3','E4','E5')),
    erfahrung              text         NOT NULL,
    f1_titel               smallint     NOT NULL CHECK (f1_titel BETWEEN 1 AND 5),
    f2_zusammenfassung     smallint     NOT NULL CHECK (f2_zusammenfassung BETWEEN 1 AND 5),
    f3_klassifikation      smallint     NOT NULL CHECK (f3_klassifikation BETWEEN 1 AND 5),
    f4_praxisnutzen        smallint     NOT NULL CHECK (f4_praxisnutzen BETWEEN 1 AND 5),
    mittelwert             numeric(4,2) NOT NULL,
    kommentar              text,
    PRIMARY KEY (sample_no, evaluator)
    );

-- Ein erneuter Import ersetzt nur die Rohbewertungen, nicht die Pipeline-Daten.
TRUNCATE TABLE expert_eval_ratings_raw;

-- ---------------------------------------------------------------------------
-- 2. Staging-Tabelle: mittelwert wird als Text importiert, da die CSV
--    deutsche Dezimaltrennzeichen enthaelt, z.B. "4,75".
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS expert_eval_csv_stage;
CREATE TEMP TABLE expert_eval_csv_stage (
    sample_no              text,
    evaluator              text,
    erfahrung              text,
    f1_titel               text,
    f2_zusammenfassung     text,
    f3_klassifikation      text,
    f4_praxisnutzen        text,
    mittelwert             text,
    kommentar              text
);

-- WICHTIG: Den Pfad zur CSV-Datei lokal anpassen.
-- Der Befehl \copy liest die Datei vom Rechner des psql-Clients.
\copy expert_eval_csv_stage (sample_no,evaluator,erfahrung,f1_titel,f2_zusammenfassung,f3_klassifikation,f4_praxisnutzen,mittelwert,kommentar) FROM 'expertenevaluation_ergebnisse_raw.csv' WITH (FORMAT csv, HEADER true, ENCODING 'UTF8');

INSERT INTO expert_eval_ratings_raw (
    sample_no,
    evaluator,
    erfahrung,
    f1_titel,
    f2_zusammenfassung,
    f3_klassifikation,
    f4_praxisnutzen,
    mittelwert,
    kommentar
)
SELECT
    BTRIM(sample_no)::integer,
    BTRIM(evaluator),
    BTRIM(erfahrung),
    BTRIM(f1_titel)::smallint,
    BTRIM(f2_zusammenfassung)::smallint,
    BTRIM(f3_klassifikation)::smallint,
    BTRIM(f4_praxisnutzen)::smallint,
    REPLACE(BTRIM(mittelwert), ',', '.')::numeric(4,2),
    NULLIF(BTRIM(kommentar), '')
FROM expert_eval_csv_stage
ORDER BY BTRIM(sample_no)::integer, BTRIM(evaluator);

COMMIT;

-- ---------------------------------------------------------------------------
-- 3. Import-Sanity-Checks
-- ---------------------------------------------------------------------------

-- Erwartung: 30 Annotationen, 5 Evaluatoren, 150 Bewertungszeilen,
--            600 Einzelbewertungen.
SELECT
    COUNT(DISTINCT sample_no) AS annotationen,
    COUNT(DISTINCT evaluator) AS evaluatoren,
    COUNT(*) AS annotation_evaluator_zeilen,
    COUNT(*) * 4 AS einzelbewertungen
FROM expert_eval_ratings_raw;

-- Erwartung: keine Zeilen; prueft den mitgelieferten Zeilenmittelwert.
SELECT
    sample_no,
    evaluator,
    mittelwert AS csv_mittelwert,
    ROUND(
            (f1_titel + f2_zusammenfassung + f3_klassifikation + f4_praxisnutzen) / 4.0,
            2
    ) AS berechneter_mittelwert
FROM expert_eval_ratings_raw
WHERE mittelwert <> ROUND(
        (f1_titel + f2_zusammenfassung + f3_klassifikation + f4_praxisnutzen) / 4.0,
        2
                    )
ORDER BY sample_no, evaluator;

-- Erwartung fuer den Join zur Stichprobe: 30 Sample-Nummern, keine Luecken.
SELECT
    COUNT(*) AS samples_in_stichprobe,
    COUNT(DISTINCT sample_no) AS eindeutige_sample_nummern
FROM expert_eval_example_primary_run_1_30_stability;

SELECT r.sample_no
FROM (SELECT DISTINCT sample_no FROM expert_eval_ratings_raw) r
         LEFT JOIN expert_eval_example_primary_run_1_30_stability s USING (sample_no)
WHERE s.sample_no IS NULL
ORDER BY r.sample_no;


-- =============================================================================
-- 4. Tabelle 4.7: Mittlere Bewertungen nach Dimension
--    inklusive mittlerem paarweisen linear gewichteten Cohen's Kappa
-- =============================================================================

DROP VIEW IF EXISTS expert_eval_ratings_long CASCADE;
CREATE TEMP VIEW expert_eval_ratings_long AS
SELECT sample_no, evaluator, 'F1'::text AS frage, f1_titel::integer AS bewertung
FROM expert_eval_ratings_raw
UNION ALL
SELECT sample_no, evaluator, 'F2', f2_zusammenfassung::integer
FROM expert_eval_ratings_raw
UNION ALL
SELECT sample_no, evaluator, 'F3', f3_klassifikation::integer
FROM expert_eval_ratings_raw
UNION ALL
SELECT sample_no, evaluator, 'F4', f4_praxisnutzen::integer
FROM expert_eval_ratings_raw;

DROP VIEW IF EXISTS expert_eval_kappa_by_dimension;
CREATE TEMP VIEW expert_eval_kappa_by_dimension AS
WITH paired AS (
    SELECT
        a.frage,
        a.evaluator AS evaluator_1,
        b.evaluator AS evaluator_2,
        a.sample_no,
        a.bewertung AS rating_1,
        b.bewertung AS rating_2
    FROM expert_eval_ratings_long a
    JOIN expert_eval_ratings_long b
      ON b.sample_no = a.sample_no
     AND b.frage = a.frage
     AND a.evaluator < b.evaluator
),
pair_keys AS (
    SELECT DISTINCT frage, evaluator_1, evaluator_2
    FROM paired
),
observed AS (
    SELECT
        frage,
        evaluator_1,
        evaluator_2,
        AVG(1.0 - ABS(rating_1 - rating_2) / 4.0) AS p_observed
    FROM paired
    GROUP BY frage, evaluator_1, evaluator_2
),
distribution_1 AS (
    SELECT
        frage,
        evaluator_1,
        evaluator_2,
        rating_1 AS rating,
        COUNT(*)::numeric
          / SUM(COUNT(*)) OVER (PARTITION BY frage, evaluator_1, evaluator_2) AS p
    FROM paired
    GROUP BY frage, evaluator_1, evaluator_2, rating_1
),
distribution_2 AS (
    SELECT
        frage,
        evaluator_1,
        evaluator_2,
        rating_2 AS rating,
        COUNT(*)::numeric
          / SUM(COUNT(*)) OVER (PARTITION BY frage, evaluator_1, evaluator_2) AS p
    FROM paired
    GROUP BY frage, evaluator_1, evaluator_2, rating_2
),
rating_grid AS (
    SELECT i AS rating_1, j AS rating_2
    FROM generate_series(1, 5) i
    CROSS JOIN generate_series(1, 5) j
),
expected AS (
    SELECT
        p.frage,
        p.evaluator_1,
        p.evaluator_2,
        SUM(
            (1.0 - ABS(g.rating_1 - g.rating_2) / 4.0)
            * COALESCE(d1.p, 0)
            * COALESCE(d2.p, 0)
        ) AS p_expected
    FROM pair_keys p
    CROSS JOIN rating_grid g
    LEFT JOIN distribution_1 d1
      ON d1.frage = p.frage
     AND d1.evaluator_1 = p.evaluator_1
     AND d1.evaluator_2 = p.evaluator_2
     AND d1.rating = g.rating_1
    LEFT JOIN distribution_2 d2
      ON d2.frage = p.frage
     AND d2.evaluator_1 = p.evaluator_1
     AND d2.evaluator_2 = p.evaluator_2
     AND d2.rating = g.rating_2
    GROUP BY p.frage, p.evaluator_1, p.evaluator_2
),
pairwise_kappa AS (
    SELECT
        o.frage,
        o.evaluator_1,
        o.evaluator_2,
        (o.p_observed - e.p_expected)
        / NULLIF(1.0 - e.p_expected, 0) AS kappa
    FROM observed o
    JOIN expected e
      USING (frage, evaluator_1, evaluator_2)
)
SELECT
    frage,
    COUNT(*)::integer AS evaluator_pairs,
    ROUND(AVG(kappa)::numeric, 2) AS kappa
FROM pairwise_kappa
GROUP BY frage;

DROP VIEW IF EXISTS ch4_table_4_7;
CREATE TEMP VIEW ch4_table_4_7 AS
WITH dimension_stats AS (
    SELECT
        CASE frage
            WHEN 'F1' THEN 1
            WHEN 'F2' THEN 2
            WHEN 'F3' THEN 3
            WHEN 'F4' THEN 4
        END AS sort_order,
        frage,
        CASE frage
            WHEN 'F1' THEN 'Titel treffend und verstaendlich'
            WHEN 'F2' THEN 'Zusammenfassung inhaltlich korrekt'
            WHEN 'F3' THEN 'Klassifikation angemessen'
            WHEN 'F4' THEN 'Annotation in der Praxis nuetzlich'
        END AS beschreibung,
        ROUND(AVG(bewertung)::numeric, 2) AS mittelwert,
        ROUND(
            100.0 * COUNT(*) FILTER (WHERE bewertung >= 4) / COUNT(*),
            2
        ) AS anteil_ge_4_prozent
    FROM expert_eval_ratings_long
    GROUP BY frage
),
with_kappa AS (
    SELECT
        d.sort_order,
        d.frage,
        d.beschreibung,
        d.mittelwert,
        d.anteil_ge_4_prozent,
        k.kappa::text AS kappa
    FROM dimension_stats d
    JOIN expert_eval_kappa_by_dimension k USING (frage)
),
gesamt AS (
    SELECT
        99 AS sort_order,
        'Gesamtmittelwert'::text AS frage,
        NULL::text AS beschreibung,
        ROUND(AVG(bewertung)::numeric, 2) AS mittelwert,
        ROUND(
            100.0 * COUNT(*) FILTER (WHERE bewertung >= 4) / COUNT(*),
            2
        ) AS anteil_ge_4_prozent,
        '--'::text AS kappa
    FROM expert_eval_ratings_long
)
SELECT * FROM with_kappa
UNION ALL
SELECT * FROM gesamt;

SELECT
    frage,
    beschreibung,
    mittelwert,
    anteil_ge_4_prozent AS "Anteil >= 4 (%)",
    kappa
FROM ch4_table_4_7
ORDER BY sort_order;

-- Erwartetes Ergebnis Tabelle 4.7:
-- F1              Titel treffend und verstaendlich          4.01  81.33  0.26
-- F2              Zusammenfassung inhaltlich korrekt        4.09  85.33  0.41
-- F3              Klassifikation angemessen                 4.29  84.67  0.63
-- F4              Annotation in der Praxis nuetzlich        3.46  48.00  0.54
-- Gesamtmittelwert NULL                                     3.97  74.83  --


-- =============================================================================
-- 5. Tabelle 4.8: Mittlere Bewertungen nach Musterklasse
-- =============================================================================

DROP VIEW IF EXISTS ch4_table_4_8;
CREATE TEMP VIEW ch4_table_4_8 AS
WITH joined AS (
    SELECT
        s.pattern::text AS kategorie,
        r.sample_no,
        r.evaluator,
        r.f1_titel::numeric AS f1,
        r.f2_zusammenfassung::numeric AS f2,
        r.f3_klassifikation::numeric AS f3,
        r.f4_praxisnutzen::numeric AS f4
    FROM expert_eval_ratings_raw r
    JOIN expert_eval_example_primary_run_1_30_stability s
      USING (sample_no)
),
by_category AS (
    SELECT
        CASE kategorie
            WHEN 'CONTROL_FLOW_TEMPLATE' THEN 1
            WHEN 'IDIOM_PATTERN' THEN 2
            WHEN 'DATA_VALIDATION' THEN 3
            WHEN 'BUSINESS_RULE_CANDIDATE' THEN 4
            WHEN 'OTHER' THEN 5
            ELSE 6
        END AS sort_order,
        kategorie,
        COUNT(DISTINCT sample_no)::integer AS n,
        ROUND(AVG(f1), 2) AS f1,
        ROUND(AVG(f2), 2) AS f2,
        ROUND(AVG(f3), 2) AS f3,
        ROUND(AVG(f4), 2) AS f4,
        ROUND(AVG((f1 + f2 + f3 + f4) / 4.0), 2) AS gesamt
    FROM joined
    GROUP BY kategorie
),
gesamt AS (
    SELECT
        99 AS sort_order,
        'Gesamt'::text AS kategorie,
        COUNT(DISTINCT sample_no)::integer AS n,
        ROUND(AVG(f1), 2) AS f1,
        ROUND(AVG(f2), 2) AS f2,
        ROUND(AVG(f3), 2) AS f3,
        ROUND(AVG(f4), 2) AS f4,
        ROUND(AVG((f1 + f2 + f3 + f4) / 4.0), 2) AS gesamt
    FROM joined
)
SELECT * FROM by_category
UNION ALL
SELECT * FROM gesamt;

SELECT
    kategorie,
    n,
    f1,
    f2,
    f3,
    f4,
    gesamt
FROM ch4_table_4_8
ORDER BY sort_order;

-- Erwartetes Ergebnis Tabelle 4.8:
-- CONTROL_FLOW_TEMPLATE     7  4.17  4.37  4.83  3.57  4.24
-- IDIOM_PATTERN             7  4.14  4.29  4.57  3.23  4.06
-- DATA_VALIDATION           7  3.83  3.97  4.20  3.54  3.89
-- BUSINESS_RULE_CANDIDATE   8  3.98  3.83  3.68  3.58  3.76
-- OTHER                     1  3.60  3.80  4.20  2.80  3.60
-- Gesamt                   30  4.01  4.09  4.29  3.46  3.97


-- =============================================================================
-- 6. Zusatzbefund im Text nach Tabelle 4.8: IF versus EVALUATE
-- =============================================================================

SELECT
    s.kind::text AS regeltyp,
    COUNT(DISTINCT r.sample_no) AS annotationen,
    ROUND(
            AVG(
                    (r.f1_titel + r.f2_zusammenfassung
                        + r.f3_klassifikation + r.f4_praxisnutzen) / 4.0
            )::numeric,
            2
    ) AS gesamtmittelwert
FROM expert_eval_ratings_raw r
         JOIN expert_eval_example_primary_run_1_30_stability s
              USING (sample_no)
GROUP BY s.kind
ORDER BY CASE s.kind::text WHEN 'IF' THEN 1 WHEN 'EVALUATE' THEN 2 ELSE 3 END;

-- Erwartetes Ergebnis:
-- IF        24  4.06
-- EVALUATE   6  3.59


-- =============================================================================
-- 7. Optional: Export der rekonstruierten Ergebnistabellen mit psql
-- =============================================================================

-- \copy (SELECT frage, beschreibung, mittelwert, anteil_ge_4_prozent, kappa FROM ch4_table_4_7 ORDER BY sort_order) TO 'table_4_7_dimensionen.csv' CSV HEADER ENCODING 'UTF8';
-- \copy (SELECT kategorie, n, f1, f2, f3, f4, gesamt FROM ch4_table_4_8 ORDER BY sort_order) TO 'table_4_8_musterklassen.csv' CSV HEADER ENCODING 'UTF8';
