package io.kestra.plugin.cloudquery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.tasks.*;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.scripts.exec.scripts.models.RunnerType;
import io.kestra.plugin.scripts.exec.scripts.models.ScriptOutput;
import io.kestra.plugin.scripts.exec.scripts.runners.CommandsWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

import javax.validation.constraints.NotNull;
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
            title = "Start a CloudQuery sync based on a YAML configuration.",
            full = true,
            code = """
                id: cloudquery_sync
                namespace: dev

                tasks:
                  - id: hn_to_duckdb
                    type: io.kestra.plugin.cloudquery.Sync
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
                namespace: dev

                tasks:
                  - id: hn_to_duckdb
                    type: io.kestra.plugin.cloudquery.Sync
                    incremental: false
                    env:
                        AWS_ACCESS_KEY_ID: "{{ secret('AWS_ACCESS_KEY_ID') }}"
                        AWS_SECRET_ACCESS_KEY: "{{ secret('AWS_SECRET_ACCESS_KEY') }}"
                        AWS_DEFAULT_REGION: "{{ secret('AWS_DEFAULT_REGION') }}"
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
    @PluginProperty(
        dynamic = false
    )
    @NotNull
    private List<Object> configs;

    @Schema(
        title = "Whether to use Kestra's internal backend to save incremental index.",
        description = "Kestra can automatically add a backend option to your sources and same incremental indexes in the internal storage. " +
            "Use this boolean to activate this option."
    )
    @PluginProperty
    @Builder.Default
    private boolean incremental = false;

    private NamespaceFiles namespaceFiles;

    private Object inputFiles;

    private List<String> outputFiles;

    @Override
    public ScriptOutput run(RunContext runContext) throws Exception {
        CommandsWrapper commands = new CommandsWrapper(runContext)
            .withWarningOnStdErr(true)
            .withRunnerType(RunnerType.DOCKER)
            .withDockerOptions(injectDefaults(getDocker()))
            .withEnv(this.getEnv())
            .withNamespaceFiles(namespaceFiles)
            .withInputFiles(inputFiles)
            .withOutputFiles(outputFiles);
        
        Path workingDirectory = commands.getWorkingDirectory();

        File incrementalDBFile = new File(workingDirectory + "/" + DB_FILENAME);

        try {
            InputStream taskCacheFile = runContext.getTaskStateFile(CLOUD_QUERY_STATE, DB_FILENAME);
            Files.copy(taskCacheFile, incrementalDBFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (FileNotFoundException exception) {
            if (!incrementalDBFile.createNewFile()) {
                throw new IOException("Unable to create incremental backend file.");
            }
        }

        Map<String, Object> backendOptionsObject = getBackendOptionObject();
        List<Map<String, Object>> configs = readConfigs(runContext, this.configs, backendOptionsObject);
        if (incremental) {
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
        runContext.putTaskStateFile(incrementalDBFile, CLOUD_QUERY_STATE, DB_FILENAME);
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
                try (BufferedReader inputStream = new BufferedReader(new InputStreamReader(runContext.uriToInputStream(from)))) {
                    result = OBJECT_MAPPER.readValue(inputStream, new TypeReference<>() {
                    });
                }
            } else if (config instanceof Map) {
                result = new HashMap<>((Map<String, Object>) config);
            } else {
                throw new IllegalVariableEvaluationException("Invalid configs type '" + configs.getClass() + "'");
            }


            if (incremental && Objects.equals(result.get("kind"), "source")) {
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
