package io.kestra.plugin.cloudquery;

import java.util.ArrayList;
import java.util.List;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.scripts.exec.scripts.models.DockerOptions;
import io.kestra.plugin.scripts.exec.scripts.models.ScriptOutput;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Run CloudQuery CLI commands",
    description = "Executes provided CloudQuery CLI commands in order using /bin/sh -c inside the task runner container; honors rendered env vars and input files."
)
@Plugin(
    examples = {
        @Example(
            title = "Run a CloudQuery sync from CLI. You need an [API key](https://docs.cloudquery.io/docs/deployment/generate-api-key) to download plugins. You can add the API key as an environment variable called `CLOUDQUERY_API_KEY`.",

            full = true,
            code = """
                id: cloudquery_sync_cli
                namespace: company.team

                tasks:
                  - id: hn_to_duckdb
                    type: io.kestra.plugin.cloudquery.CloudQueryCLI
                    env:
                      CLOUDQUERY_API_KEY: "{{ secret('CLOUDQUERY_API_KEY') }}"
                    inputFiles:
                      config.yml: |
                        kind: source
                        spec:
                          name: hackernews
                          path: cloudquery/hackernews
                          version: v3.0.13
                          tables: ["*"]
                          backend_options:
                            table_name: cq_cursor
                            connection: "@@plugins.duckdb.connection"
                          destinations:
                            - "duckdb"
                          spec:
                            item_concurrency: 100
                            start_time: "{{ now() | dateAdd(-1, 'DAYS') }}"
                        ---
                        kind: destination
                        spec:
                          name: duckdb
                          path: cloudquery/duckdb
                          version: v4.2.10
                          write_mode: overwrite-delete-stale
                          spec:
                            connection_string: hn.db
                    commands:
                      - cloudquery sync config.yml --log-console"""
        )
    }
)
public class CloudQueryCLI extends AbstractCloudQueryCommand implements RunnableTask<ScriptOutput> {

    @Schema(
        title = "Commands to execute",
        description = "Shell commands executed sequentially; include the cloudquery binary (aliased to /app/cloudquery) and any arguments."
    )
    @NotNull
    @PluginProperty(group = "main")
    protected Property<List<String>> commands;

    @Override
    public ScriptOutput run(RunContext runContext) throws Exception {
        var rBeforeCommands = new ArrayList<>(List.of("alias cloudquery='/app/cloudquery'"));
        rBeforeCommands.addAll(runContext.render(this.getBeforeCommands()).asList(String.class));

        var commands = commands(runContext)
            .withWarningOnStdErr(runContext.render(this.getWarningOnStdErr()).as(Boolean.class).orElse(true))
            .withInterpreter(this.getInterpreter())
            .withBeforeCommands(Property.ofValue(rBeforeCommands))
            .withCommands(this.commands);

        return commands.run();
    }

    @Override
    protected DockerOptions injectDefaults(RunContext runContext, DockerOptions original) throws IllegalVariableEvaluationException {
        if (original == null) {
            return null;
        }

        if (original.getEntryPoint() == null || original.getEntryPoint().isEmpty()) {
            original = original.toBuilder().entryPoint(List.of("")).build();
        }
        return super.injectDefaults(runContext, original);
    }
}
