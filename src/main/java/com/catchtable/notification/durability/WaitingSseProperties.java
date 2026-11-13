package com.catchtable.notification.durability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("waiting.sse")
public record WaitingSseProperties(boolean deliveryEnabled, boolean schedulingEnabled,
                                   int maxRetainedRoots, int maxTargetsPerRoot, int maxMessageBytes,
                                   long leaseSeconds, int maxRootsPerPoll, long maxRunMillis) {
    // Preserve existing independent fault tools and manual one-root workers.
    public WaitingSseProperties(boolean deliveryEnabled, boolean schedulingEnabled, int maxRetainedRoots,
                                int maxTargetsPerRoot, int maxMessageBytes, long leaseSeconds) {
        this(deliveryEnabled, schedulingEnabled, maxRetainedRoots, maxTargetsPerRoot, maxMessageBytes, leaseSeconds, 64, 250);
    }

    @ConstructorBinding
    public WaitingSseProperties {
        if (maxRetainedRoots < 1 || maxTargetsPerRoot < 1 || maxMessageBytes < 256 || leaseSeconds < 1
                || maxRootsPerPoll < 1 || maxRunMillis < 1 || maxRunMillis > Long.MAX_VALUE / 1_000_000)
            throw new IllegalArgumentException("Invalid waiting SSE journal limits");
    }
}
