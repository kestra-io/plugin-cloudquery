package io.kestra.plugin.cloudquery;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.*;
import io.kestra.core.models.tasks.runners.ScriptService;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.scripts.exec.scripts.models.DockerOptions;
import io.kestra.plugin.scripts.exec.scripts.models.RunnerType;
import io.kestra.plugin.scripts.exec.scripts.models.ScriptOutput;
import io.kestra.plugin.scripts.exec.scripts.runners.CommandsWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import jakarta.validation.constraints.NotEmpty;

import java.util.HashMap;
import java.util.List;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Execute a CloudQuery command from CLI."
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
public class CloudQueryCLI extends AbstractCloudQueryCommand implements RunnableTask<ScriptOutput>, NamespaceFilesInterface, InputFilesInterface, OutputFilesInterface {

    @Schema(
        title = "List of CloudQuery commands to run."
    )
    @NotNull
    protected Property<List<String>> commands;

    private NamespaceFiles namespaceFiles;

    private Object inputFiles;

    private Property<List<String>> outputFiles;

    @Override
    public ScriptOutput run(RunContext runContext) throws Exception {
        var renderedOutputFiles = runContext.render(this.outputFiles).asList(String.class);
        CommandsWrapper commands = new CommandsWrapper(runContext)
            .withWarningOnStdErr(true)
            .withDockerOptions(injectDefaults(getDocker()))
            .withTaskRunner(this.getTaskRunner())
            .withContainerImage(runContext.render(this.getContainerImage()).as(String.class).orElseThrow())
            .withInterpreter(Property.ofValue(List.of("/bin/sh", "-c")))
            .withBeforeCommands(Property.ofValue(List.of("alias cloudquery='/app/cloudquery'")))
            .withCommands(this.commands)
            .withEnv(runContext.render(this.getEnv()).asMap(String.class, String.class).isEmpty() ? new HashMap<>() : runContext.render(this.getEnv()).asMap(String.class, String.class))
            .withInputFiles(inputFiles)
            .withOutputFiles(renderedOutputFiles.isEmpty() ? null : renderedOutputFiles);

        return commands.run();
    }

    @Override
    protected DockerOptions injectDefaults(DockerOptions original) {
        if (original == null) {
            return null;
        }

        if (original.getEntryPoint() == null || original.getEntryPoint().isEmpty()) {
            original = original.toBuilder().entryPoint(List.of("")).build();
        }
        return super.injectDefaults(original);
    }
}
