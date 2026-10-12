package cn.zzuit.campus.repair;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.repair.reminders.enabled",havingValue="true",matchIfMissing=true)
public class RepairReminderScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(RepairReminderScheduler.class);
    private final RepairReminderService reminders;
    public RepairReminderScheduler(RepairReminderService reminders) {this.reminders=reminders;}
    @Scheduled(fixedDelayString="${app.repair.reminders.interval-ms:600000}",initialDelayString="${app.repair.reminders.initial-delay-ms:60000}")
    public void run() {
        try {reminders.processDue();}
        catch(RuntimeException ex) {LOG.warn("Repair reminder batch failed; records and notices rolled back. Error type: {}",ex.getClass().getSimpleName());}
    }
}
