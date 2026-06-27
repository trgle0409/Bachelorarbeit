# Dokumentation zum elektronischen Abgabepaket

## Bachelorarbeit: Hybrider Ansatz zur großskaligen Extraktion von Geschäftsregelkandidaten aus COBOL-Programmen mit Spring Batch und Künstlicher Intelligenz

**Autor:** Thanh Trung Le  
**Hochschule:** Hochschule Darmstadt (h_da), Fachbereich Informatik  
**Abgabedatum:** 26.05.2026  

---

## 1. Zweck des Abgabepakets

Dieses Paket enthält den Quellcode, die persistierten Pipeline-Ergebnisse, die Daten der Expertenevaluation sowie SQL-Skripte zur Nachvollziehbarkeit der in der Bachelorarbeit berichteten Ergebnisse.

Die Pipeline extrahiert zunächst **Entscheidungsregel-Kandidaten** aus expliziten `IF`- und `EVALUATE`-Strukturen. Die Kategorie `BUSINESS_RULE_CANDIDATE` bezeichnet einen Kandidaten für eine fachliche Prüfung und nicht den automatischen Nachweis einer bestätigten Geschäftsregel.

Eine erneute LLM-Annotation ist zur Prüfung der in der Arbeit berichteten Ergebnisse nicht erforderlich, da die drei verwendeten Annotationsläufe als CSV-Exporte beigefügt sind.

---

## 2. Zentrale Ergebnisse

Die Untersuchung basiert auf **882 als Programmeingaben eingelesenen COBOL-Quelldateien** aus X-COBOL und IBM GenApp.

| Kennzahl | Ergebnis |
|---|---:|
| Eingelesene Korpuseinheiten | 882 |
| Syntaktisch analysierbare Programme (`VALID` gesamt) | 487 |
| Regelkandidaten | 5.438 |
| Normalisierte Regelinstanzen | 6.017 |
| Kanonische Muster | 2.417 |
| Abstrakte Muster | 1.163 |
| Gesamtreduktion der Annotationsmenge | 80,67 % |

### LLM-Klassifikation im Primärlauf `run_1`

| Kategorie | Muster | Anteil |
|---|---:|---:|
| `BUSINESS_RULE_CANDIDATE` | 462 | 39,72 % |
| `CONTROL_FLOW_TEMPLATE` | 369 | 31,73 % |
| `IDIOM_PATTERN` | 255 | 21,93 % |
| `DATA_VALIDATION` | 76 | 6,53 % |
| `OTHER` | 1 | 0,09 % |
| **Gesamt** | **1.163** | **100,00 %** |

### Stabilität über drei Annotationsläufe

| Vergleich | Gleiche Klassifikation | Anteil |
|---|---:|---:|
| `run_1` vs. `run_2` | 959 | 82,46 % |
| `run_1` vs. `run_3` | 847 | 72,83 % |
| `run_2` vs. `run_3` | 844 | 72,57 % |
| Alle drei Läufe stimmen überein | 756 | 65,00 % |
| Mindestens ein Lauf weicht ab | 407 | 35,00 % |

### Expertenevaluation

| Kennzahl | Ergebnis |
|---|---:|
| Bewertete Annotationen | 30 |
| Evaluatoren | 5 |
| Einzelbewertungen | 600 |
| Gesamtmittelwert | 3,97 / 5 |
| Mittelwert F3 „Klassifikation angemessen“ | 4,29 / 5 |
| Mittleres paarweises linear gewichtetes Cohen’s Kappa für F3 | 0,63 |

---

## 3. Struktur der Ergebnis- und SQL-Dateien

```text
results/
├── ergebnis/
│   ├── expert_eval_sample_primary_run_1_30_stability.csv
│   ├── parserfehler_stichprobe.csv
│   ├── pipeline_stage_counts.csv
│   ├── regeltypen_regelinstanzen.csv
│   ├── tabelle_4_1_parse_status.csv
│   ├── tabelle_4_2_verdichtung.csv
│   ├── tabelle_4_3_klassifikationsverteilung_run_1.csv
│   ├── tabelle_4_4_stabilitaet_runs_1_2_3.csv
│   └── vorverarbeitung_sanity_checks.csv
├── expertenevaluation/
│   ├── expertenevaluation_ergebnisse_raw.csv
│   ├── expertenevaluation_freitextkommentare.txt
│   └── expertenevaluation_stichprobe_annotationen.csv
└── pipeline/
    ├── batch_metrics_3_runs.csv
    ├── cobol_programs.csv
    ├── Ergebnis_EXCEL.md
    ├── expert_eval_sample_primary_run_1_30_stability.csv
    ├── S2_cobol_parse_results.csv
    ├── S3_cobol_rule_candidates.csv
    ├── S4_cobol_extracted_rules.csv
    ├── S5_cobol_normalized_rules.csv
    ├── S6_cobol_canonical_rules.csv
    ├── S7_cobol_abstract_canonical_rules.csv
    ├── S8_cobol_ai_rule_labels_1.csv
    ├── S8_cobol_ai_rule_labels_2.csv
    └── S8_cobol_ai_rule_labels_3.csv

sql/
├── chapter4_sanity_sql_final.sql
├── expertenevaluation_import_and_tables_4_7_4_8.sql
├── klassifikationsstabilitaet_runs_1_2_3.sql
└── vorverarbeitung_sanity_check.sql
```

