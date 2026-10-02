export const meta = {
  name: 'final-tasks-completion',
  description: 'Complete step 4 for the tasks area: run the already-written exploratory cases as-is (test only), file the suite results correctly, and update the report data',
  phases: [ { title: 'Test', detail: 'run tasks final-explore.js as written' }, { title: 'Compile', detail: 'update report-data.json' } ],
}
const REPO = '/Users/srinivasans/Git/DooD_Test/sundhartest/geneinvt1'
const SP = 'docs'
const E = `${SP}/e2e`
const F = `${E}/final`
const ISOLATION = `ISOLATION: never touch the user's services (Docker gene-invoice-db :5433, gi-verify-db :55433, dood-srv :8080, kernel-netguard :8090, srv-db :3306, srv-graph :7474/:7687, llm-service :50051, Python :8081/:8093/:8765, Java :8082/:8092). Use only gi-e2e-db (:18432, e2e_* databases), ports 18100–18299, and only kill processes you started. Git read-only.`
const FINAL = `THIS IS THE FINAL TEST-ONLY RUN: never edit anything under ${REPO}; never edit ${E}/suites/** or ${E}/lib.js. If a case cannot run because of the harness, mark it BLOCKED with the reason. A failure you cannot reproduce twice is NOT_TESTED.`

phase('Test')
const run = await agent(`${ISOLATION}\n\n${FINAL}\n\nYou complete the final run for the TASKS area (env index 4: API :18104, WEB :18204). Read ${E}/README.md.\n1. The suite-as-written already ran and wrote ${E}/runs/rNaN/tasks.json (75/75 PASS; its runner mis-labelled the round). Verify that file's cases and timestamp, then copy it to ${E}/runs/final/tasks.json and set its "round" field to "final" (only that field). Move any screenshots it wrote under runs/rNaN/ to ${E}/runs/final/tasks/shots/ and fix their paths in the copied JSON.\n2. The exploratory cases were written by the final tester at ${E}/suites/tasks/final-explore.js but never run. Run that file AS WRITTEN on a fresh database (\`${E}/env/up.sh tasks 4 --fresh\` … \`${E}/env/down.sh tasks\` — always bring it down). It must write ${E}/runs/final/tasks-explore.json in the README case format (if the file's own output path/round handling is wrong, pass the round/output it expects via argv/env rather than editing it; if it truly cannot run without an edit, mark the affected cases BLOCKED with the reason). Screenshots under ${E}/runs/final/tasks/shots/explore/.\n3. For every FAIL: exact steps, expected (PRD/DESIGN reference), actual, evidence, code file:line if found; ids TSK-F-<n>. No fixes.\nReturn: suite counts, explore counts (total/pass/fail/blocked/notTested, API/UI split), defects (full detail).`, { label: 'final:tasks-explore', phase: 'Test', effort: 'high' })

phase('Compile')
const upd = await agent(`You update the final report data after the tasks area's final run completed. Inputs: ${F}/report-data.json (compiled earlier; tasks explore cases were NOT_TESTED because they hadn't run), ${E}/runs/final/tasks.json and ${E}/runs/final/tasks-explore.json (now written), and this tester report: ${JSON.stringify(run)}. Recompute from the case files (do not trust summaries): api/ui summary, byArea, byPrd, cases (replace the tasks cases with the real results), defects (add any tasks FAIL with full detail and screenshot), gallery (add any tasks FAIL screenshot as a compressed JPEG into ${F}/assets/ with sips -s format jpeg -s formatOptions 72 -Z 1280), history 'final' row, and knownLimitations (remove the 'tasks explore not run' and 'rNaN' entries if resolved; keep anything still true). Write ${F}/report-data.json in place (keep its structure). Only write under ${F}/. Return the new headline numbers (API and UI total/pass/fail/blocked/notTested) and any defects.`, { label: 'final:recompile', phase: 'Compile', effort: 'high' })

return { run, upd }
