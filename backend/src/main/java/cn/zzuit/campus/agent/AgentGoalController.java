package cn.zzuit.campus.agent;

import static cn.zzuit.campus.agent.AgentGoalModels.*;
import cn.zzuit.campus.identity.CampusPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/assistant/sessions/{session}/goals")
public class AgentGoalController {
 private final AgentGoalService goals;
 public AgentGoalController(AgentGoalService goals){this.goals=goals;}
 @GetMapping List<Summary> list(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long session){return goals.list(user,session);}
 @PostMapping View create(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long session,@Valid @RequestBody Create input){return goals.create(user,session,input);}
 @GetMapping("/{id}") View detail(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long session,@PathVariable long id){return goals.detail(user,session,id);}
 @PostMapping("/{id}/actions") View action(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long session,@PathVariable long id,@Valid @RequestBody Action input){return goals.action(user,session,id,input);}
}