`README.md` liegt im Projektwurzelverzeichnis auf derselben Ebene wie `results/`, `sql/` und `src/`.

---

## 4. Struktur der Implementierung und Tests

```text
src/main/
├── antlr4/cobol85/      # COBOL-Grammatiken
├── java/thesis/
│   ├── ai/              # LLM-Annotation und Persistierung
│   ├── batch/           # Spring-Batch-Konfiguration und Metriken
│   ├── corpus/          # Einlesen des Korpus
│   ├── parser/          # Vorverarbeitung und Parsing
│   └── rules/           # Extraktion, Normalisierung und Abstraktion
└── resources/
    └── application.yml

src/test/java/thesis/
├── parser/              # Preprocessor- und Parser-Tests
├── rules/               # Extraktions-, Normalisierungs- und Abstraktionstests
└── BatchJobConfigStepTest
```

Die Tests decken die zentralen regelbasierten Verarbeitungsschritte sowie die Batch-Step-Konfiguration ab. Im finalen Stand des Abgabepakets wurden sämtliche vorhandenen automatisierten Tests erfolgreich ausgeführt. Damit ist insbesondere die testseitige Ausführbarkeit der Vorverarbeitung, des Parsings, der Regelextraktion, der Normalisierung, der strukturellen Abstraktion sowie der Batch-Step-Konfiguration für den abgegebenen Implementierungsstand geprüft.

Die Tests können aus dem Projektwurzelverzeichnis erneut ausgeführt werden:

```bash
mvn test
```

### Erstmaliges Bauen mit Maven

Für einen erstmaligen lokalen Build müssen Java, Maven und – nur für eine erneute Pipelineausführung – die benötigte lokale Konfiguration verfügbar sein. Aus dem Projektwurzelverzeichnis wird das Projekt einschließlich der automatisierten Tests wie folgt gebaut:

```bash
mvn clean package
```

Maven lädt beim ersten Build die benötigten Abhängigkeiten herunter, führt die vorhandenen Tests aus und erzeugt das ausführbare Artefakt im Verzeichnis `target/`. Für einen Build ohne erneute Testausführung kann optional verwendet werden:

```bash
mvn clean package -DskipTests
```

Für eine tatsächliche Neu-Ausführung der Pipeline müssen zusätzlich PostgreSQL, der Pfad zum COBOL-Korpus und für den LLM-Annotationsschritt ein eigener API-Schlüssel konfiguriert sein. Zur Prüfung der in der Arbeit berichteten Ergebnisse ist eine Neu-Ausführung nicht erforderlich, da die verwendeten Ergebnisdaten als CSV-Exporte beigefügt sind.

---

## 5. Kompakte Ergebnisdateien in `results/ergebnis/`

| Datei | Inhalt / Bezug zur Arbeit |
|---|---|
| `tabelle_4_1_parse_status.csv` | Parse-Statusverteilung der 882 Korpuseinheiten; Tabelle 4.1 |
| `regeltypen_regelinstanzen.csv` | Verteilung der 6.017 Regelinstanzen auf `IF` und `EVALUATE` |
| `tabelle_4_2_verdichtung.csv` | Verdichtung `6.017 → 2.417 → 1.163`; Tabelle 4.2 |
| `tabelle_4_3_klassifikationsverteilung_run_1.csv` | Klassifikationsverteilung des Primärlaufs; Tabelle 4.3 |
| `tabelle_4_4_stabilitaet_runs_1_2_3.csv` | Übereinstimmung der Kategorien über drei Läufe; Tabelle 4.4 |
| `pipeline_stage_counts.csv` | Ergänzende Mengenübersicht der Pipeline-Stufen |
| `vorverarbeitung_sanity_checks.csv` | Qualitätsprüfungen persistierter normalisierter Regeln aus dem Anhang |
| `parserfehler_stichprobe.csv` | Stichprobe verbleibender Parserfehler zur qualitativen Einordnung |
| `expert_eval_sample_primary_run_1_30_stability.csv` | Zusätzlicher Export der für die Evaluation verwendeten Stichprobe |

