# Docs

Everything produced while building the automation engine, regions, maker-checker and as-of work on branch
`feature/automation-regions-approvals-asof`.

## Start here

- [**Workflow architecture**](workflow/WORKFLOW-ARCHITECTURE.md): how the work was orchestrated across 218
  agents, phase by phase, with cost, guardrails and what went wrong.
- [**Requirement**](requirements/automation-regions-approvals-asof.md): the PRD (A1–A6, B1–B3).
- [**Final test run**](regression/2026-09-28/README.md): results, the one open defect, and the API and UI reports.

## Folders

| Folder | Contents |
|---|---|
| [`requirements/`](requirements/) | The PRD this work was built from |
| [`design/`](design/) | [`DESIGN.md`](design/DESIGN.md): the authoritative design, covering data model, rights, the REST contract, the frontend, the test plan, the slice plan, open questions with the defaults taken, and the critique log. [`decision-records/`](design/decision-records/): the four judged decisions it was built from (as-of reads, regions and rights, maker-checker, automation engine) |
| [`implementation/codebase-map/`](implementation/codebase-map/) | Maps of the codebase **before** this work, one per subsystem, plus the critic's review. Useful for understanding conventions; the code has since changed |
| [`implementation/build/`](implementation/build/) | Per-slice build logs for backend and frontend (what each slice built, deviations from the design, gaps), the carry-forward list, and every slice's review findings |
| [`implementation/hardening/`](implementation/hardening/) | The whole-build audit: triage plan, product decisions, and fix logs |
| [`regression/2026-09-28/`](regression/2026-09-28/) | The E2E campaign: harness and suites (`scripts/`), per-round results, triage and fix logs (`data/`), and the reports with all screenshots (`report/`) |
| [`workflow/`](workflow/) | The architecture document, the exact workflow scripts that ran (`scripts/`), per-run figures (`workflow-runs.json`), and the snapshot, diff and cost tools (`tools/`) |

## Notes

- Paths inside these documents were rewritten from the build's scratch folder to their place under `docs/`.
  Line numbers cited in older documents (codebase maps, early decision records) refer to the code as it was then.
- Screenshot (`.png`) and runtime log paths inside the result files refer to the raw run folders, which were not
  kept. The screenshots are in `regression/2026-09-28/report/slides/` as JPEGs. Mentions of throwaway probe
  folders (`scratchpad/...`) in reviews and fix logs are records of where a check ran, not files to look for.
- The files in [`workflow/scripts/`](workflow/scripts/) are kept exactly as they ran, including their absolute
  paths and the list of the developer's services they were told never to touch.
- Nothing here is committed yet. The screenshots under `regression/2026-09-28/report/slides/` are 32 MB. Drop
  them before committing if you only want the text; the published UI report keeps them.
- Every build step is also recorded as a hidden git ref under `refs/snapshots/` (see
  [`workflow/tools/snap.sh`](workflow/tools/snap.sh)). `refs/snapshots/FINAL` (`ff97402d`) is the tested source.
  These refs exist only in the clone that did the build; a normal `git push` does not send them. To share them, run
  `git push origin "refs/snapshots/*:refs/snapshots/*"`, or tag `ff97402d`.
