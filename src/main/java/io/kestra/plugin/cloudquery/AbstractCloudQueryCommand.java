package io.kestra.plugin.cloudquery;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.tasks.runners.TaskRunner;
import io.kestra.plugin.scripts.exec.scripts.models.DockerOptions;
import io.kestra.plugin.scripts.runner.docker.Docker;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.Collections;
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
abstract class AbstractCloudQueryCommand extends Task {
    protected static final String DEFAULT_IMAGE = "ghcr.io/cloudquery/cloudquery:latest";

    @Schema(
        title = "Set CloudQuery environment variables",
        description = "Key-value pairs rendered by Kestra and passed to the CloudQuery process; empty by default."
    )
    protected Property<Map<String, String>> env;

    @Schema(
        title = "Deprecated Docker runner options",
        description = "Replaced by 'taskRunner'; keep only for legacy flows."
    )
    @PluginProperty
    @Deprecated
    private DockerOptions docker;

    @Schema(
        title = "Choose the task runner",
        description = """
            Defaults to the Docker runner with an empty entrypoint. If you switch runners, ensure the entrypoint suits the CloudQuery binary."""
    )
    @PluginProperty
    @Builder.Default
    @Valid
    private TaskRunner<?> taskRunner = Docker.builder()
        .type(Docker.class.getName())
        .entryPoint(Collections.emptyList())
        .build();

    @Schema(
        title = "Container image for CloudQuery runner",
        description = "Used when the selected task runner is container-based; defaults to ghcr.io/cloudquery/cloudquery:latest."
    )
    @Builder.Default
    private Property<String> containerImage = Property.ofValue(DEFAULT_IMAGE);

    protected DockerOptions injectDefaults(DockerOptions original) {
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
