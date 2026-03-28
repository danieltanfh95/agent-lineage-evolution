#!/usr/bin/env python3
"""CSV analysis CLI tool."""

import click
import pandas as pd


@click.command()
@click.argument("csvfile", type=click.Path(exists=True))
@click.option("--format", "output_format", default="text", help="Output format: text or json")
def analyze(csvfile, output_format):
    """Analyze a CSV file and print summary statistics."""
    df = pd.read_csv(csvfile)

    if output_format == "json":
        click.echo(df.describe().to_json())
    else:
        click.echo(f"Rows: {len(df)}")
        click.echo(f"Columns: {', '.join(df.columns)}")
        click.echo(f"\n{df.describe()}")


if __name__ == "__main__":
    analyze()
