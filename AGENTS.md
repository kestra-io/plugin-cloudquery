# Kestra CloudQuery Plugin

## What

- Provides plugin components under `io.kestra.plugin.cloudquery`.
- Includes classes such as `Sync`, `CloudQueryCLI`.

## Why

- What user problem does this solve? Teams need to run CloudQuery commands to sync data through the CLI from orchestrated workflows instead of relying on manual console work, ad hoc scripts, or disconnected schedulers.
- Why would a team adopt this plugin in a workflow? It keeps CloudQuery steps in the same Kestra flow as upstream preparation, approvals, retries, notifications, and downstream systems.
- What operational/business outcome does it enable? It reduces manual handoffs and fragmented tooling while improving reliability, traceability, and delivery speed for processes that depend on CloudQuery.

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