Das Laufzeitprofil aus Tabelle 4.5 wird aus der Rohdatei `results/pipeline/batch_metrics_3_runs.csv` mit `sql/chapter4_sanity_sql_final.sql` rekonstruiert.

---

## 6. Vollständige Pipeline-Exporte in `results/pipeline/`

Die vollständigen CSV-Exporte ermöglichen eine SQL-basierte Nachrechnung der Ergebniskennzahlen.

| CSV-Datei | Beim Import verwendeter Tabellenname | Inhalt |
|---|---|---|
| `cobol_programs.csv` | `cobol_programs` | Eingelesene COBOL-Korpuseinheiten |
| `S2_cobol_parse_results.csv` | `cobol_parse_results` | Parse- und Validierungsergebnisse |
| `S3_cobol_rule_candidates.csv` | `cobol_rule_candidates` | Erkannte Entscheidungsregel-Kandidaten |
| `S4_cobol_extracted_rules.csv` | `cobol_extracted_rules` | Strukturierte Regelinstanzen |
| `S5_cobol_normalized_rules.csv` | `cobol_normalized_rules` | Normalisierte Regelinstanzen |
| `S6_cobol_canonical_rules.csv` | `cobol_canonical_rules` | Kanonische Muster |
| `S7_cobol_abstract_canonical_rules.csv` | `cobol_abstract_canonical_rules` | Abstrakte Muster |
| `S8_cobol_ai_rule_labels_1.csv` | `cobol_ai_rule_labels_1` | Annotationen des Primärlaufs `run_1` |
| `S8_cobol_ai_rule_labels_2.csv` | `cobol_ai_rule_labels_2` | Annotationen des Laufs `run_2` |
| `S8_cobol_ai_rule_labels_3.csv` | `cobol_ai_rule_labels_3` | Annotationen des Laufs `run_3` |
| `batch_metrics_3_runs.csv` | `batch_metrics` | Step- und Job-Metriken der drei Pipelineausführungen |
| `expert_eval_sample_primary_run_1_30_stability.csv` | `expert_eval_example_primary_run_1_30_stability` | Evaluationsstichprobe einschließlich Stabilitätsbezug |

**Hinweis zur Benennung der Evaluationsstichprobe:** Der CSV-Dateiname enthält `sample`, während die SQL-Skripte den Tabellennamen `expert_eval_example_primary_run_1_30_stability` verwenden. Beim Import muss die Datei daher unter diesem Tabellennamen bereitgestellt werden.

### Drei vollständige Pipelineausführungen

Die Datei `batch_metrics_3_runs.csv` dokumentiert die am **24.05.2026** ausgeführten Läufe:

| Lauf | Step-IDs | Bedeutung |
|---|---:|---|
| `run_1` | 794–801 | erste vollständige Ausführung |
| `run_2` | 803–810 | zweite vollständige Ausführung |
| `run_3` | 812–819 | dritte vollständige Ausführung |

Zeilen ohne Step-Namen sind Job-Gesamteinträge und keine zusätzlichen Läufe. Für das in der Arbeit berichtete Laufzeitprofil wird die Zeitspanne vom Start von S1 bis zum Abschluss von S8 aus den Step-Zeilen berechnet.

---

## 7. Dateien zur Expertenevaluation

| Datei | Zweck |
|---|---|
| `expertenevaluation_stichprobe_annotationen.csv` | Die 30 im Fragebogen präsentierten Annotationen mit Regeltyp, Kategorie, LLM-Annotation und Quelltextbelegen |
| `expertenevaluation_ergebnisse_raw.csv` | Rohbewertungen: 30 Annotationen × 5 Evaluatoren mit F1–F4 und Kommentaren |
| `expertenevaluation_freitextkommentare.txt` | Lesefassung der anonymisierten Freitextbegründungen |

Die Rohbewertungen bilden die Grundlage für die Tabellen 4.7 und 4.8 sowie für die im Text erläuterten qualitativen Grenzfälle.

---

## 8. SQL-Skripte

