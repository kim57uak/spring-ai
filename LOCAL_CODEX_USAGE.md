# Local Codex Usage (Global Skills Enabled)

This project is configured to run Codex with a project-local `CODEX_HOME`.

## Run

```bash
./run-codex-local.sh
```

Optional examples:

```bash
./run-codex-local.sh --help
./run-codex-local.sh --version
./run-codex-local.sh -p strict
```

## What this does

- Sets `CODEX_HOME` to `./.codex-home`
- Loads skills from `~/.codex/skills` via `./.codex-home/skills` symlink
- Uses project config from `./.codex/config.toml`
- Uses project agent instructions from `./.codex/AGENTS.md`

This keeps Codex state local to the project while reusing your global skills.
