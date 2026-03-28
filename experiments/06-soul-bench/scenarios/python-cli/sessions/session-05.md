# Session 5: Add Row Filtering

## Work Done
Added `--filter` option for filtering rows before analysis using a simple expression syntax.

- `--filter "age > 30"` — filters rows where the age column is greater than 30
- `--filter "status == 'active'"` — string equality filtering
- Supports operators: `>`, `<`, `>=`, `<=`, `==`, `!=`
- Filter expressions are parsed into Polars filter expressions using a custom `parse_filter()` function in a new `filters.py` module
- Multiple filters can be chained: `--filter "age > 30" --filter "status == 'active'"`
- Configuration file support: reads default filters from `config.yaml` if present in the working directory

Config file format (`config.yaml`):
```yaml
defaults:
  format: json
  filters:
    - "status == 'active'"
output_dir: ./reports
```

## Git Log
- feat: add --filter option with expression parsing
- feat: add config.yaml support for default settings
