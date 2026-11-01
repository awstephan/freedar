# Issue tracker: GitHub

Specs and tickets live at https://github.com/awstephan/freedar/issues.
Use the gh CLI for all tracker operations.

## Conventions

- Before creating issues, verify the target with:
  gh repo view --json nameWithOwner
  Expected repository: awstephan/freedar.
- Publish specs and tickets as GitHub issues.
- Read tickets with:
  gh issue view <number> --json number,title,body,labels,comments
- Create issues with gh issue create; use --body-file for long bodies.
- Link implementation tickets to their parent spec using native sub-issues
  where available. Otherwise include “Part of #<parent>” in the body.
- Record blockers using native GitHub issue dependencies where available.
  Otherwise include “Blocked by: #<number>” in the body.
- Use supported gh commands or gh api for sub-issues and dependencies.
  API dependency links require issue database IDs, not issue numbers.
- A ticket is startable when all its blockers are closed.
- Comment with gh issue comment; apply labels with gh issue edit.
- Post the completion explanation before closing an issue.

## Pull requests as a triage surface

PRs as a request surface: no.

## Wayfinding

Use a parent issue labelled wayfinder:map and linked decision tickets.
Use wayfinder:<type> labels for research, prototype, grilling, or task.
Claim a ready ticket by assigning yourself. Record the result before
closing it and add a summary/link to the parent map.
