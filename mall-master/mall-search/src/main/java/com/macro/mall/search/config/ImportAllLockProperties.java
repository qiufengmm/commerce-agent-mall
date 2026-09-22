package com.macro.mall.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * importAll 分布式锁配置。
 */
@Component
@ConfigurationProperties(prefix = "mall.search.import-lock")
public class ImportAllLockProperties {
    private boolean enabled = true;
    private String key = "mall:search:import-all";
    private long leaseSeconds = 1800;
    private long renewIntervalSeconds = 30;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public long getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(long leaseSeconds) {
        this.leaseSeconds = leaseSeconds;
    }

    public long getRenewIntervalSeconds() {
        return renewIntervalSeconds;
    }

    public void setRenewIntervalSeconds(long renewIntervalSeconds) {
        this.renewIntervalSeconds = renewIntervalSeconds;
    }
}
