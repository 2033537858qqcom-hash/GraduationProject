package cn.zzuit.campus.repair;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration @EnableScheduling
public class RepairClockConfiguration {
    @Bean Clock repairClock() {return Clock.system(ZoneId.of("Asia/Shanghai"));}
}
