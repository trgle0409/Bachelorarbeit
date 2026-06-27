WITH compared AS (
    SELECT
        r1.abstract_canonical_key,
        r1.pattern::text AS pattern_run_1,
        r2.pattern::text AS pattern_run_2,
        r3.pattern::text AS pattern_run_3
    FROM cobol_ai_rule_labels_v1 r1
             JOIN cobol_ai_rule_labels_v2 r2
                  ON r2.abstract_canonical_key = r1.abstract_canonical_key
             JOIN cobol_ai_rule_labels_v3 r3
                  ON r3.abstract_canonical_key = r1.abstract_canonical_key
)
SELECT
    COUNT(*) AS total_patterns,

    COUNT(*) FILTER (
        WHERE pattern_run_1 = pattern_run_2
    ) AS same_run_1_run_2,

    ROUND(
            100.0 * COUNT(*) FILTER (
            WHERE pattern_run_1 = pattern_run_2
        ) / COUNT(*),
            2
    ) AS same_run_1_run_2_pct,

    COUNT(*) FILTER (
        WHERE pattern_run_1 = pattern_run_3
    ) AS same_run_1_run_3,

    ROUND(
            100.0 * COUNT(*) FILTER (
            WHERE pattern_run_1 = pattern_run_3
        ) / COUNT(*),
            2
    ) AS same_run_1_run_3_pct,

    COUNT(*) FILTER (
        WHERE pattern_run_2 = pattern_run_3
    ) AS same_run_2_run_3,

    ROUND(
            100.0 * COUNT(*) FILTER (
            WHERE pattern_run_2 = pattern_run_3
        ) / COUNT(*),
            2
    ) AS same_run_2_run_3_pct,

    COUNT(*) FILTER (
        WHERE pattern_run_1 = pattern_run_2
          AND pattern_run_1 = pattern_run_3
    ) AS stable_in_all_three_runs,

    ROUND(
            100.0 * COUNT(*) FILTER (
            WHERE pattern_run_1 = pattern_run_2
              AND pattern_run_1 = pattern_run_3
        ) / COUNT(*),
            2
    ) AS stable_in_all_three_runs_pct

FROM compared;

--Erwartetes Ergebnis:
--total_patterns,same_run_1_run_2,same_run_1_run_2_pct,same_run_1_run_3,same_run_1_run_3_pct,same_run_2_run_3,same_run_2_run_3_pct,stable_in_all_three_runs,stable_in_all_three_runs_pct
--1163,959,82.46,847,72.83,844,72.57,756,65
