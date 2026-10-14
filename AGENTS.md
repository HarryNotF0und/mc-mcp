# Agent instructions

Read [HANDOFF.md](HANDOFF.md) first (current status, next steps, pitfalls), then [CLAUDE.md](CLAUDE.md)
(conventions and commands; it applies to every agent, not only Claude) and [PLAN-v2.md](PLAN-v2.md).

- After every piece of work, write the details into HANDOFF.md before committing: what changed and
  why, the files, the tests you ran with their real results, what was not verified, and known
  issues. Claude and Codex take turns on this repository; HANDOFF.md is the shared state.
- [docs/AGENTS.md](docs/AGENTS.md) is a different document: operating instructions for an agent
  that *uses* the MC-MCP tools in a Minecraft working directory (copy it there). When you add or
  change tools or their behaviour, update it as well as docs/USAGE.md.
