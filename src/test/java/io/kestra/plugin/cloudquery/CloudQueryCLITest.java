package io.kestra.plugin.cloudquery;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.NamespaceFiles;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.scripts.exec.scripts.models.ScriptOutput;
import io.kestra.plugin.scripts.runner.docker.Docker;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

@KestraTest
@Testcontainers
class CloudQueryCLITest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    @SuppressWarnings("unchecked")
    void run() throws Exception {
        String envKey = "MY_KEY";
        String envValue = "MY_VALUE";

        CloudQueryCLI execute = CloudQueryCLI.builder()
            .id(IdUtils.create())
            .type(CloudQueryCLI.class.getName())
            .env(Property.ofValue(Map.of("{{ inputs.envKey }}", "{{ inputs.envValue }}")))
            .commands(
                TestsUtils.propertyFromList(
                    List.of(
                        "echo \"::{\\\"outputs\\\":{" +
                            "\\\"customEnv\\\":\\\"$" + envKey + "\\\"" +
                            "}}::\"",
                        "cloudquery --version --log-console"
                    )
                )
            )
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, execute, Map.of("envKey", envKey, "envValue", envValue));

        ScriptOutput runOutput = execute.run(runContext);

        assertThat(runOutput.getExitCode(), is(0));
        assertThat(runOutput.getVars().get("customEnv"), is(envValue));

    }

    @Test
    void namespaceFiles() throws Exception {
        CloudQueryCLI execute = CloudQueryCLI.builder()
            .id(IdUtils.create())
            .type(CloudQueryCLI.class.getName())
            .namespaceFiles(
                NamespaceFiles.builder()
                    .enabled(Property.ofValue(true))
                    .build()
            )
            .commands(Property.ofValue(List.of("test -f config.yml")))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, execute, Map.of());

        runContext.storage().namespace().putFile(
            Path.of("config.yml"),
            new ByteArrayInputStream("kind: source".getBytes())
        );

        ScriptOutput runOutput = execute.run(runContext);

        assertThat(runOutput.getExitCode(), is(0));
    }

    @Test
    void defaultsToDockerWithEmptyEntrypoint() throws Exception {
        var task = CloudQueryCLI.builder()
            .id(IdUtils.create())
            .type(CloudQueryCLI.class.getName())
            .commands(Property.ofValue(List.of("cloudquery --version")))
            .build();

        assertThat(task.getTaskRunner(), instanceOf(Docker.class));
        assertThat(((Docker) task.getTaskRunner()).getEntryPoint(), is(empty()));

        var runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of());
        assertThat(runContext.render(task.getContainerImage()).as(String.class).orElseThrow(), is("ghcr.io/cloudquery/cloudquery:latest-ubuntu"));
    }

    @Test
    void aliasKeptWithUserBeforeCommands() throws Exception {
        var execute = CloudQueryCLI.builder()
            .id(IdUtils.create())
            .type(CloudQueryCLI.class.getName())
            .beforeCommands(Property.ofValue(List.of("export MY_BEFORE=ok")))
            .commands(
                TestsUtils.propertyFromList(
                    List.of(
                        "echo \"::{\\\"outputs\\\":{\\\"before\\\":\\\"$MY_BEFORE\\\"}}::\"",
                        "cloudquery --version --log-console"
                    )
                )
            )
            .build();

        var runContext = TestsUtils.mockRunContext(runContextFactory, execute, Map.of());
        var runOutput = execute.run(runContext);

        assertThat(runOutput.getExitCode(), is(0));
        assertThat(runOutput.getVars().get("before"), is("ok"));
    }

    @Test
    void beforeCommandsCanUseWorkingDir() throws Exception {
        var execute = CloudQueryCLI.builder()
            .id(IdUtils.create())
            .type(CloudQueryCLI.class.getName())
            .beforeCommands(Property.ofValue(List.of("cd {{ workingDir }}", "export MY_BEFORE=ok")))
            .commands(
                TestsUtils.propertyFromList(
                    List.of("echo \"::{\\\"outputs\\\":{\\\"before\\\":\\\"$MY_BEFORE\\\"}}::\"")
                )
            )
            .build();

        var runContext = TestsUtils.mockRunContext(runContextFactory, execute, Map.of());
        var runOutput = execute.run(runContext);

        assertThat(runOutput.getExitCode(), is(0));
        assertThat(runOutput.getVars().get("before"), is("ok"));
    }
}
