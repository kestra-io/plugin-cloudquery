package io.kestra.plugin.cloudquery;

import java.util.Collections;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.runners.TaskRunner;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.scripts.exec.AbstractExecScript;
import io.kestra.plugin.scripts.exec.scripts.models.DockerOptions;
import io.kestra.plugin.scripts.runner.docker.Docker;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
abstract class AbstractCloudQueryCommand extends AbstractExecScript {
    protected static final String DEFAULT_IMAGE = "ghcr.io/cloudquery/cloudquery:latest-ubuntu";

    @Schema(
        title = "Choose the task runner",
        description = """
            Defaults to the Docker runner with an empty entrypoint. If you switch runners, ensure the entrypoint suits the CloudQuery binary."""
    )
    @PluginProperty(group = "execution")
    @Builder.Default
    @Valid
    private TaskRunner<?> taskRunner = Docker.builder()
        .type(Docker.class.getName())
        .entryPoint(Collections.emptyList())
        .build();

    @Schema(
        title = "Container image for CloudQuery runner",
        description = "Container image used by the task runner. Defaults to the Ubuntu variant so destinations like DuckDB work out of the box. Set to ghcr.io/cloudquery/cloudquery:latest for the smaller Alpine image."
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<String> containerImage = Property.ofValue(DEFAULT_IMAGE);

    @Override
    protected DockerOptions injectDefaults(RunContext runContext, DockerOptions original) throws IllegalVariableEvaluationException {
        if (original == null) {
            return null;
        }

        var builder = original.toBuilder();
        if (original.getImage() == null) {
            builder.image(DEFAULT_IMAGE);
        }

        return builder.build();
    }
}
