package com.suretyseven.service.scoring;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "underwriting.thresholds")
public record UnderwritingThresholds(int approveAt, int referAt) {
    public UnderwritingThresholds {
        if (approveAt <= referAt) {
            throw new IllegalStateException("underwriting.thresholds.approve-at must be greater than refer-at");
        }
    }
}
