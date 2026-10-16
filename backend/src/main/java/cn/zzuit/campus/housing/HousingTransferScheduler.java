package cn.zzuit.campus.housing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HousingTransferScheduler {
    private static final Logger LOG=LoggerFactory.getLogger(HousingTransferScheduler.class);
    private final HousingTransferService transfers;
    public HousingTransferScheduler(HousingTransferService transfers) {this.transfers=transfers;}
    @Scheduled(fixedDelayString="${app.housing.transfer-expiry-ms:600000}",initialDelayString="${app.housing.transfer-expiry-delay-ms:60000}")
    public void run() {try {transfers.processDue();} catch(RuntimeException ex) {LOG.warn("Housing transfer expiry failed. Error type: {}",ex.getClass().getSimpleName());}}
}
