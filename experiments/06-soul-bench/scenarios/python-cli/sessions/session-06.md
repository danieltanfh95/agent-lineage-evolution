# Session 6: Change Config Format — YAML to TOML

## Work Done
Replaced YAML config format with TOML for consistency with `pyproject.toml` and to remove the PyYAML dependency.

Changes:
- Config file changed from `config.yaml` to `config.toml`
- Replaced `import yaml` / `yaml.safe_load()` with `import tomllib` (stdlib in Python 3.11+) / `tomllib.loads()`
- Removed `pyyaml` from dependencies in `pyproject.toml`
- Updated `requires-python` from `>=3.10` to `>=3.11` (for `tomllib` stdlib support)
- Config format is now:
```toml
[defaults]
format = "json"
output_dir = "./reports"

[[defaults.filters]]
expr = "status == 'active'"
```
- Added migration note in README for existing users with `config.yaml` files

**NOTE**: YAML configuration is no longer supported. All config files must use TOML format (`config.toml`). The `pyyaml` dependency has been removed.

## Git Log
- refactor: replace YAML config with TOML
- chore: remove pyyaml dep, bump python to 3.11+
- docs: add config migration guide
