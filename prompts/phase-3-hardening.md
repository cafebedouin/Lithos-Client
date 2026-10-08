# Phase 3: hardening and pull-request shape

Read `CLAUDE.md`, `UPKEEP-BRIEF.md` (sections 3, 5, 6), everything under `app/transactions/upkeep/`,
`app/configs/UpkeepConfig.scala`, the specs under `test/transactions/upkeep/` and
`test/contracts/specs/upkeep/`, and the operator's notes in `prompts/REVIEW-NOTES.md` if present.

Then:
1. **Cost and bytes.** Estimate each built transaction's cost and size the way `StorageRent` does
   (`estimatedCost`, `sweptBytes`, the node's parameters from `ctx.getDataSource.getParameters`) so
   fitting uses real numbers; add a spec that a job whose transaction exceeds `maxCost` is left out
   while a cheaper one fits.
2. **Observability.** Log lines name the job and the box; participate in `logBudgets` the way other
   sources do (find where they report final contributions and do the same).
3. **Failure containment.** A job that throws in `build` loses only its own box for that height; the
   source still answers. Spec it.
4. **Docs.** A short section in `README.md` under whatever heading covers block transactions or
   storage rent (read the file; match its voice): what upkeep is, that it is off by default, how to
   enable a job, that it never spends the operator's ERG, and that it does not reorder or front-run.
5. **Review your own diff** against the brief's seven rules and the acceptance list; fix what fails.
6. **PR text** in `prompts/PR-DESCRIPTION.md`: title, what and why (two paragraphs), off by default,
   not extractive, broadcast mode as a follow-up, Dexy as the next job to be coordinated with the
   maintainer, how it was tested. No marketing language.

Run the full `sbt -batch test` with Java 17 if you can. Commit as "Harden the upkeep source: real
cost fitting, failure containment, docs". Report what changed, what the full test run said, and
anything in the earlier phases you would redo.
