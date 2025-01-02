package io.kestra.plugin.cloudquery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.*;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.storages.StorageContext;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.scripts.exec.scripts.models.ScriptOutput;
import io.kestra.plugin.scripts.exec.scripts.runners.CommandsWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

import jakarta.validation.constraints.NotNull;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

import static io.kestra.core.utils.Rethrow.throwConsumer;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Execute a CloudQuery sync."
)
@Plugin(
    examples = {
        @Example(
            title = "Start a CloudQuery sync based on a YAML configuration. You need an [API key](https://docs.cloudquery.io/docs/deployment/generate-api-key) to download plugins. You can add the API key as an environment variable called `CLOUDQUERY_API_KEY`.",
            full = true,
            code = """
                id: cloudquery_sync
                namespace: company.team

                tasks:
                  - id: hn_to_duckdb
                    type: io.kestra.plugin.cloudquery.Sync
                    env:
                      CLOUDQUERY_API_KEY: "{{ secret('CLOUDQUERY_API_KEY') }}"
                    incremental: false
                    configs:
                      - kind: source
                        spec:
                          name: hackernews
                          path: cloudquery/hackernews
                          version: v3.0.13
                          tables: ["*"]
                          destinations: ["duckdb"]
                          spec:
                            item_concurrency: 100
                            start_time: "{{ now() | dateAdd(-1, 'DAYS') }}"
                      - kind: destination
                        spec:
                          name: duckdb
                          path: cloudquery/duckdb
                          version: v4.2.10
                          write_mode: overwrite-delete-stale
                          spec:
                            connection_string: hn.db"""
        ),
        @Example(
            title = "Start a CloudQuery sync based on a file(s) input.",
            full = true,
            code = """
                id: cloudquery_sync
                namespace: company.team

                tasks:
                  - id: hn_to_duckdb
                    type: io.kestra.plugin.cloudquery.Sync
                    incremental: false
                    env:
                        AWS_ACCESS_KEY_ID: "{{ secret('AWS_ACCESS_KEY_ID') }}"
                        AWS_SECRET_ACCESS_KEY: "{{ secret('AWS_SECRET_ACCESS_KEY') }}"
                        AWS_DEFAULT_REGION: "{{ secret('AWS_DEFAULT_REGION') }}"
                        CLOUDQUERY_API_KEY: "{{ secret('CLOUDQUERY_API_KEY') }}"
                        PG_CONNECTION_STRING: "postgresql://postgres:{{ secret('DB_PASSWORD') }}@host.docker.internal:5432/demo?sslmode=disable"
                    configs:
                      - sources.yml
                      - destination.yml"""
        )
    }
)
public class Sync extends AbstractCloudQueryCommand implements RunnableTask<ScriptOutput>, NamespaceFilesInterface, InputFilesInterface, OutputFilesInterface {
    private static final ObjectMapper OBJECT_MAPPER = JacksonMapper.ofYaml();
    private static final String DB_FILENAME = "icrementaldb.sqlite";
    private static final String CLOUD_QUERY_STATE = "CloudQueryState";

    @Schema(
        title = "CloudQuery configurations.",
        description = "A list of CloudQuery configurations or files containing CloudQuery configurations.",
        anyOf = {String[].class, Map[].class}
    )
    @PluginProperty
    @NotNull
    private List<Object> configs;

    @Schema(
        title = "Whether to use Kestra's internal KV Store backend to save incremental index.",
        description = "Kestra can automatically add a backend option to your sources and store the incremental indexes in the KV Store. " +
            "Use this boolean to activate this option."
    )
    @Builder.Default
    private Property<Boolean> incremental = Property.of(false);

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
            .withEnv(runContext.render(this.getEnv()).asMap(String.class, String.class).isEmpty() ? new HashMap<>() : runContext.render(this.getEnv()).asMap(String.class, String.class))
            .withNamespaceFiles(namespaceFiles)
            .withInputFiles(inputFiles)
            .withOutputFiles(renderedOutputFiles.isEmpty() ? null : renderedOutputFiles);

        Path workingDirectory = commands.getWorkingDirectory();

        File incrementalDBFile = new File(workingDirectory + "/" + DB_FILENAME);

        try {
            InputStream taskCacheFile = runContext.stateStore().getState(
                CLOUD_QUERY_STATE,
                DB_FILENAME,
                runContext.storage().getTaskStorageContext().map(StorageContext.Task::getTaskRunValue).orElse(null)
            );
            Files.copy(taskCacheFile, incrementalDBFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (FileNotFoundException exception) {
            if (!incrementalDBFile.createNewFile()) {
                throw new IOException("Unable to create incremental backend file.");
            }
        }

        Map<String, Object> backendOptionsObject = getBackendOptionObject();
        List<Map<String, Object>> configs = readConfigs(runContext, this.configs, backendOptionsObject);
        if (runContext.render(incremental).as(Boolean.class).orElseThrow()) {
            configs.add(getIncrementalSqliteDestination());
        }


        List<String> cmds = new ArrayList<>(List.of("sync"));
        configs.forEach(throwConsumer(config -> {
            File confFile = new File(workingDirectory + "/" + IdUtils.create() + ".yml");
            OBJECT_MAPPER.writeValue(confFile, config);
            cmds.add(confFile.getName());
        }));

        commands = commands.withCommands(
            cmds
        );

        ScriptOutput run = commands.run();
        try (FileInputStream fis = new FileInputStream(incrementalDBFile)) {
            runContext.stateStore().putState(
                CLOUD_QUERY_STATE,
                DB_FILENAME,
                runContext.storage().getTaskStorageContext().map(StorageContext.Task::getTaskRunValue).orElse(null),
                fis.readAllBytes()
            );
        }
        return run;
    }

    private Map<String, Object> getIncrementalSqliteDestination() {
        return Map.of(
            "kind", "destination",
            "spec", Map.of(
                "name", "kestra_incremental_db",
                "path", "cloudquery/sqlite",
                "version", "v2.4.10",
                "spec", Map.of(
                    "connection_string", DB_FILENAME
                )
            )
        );
    }

    private Map<String, Object> getBackendOptionObject() {
        return Map.of(
            "table_name", "kestra_incremental_table",
            "connection", "@@plugins.kestra_incremental_db.connection"
        );
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readConfigs(RunContext runContext, List<Object> configurations, Map<String, Object> backendOptionsObject) throws IllegalVariableEvaluationException, URISyntaxException, IOException {
        List<Map<String, Object>> results = new ArrayList<>(configurations.size());
        for (Object config : configurations) {
            Map<String, Object> result;
            if (config instanceof String) {
                URI from = new URI(runContext.render((String) config));
                try (BufferedReader inputStream = new BufferedReader(new InputStreamReader(runContext.storage().getFile(from)))) {
                    result = OBJECT_MAPPER.readValue(inputStream, new TypeReference<>() {
                    });
                }
            } else if (config instanceof Map) {
                result = new HashMap<>((Map<String, Object>) config);
            } else {
                throw new IllegalVariableEvaluationException("Invalid configs type '" + configs.getClass() + "'");
            }


            if (runContext.render(incremental).as(Boolean.class).orElseThrow() && Objects.equals(result.get("kind"), "source")) {
                if (result.containsKey("spec")) {
                    Map<String, Object> spec = (Map<String, Object>) result.get("spec");
                    if (!spec.containsKey("backend_options")) {
                        spec = new HashMap<>((Map<String, Object>) result.get("spec"));
                        spec.put("backend_options", backendOptionsObject);
                        result.put("spec", spec);
                    }
                }
            }
            results.add(result);
        }

        return results;
    }
}
