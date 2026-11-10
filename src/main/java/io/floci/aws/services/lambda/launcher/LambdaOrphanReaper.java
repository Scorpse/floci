package io.floci.aws.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Removes the Lambda function containers, and their code volumes, that an earlier run of this
 * Floci left behind. A Floci that is killed or crashes never stops its warm containers; they stay
 * as {@code Exited} for good, because the pool of the next run does not know them. At startup the
 * pool is empty, so every container labelled with this Floci's {@code floci_namespace} and
 * {@code io.floci.service=lambda} is an orphan.
 *
 * <p>Without a {@code floci.docker.resource-namespace} nothing is removed: the labels then match
 * every Floci on the daemon.
 */
@ApplicationScoped
public class LambdaOrphanReaper {

    private static final Logger LOG = Logger.getLogger(LambdaOrphanReaper.class);

    private final EmulatorConfig config;
    private final DockerClient dockerClient;

    @Inject
    public LambdaOrphanReaper(EmulatorConfig config, DockerClient dockerClient) {
        this.config = config;
        this.dockerClient = dockerClient;
    }

    void onStart(@Observes StartupEvent event) {
        if (!config.services().lambda().enabled() || !config.services().lambda().reapOrphansOnStart()
                || !"docker".equals(config.services().lambda().executor().trim().toLowerCase(Locale.ROOT))) {
            return;
        }
        try {
            int removed = reap();
            if (removed > 0) {
                LOG.infov("Removed {0} Lambda function container(s) left by an earlier run", removed);
            }
        } catch (RuntimeException e) {
            LOG.warnv("Could not look for Lambda containers left by an earlier run: {0}", e.getMessage());
        }
    }

    /** Removes the orphans; returns how many containers went. */
    int reap() {
        String namespace = ContainerStorageHelper.resourceNamespace(config);
        if (namespace.isBlank()) {
            LOG.debug("No floci.docker.resource-namespace: Lambda containers are not reaped at startup");
            return 0;
        }
        List<Container> orphans = dockerClient.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of("floci_namespace", namespace, ContainerStorageHelper.SERVICE_LABEL, "lambda"))
                .exec();
        String codeVolumePrefix = ContainerLauncher.codeVolumeNamePrefix(config);
        int removed = 0;
        for (Container orphan : orphans) {
            List<String> volumes = codeVolumesOf(orphan.getId(), codeVolumePrefix);
            try {
                dockerClient.removeContainerCmd(orphan.getId()).withForce(true).exec();
                removed++;
            } catch (RuntimeException e) {
                LOG.warnv("Could not remove orphaned Lambda container {0}: {1}", orphan.getId(), e.getMessage());
                continue;
            }
            for (String volume : volumes) {
                try {
                    dockerClient.removeVolumeCmd(volume).exec();
                } catch (RuntimeException e) {
                    // Still used by another function container, or already gone.
                    LOG.debugv("Kept Lambda code volume {0}: {1}", volume, e.getMessage());
                }
            }
        }
        return removed;
    }

    private List<String> codeVolumesOf(String containerId, String codeVolumePrefix) {
        try {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            List<InspectContainerResponse.Mount> mounts = inspect.getMounts();
            if (mounts == null) {
                return List.of();
            }
            return mounts.stream()
                    .map(InspectContainerResponse.Mount::getName)
                    .filter(name -> name != null && name.startsWith(codeVolumePrefix))
                    .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
