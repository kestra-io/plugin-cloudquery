package io.kestra.plugin.cloudquery;

import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.scripts.exec.scripts.models.ScriptOutput;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@MicronautTest
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
            .env(Map.of("{{ inputs.envKey }}", "{{ inputs.envValue }}"))
            .commands(List.of(
                "--version --log-console"
            ))
            .build();

        RunContext runContext = TestsUtils.mockRunContext(runContextFactory, execute, Map.of("envKey", envKey, "envValue", envValue));

        ScriptOutput runOutput = execute.run(runContext);

        assertThat(runOutput.getExitCode(), is(0));
    }
}