| SQL-Datei | Zweck |
|---|---|
| `sql/chapter4_sanity_sql_final.sql` | Rekonstruktion und Kontrolle der zentralen quantitativen Ergebnisse aus Kapitel 4 |
| `sql/klassifikationsstabilitaet_runs_1_2_3.sql` | Eigenständige Kontrolle der Stabilität der Klassifikation über drei Läufe |
| `sql/expertenevaluation_import_and_tables_4_7_4_8.sql` | Import der Rohbewertungen und Rekonstruktion der Tabellen 4.7 und 4.8 |
| `sql/vorverarbeitung_sanity_check.sql` | Nachweisabfragen zu den Qualitätsprüfungen der Vorverarbeitung im Anhang |

Die Skripte enthalten jeweils Kommentare mit den erwarteten Ergebnissen. Für `psql`-Exportbefehle (`\copy`) müssen gegebenenfalls die lokalen Pfade angepasst werden.

---

## 9. Empfohlenes Vorgehen zur Nachprüfung

### 9.1 Direkte Einsicht in kompakte Ergebnisexporte

Für eine schnelle Prüfung genügen zunächst:

```text
results/ergebnis/tabelle_4_1_parse_status.csv
results/ergebnis/tabelle_4_2_verdichtung.csv
results/ergebnis/tabelle_4_3_klassifikationsverteilung_run_1.csv
results/ergebnis/tabelle_4_4_stabilitaet_runs_1_2_3.csv
results/ergebnis/vorverarbeitung_sanity_checks.csv
results/expertenevaluation/expertenevaluation_ergebnisse_raw.csv
```

### 9.2 SQL-basierte Nachrechnung aus Pipeline-Exporten

Nach Import der CSV-Dateien aus `results/pipeline/` unter den in Abschnitt 6 genannten Tabellennamen:

```bash
psql -U <db_user> -d <db_name> -f sql/chapter4_sanity_sql_final.sql
psql -U <db_user> -d <db_name> -f sql/klassifikationsstabilitaet_runs_1_2_3.sql
psql -U <db_user> -d <db_name> -f sql/vorverarbeitung_sanity_check.sql
```

### 9.3 Tabellen 4.7 und 4.8 der Expertenevaluation

Nach Import der Evaluationsstichprobe als Tabelle `expert_eval_example_primary_run_1_30_stability` wird ausgeführt:

```bash
psql -U <db_user> -d <db_name> -f sql/expertenevaluation_import_and_tables_4_7_4_8.sql
```

Im Skript ist gegebenenfalls der Pfad zur Rohdaten-CSV anzupassen:

```sql
\copy expert_eval_csv_stage (...)
FROM 'results/expertenevaluation/expertenevaluation_ergebnisse_raw.csv'
WITH (FORMAT csv, HEADER true, ENCODING 'UTF8');
```

Erwartete Kernergebnisse:

| Auswertung | Ergebnis |
|---|---:|
| Bewertete Annotationen | 30 |
| Evaluatoren | 5 |
| Einzelbewertungen | 600 |
| Gesamtmittelwert | 3,97 |
| F3: Mittelwert | 4,29 |
| F3: mittleres paarweises linear gewichtetes Cohen’s Kappa | 0,63 |
| Mittelwert `IF`-Annotationen | 4,06 |
| Mittelwert `EVALUATE`-Annotationen | 3,59 |

---

## 10. Interpretationsgrenzen

1. Die Regelanalyse bezieht sich auf den syntaktisch analysierbaren Teilkorpus; von 882 eingelesenen Korpuseinheiten waren 487 syntaktisch analysierbar.
2. Eingebettete `EXEC SQL`- und `EXEC CICS`-Blöcke wurden parser-sicher neutralisiert; ihre interne Logik wurde nicht inhaltlich ausgewertet.
3. Strukturelle Ähnlichkeit nach Normalisierung und Abstraktion belegt keine vollständige semantische Gleichwertigkeit der Quelltextbelege.
4. Die LLM-Klassifikation ist nicht vollständig stabil: 756 von 1.163 Mustern erhielten in allen drei Läufen dieselbe Kategorie.
5. Die Expertenevaluation basiert auf einer disproportional geschichteten, quotenbasierten Stichprobe von 30 Annotationen; ihre Mittelwerte sind nicht unverzerrt auf alle 1.163 Muster hochzurechnen.
6. Ein direkter Genauigkeitsvergleich mit bestehenden Werkzeugen wird nicht beansprucht, da kein gemeinsamer manuell annotierter Referenzkorpus vorliegt.

---

## 11. Kontakt

**Autor:** Thanh Trung Le  
**Fachbereich:** Informatik, Hochschule Darmstadt (h_da)  

*Stand dieser Dokumentation: 26.05.2026*
