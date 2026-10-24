package cn.zzuit.campus.affairs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.services.followup.enabled",havingValue="true",matchIfMissing=true)
public class ServiceFollowupScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(ServiceFollowupScheduler.class);
    private final ServiceFollowupService service;
    public ServiceFollowupScheduler(ServiceFollowupService service) { this.service=service; }
    @Scheduled(fixedDelayString="${app.services.followup.interval-ms:60000}",initialDelayString="${app.services.followup.initial-delay-ms:60000}")
    public void run() {
        try { service.processDue(); }
        catch(RuntimeException ex) { LOG.warn("Service follow-up batch rolled back; error type: {}",ex.getClass().getSimpleName()); }
    }
}
