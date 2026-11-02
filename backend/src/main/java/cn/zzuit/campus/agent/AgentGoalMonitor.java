package cn.zzuit.campus.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Durable station-notice follow-up; unchanged states and provider usage stay quiet. */
@Component
public class AgentGoalMonitor {
 private final AgentGoalService goals;
 private final boolean enabled;
 public AgentGoalMonitor(AgentGoalService goals,@Value("${app.agent.goals.monitor.enabled:true}") boolean enabled){this.goals=goals;this.enabled=enabled;}
 @Scheduled(fixedDelayString="${app.agent.goals.monitor.interval-ms:60000}",initialDelayString="${app.agent.goals.monitor.initial-delay-ms:60000}")
 public void scheduled(){if(enabled)check();}
 public int check(){return goals.pollMonitored();}
}
