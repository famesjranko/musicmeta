# Triage Labels

The skills speak in terms of five canonical triage roles. This file maps those roles to the actual label strings used in this repo's issue tracker.

| Label in mattpocock/skills | Label in our tracker | Meaning                                  |
| -------------------------- | -------------------- | ---------------------------------------- |
| `needs-triage`             | `needs-triage`       | Maintainer needs to evaluate this issue  |
| `needs-info`               | `needs-info`         | Waiting on reporter for more information |
| `ready-for-agent`          | `ready-for-agent`    | Fully specified, ready for an AFK agent  |
| `ready-for-human`          | `ready-for-human`    | Requires human implementation            |
| `wontfix`                  | `wontfix`            | Will not be actioned                     |

When a skill mentions a role (e.g. "apply the AFK-ready triage label"), use the corresponding label string from this table.

## Where the label goes

The tracker is local markdown (`docs/agents/issue-tracker.md`), so there is nothing to apply a label
to — write the role string on the `Status:` line near the top of the issue file:

```markdown
Status: ready-for-agent
```

The same five strings also exist as GitHub labels on `famesjranko/musicmeta` — all except
`needs-triage`, which has no label yet — but the GitHub issues are a separate surface the skills do
not write to. Keeping one vocabulary across both is deliberate: an issue moved from `.scratch/` to
GitHub keeps its state.
