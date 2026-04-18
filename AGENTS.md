# Kestra CloudQuery Plugin

## What

- Provides plugin components under `io.kestra.plugin.cloudquery`.
- Includes classes such as `Sync`, `CloudQueryCLI`.

## Why

- This plugin integrates Kestra with CloudQuery.
- It provides tasks that run CloudQuery commands to sync data through the CLI.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `cloudquery`

### Key Plugin Classes

- `io.kestra.plugin.cloudquery.CloudQueryCLI`
- `io.kestra.plugin.cloudquery.Sync`

### Project Structure

```
plugin-cloudquery/
├── src/main/java/io/kestra/plugin/cloudquery/
├── src/test/java/io/kestra/plugin/cloudquery/
├── build.gradle
└── README.md
```

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
