# Issue tracker: GitHub Issues

Issues and specs (you may know a spec as a PRD) for this repo live in GitHub Issues on
`famesjranko/musicmeta`. Use the authenticated `gh` CLI for every operation.

> **Outside reports arrive through the same tracker.** Contributors file them through the templates
> in `.github/ISSUE_TEMPLATE/`, which apply `needs-triage`. An inbound report is a person's: triage
> it, label it and comment on it, but closing it is the maintainer's call unless a merged PR closes
> it with a closing keyword. `provider-drift.yml` files nothing: a failed scheduled run emails the
> maintainer, and that email is the drift signal.

> **Issues are public.** A vulnerability or a leaked credential never becomes an issue, including a
> finding of your own: it goes through the private security policy in `.github/SECURITY.md`.

## Conventions

- One issue per work item. A spec is an issue too, and its implementation tickets are sub-issues of
  it (`gh issue create --parent <n>`).
- Labels carry the state. One triage role from `triage-labels.md`, plus a `priority/p0`–`p3` and an
  `area/*` label once it is triaged. Bugs and features also carry `bug` or `enhancement`.
- Discussion, findings and decisions go in issue comments, not edits to the body. Edit the body only
  to correct the problem statement or the acceptance criteria, and say so in a comment.
- A PR that fully resolves an issue says `Closes #n`. A PR that only advances it links it without
  the keyword.
- Write the body for a reader who has not seen the conversation: the problem, the evidence with
  `file:line` or a permalink, and the acceptance criteria. Pass long bodies with `--body-file`.

`.scratch/` stays gitignored. It holds local working files, such as prototype arms and probe
scripts (`CLAUDE.md`), but no tickets.

## When a skill says "publish to the issue tracker"

Run `gh issue create` with a title, a `--body-file`, and the labels above. A new item that nobody
has triaged gets `needs-triage`.

## When a skill says "fetch the relevant ticket"

Run `gh issue view <n> --comments`. The user will normally pass the issue number or URL.

## Wayfinding operations

Used by `/wayfinder`. The **map** is an issue with one **child** sub-issue per ticket.

- **Map**: an issue whose body holds the Notes / Decisions-so-far / Fog sections.
- **Child ticket**: a sub-issue of the map (`gh issue create --parent <map>`), with the question in
  the body. A `Type:` line in the body records the ticket type (`research`/`prototype`/`grilling`/`task`).
- **Blocking**: a `Blocked by: #n, #m` line near the top of the body. A ticket is unblocked when
  every issue it lists is closed.
- **Frontier**: the map's open sub-issues that are unblocked and unassigned; the lowest number wins.
- **Claim**: assign yourself (`gh issue edit <n> --add-assignee @me`) before any work.
- **Resolve**: comment the answer under an `## Answer` heading, close the issue, then edit the
  map's Decisions-so-far to add a context pointer (gist + `#n`).
