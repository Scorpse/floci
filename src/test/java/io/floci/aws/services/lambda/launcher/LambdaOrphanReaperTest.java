package io.floci.aws.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.RemoveVolumeCmd;
import com.github.dockerjava.api.model.Container;
import io.floci.aws.config.EmulatorConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LambdaOrphanReaperTest {

    private static EmulatorConfig config(String namespace) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.DockerConfig docker = mock(EmulatorConfig.DockerConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.LambdaServiceConfig lambda = mock(EmulatorConfig.LambdaServiceConfig.class);
        when(config.docker()).thenReturn(docker);
        when(docker.resourceNamespace()).thenReturn(Optional.ofNullable(namespace));
        when(config.services()).thenReturn(services);
        when(services.lambda()).thenReturn(lambda);
        when(lambda.containerNamePrefix()).thenReturn(Optional.empty());
        return config;
    }

    @Test
    void doesNothingWithoutAResourceNamespace() {
        DockerClient docker = mock(DockerClient.class);

        assertEquals(0, new LambdaOrphanReaper(config(null), docker).reap());

        verify(docker, never()).listContainersCmd();
    }

    @Test
    @SuppressWarnings("unchecked")
    void removesTheLabelledContainersAndTheirCodeVolumesOnly() {
        DockerClient docker = mock(DockerClient.class);
        ListContainersCmd list = mock(ListContainersCmd.class);
        when(docker.listContainersCmd()).thenReturn(list);
        when(list.withShowAll(true)).thenReturn(list);
        when(list.withLabelFilter(any(Map.class))).thenReturn(list);
        Container orphan = mock(Container.class);
        when(orphan.getId()).thenReturn("c1");
        when(list.exec()).thenReturn(List.of(orphan));

        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        InspectContainerResponse.Mount code = mock(InspectContainerResponse.Mount.class);
        when(code.getName()).thenReturn("floci-aws-ci-code-fn-abc-123");
        InspectContainerResponse.Mount efs = mock(InspectContainerResponse.Mount.class);
        when(efs.getName()).thenReturn("floci-aws-ci-efs-shared");
        when(docker.inspectContainerCmd("c1")).thenReturn(inspect);
        when(inspect.exec()).thenReturn(response);
        when(response.getMounts()).thenReturn(List.of(code, efs));

        RemoveContainerCmd removeContainer = mock(RemoveContainerCmd.class);
        when(docker.removeContainerCmd("c1")).thenReturn(removeContainer);
        when(removeContainer.withForce(true)).thenReturn(removeContainer);
        RemoveVolumeCmd removeVolume = mock(RemoveVolumeCmd.class);
        when(docker.removeVolumeCmd(anyString())).thenReturn(removeVolume);

        assertEquals(1, new LambdaOrphanReaper(config("ci"), docker).reap());

        verify(removeContainer).exec();
        verify(docker).removeVolumeCmd("floci-aws-ci-code-fn-abc-123");
        verify(docker, never()).removeVolumeCmd("floci-aws-ci-efs-shared");
    }
}
