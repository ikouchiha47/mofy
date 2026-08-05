# The Ralph Loop

A Ralph loop is a simple technique for driving an AI agent through a large
task list autonomously: run the same prompt against the same task file,
repeatedly, letting the agent pick up wherever the file says it left off.

There is no session memory carried between iterations other than the task
file itself and the repo state (git history, code). Each iteration must be
able to stand alone.

## The loop

1. Open the phase's task file in `docs/tasks/NN-name.md`.
2. Find the first unchecked `- [ ]` task, top to bottom.
3. Read the corresponding requirement(s) in `docs/phases/NN-name.md` (EARS
   format) so the acceptance criteria are unambiguous.
4. Implement just that task. Don't jump ahead to later tasks even if the
   solution is obvious — one task per iteration keeps diffs reviewable and
   keeps the loop resumable if it's interrupted mid-phase.
5. Verify the task against its requirement (run it, test it, actually check
   the behavior — do not mark done on faith).
6. Check the box: `- [x]`. Commit.
7. If a task turns out to be ambiguous, too large, or blocked on a decision
   only the human can make, do not silently skip it. Add a `> BLOCKED:`
   note under the task explaining why, and stop the loop there.
8. If all tasks in the file are checked, stop — do not invent new tasks.
   New tasks belong in the next phase's file or in a fresh line item added
   deliberately, not auto-generated to keep the loop busy.

## Rules for writing task files (for whoever authors `docs/tasks/*.md`)

- Each task should be independently completable and independently
  verifiable — no "do steps 3-9 as one task."
- Order tasks by dependency within the phase, same as phases are ordered
  relative to each other.
- A task should reference which EARS requirement(s) it satisfies, so the
  loop never has to guess what "done" means.
- Do not estimate effort or duration on tasks. Size is irrelevant to an
  agent running a loop; only correctness and order matter.
</content>
