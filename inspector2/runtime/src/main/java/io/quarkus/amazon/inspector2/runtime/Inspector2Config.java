package io.quarkus.amazon.inspector2.runtime;

import io.quarkus.amazon.common.runtime.AsyncHttpClientConfig;
import io.quarkus.amazon.common.runtime.HasAmazonClientRuntimeConfig;
import io.quarkus.amazon.common.runtime.SyncHttpClientConfig;
import io.quarkus.runtime.annotations.ConfigDocSection;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "quarkus.inspector2")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface Inspector2Config extends HasAmazonClientRuntimeConfig {
    /**
     * Sync HTTP transport configurations
     */
    @ConfigDocSection
    SyncHttpClientConfig syncClient();

    /**
     * Async HTTP transport configurations
     */
    @ConfigDocSection
    AsyncHttpClientConfig asyncClient();
}
