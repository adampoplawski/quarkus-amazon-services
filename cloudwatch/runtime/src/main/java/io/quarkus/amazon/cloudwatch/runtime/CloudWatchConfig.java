package io.quarkus.amazon.cloudwatch.runtime;

import io.quarkus.amazon.common.runtime.AsyncHttpClientConfig;
import io.quarkus.amazon.common.runtime.HasAmazonClientRuntimeConfig;
import io.quarkus.amazon.common.runtime.SyncHttpClientConfig;
import io.quarkus.runtime.annotations.ConfigDocSection;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "quarkus.cloudwatch")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface CloudWatchConfig extends HasAmazonClientRuntimeConfig {
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
