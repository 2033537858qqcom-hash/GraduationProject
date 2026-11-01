package cn.zzuit.campus.agent;

import static cn.zzuit.campus.agent.AgentMissionModels.*;
import cn.zzuit.campus.identity.CampusPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/assistant/sessions/{session}/missions")
public class AgentMissionController {
    private final AgentMissionService missions;
    public AgentMissionController(AgentMissionService missions){this.missions=missions;}
    @GetMapping List<Summary> list(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long session){return missions.list(user,session);}
    @PostMapping View create(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long session,@Valid @RequestBody Create input){return missions.create(user,session,input);}
    @GetMapping("/{id}") View detail(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long session,@PathVariable long id){return missions.detail(user,session,id);}
    @PostMapping("/{id}/actions") View action(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long session,@PathVariable long id,@Valid @RequestBody Action input){return missions.action(user,session,id,input);}
}
