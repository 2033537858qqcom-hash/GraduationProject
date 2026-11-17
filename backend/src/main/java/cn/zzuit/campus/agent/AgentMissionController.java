package cn.zzuit.campus.agent;

import static cn.zzuit.campus.agent.AgentMissionModels.*;
import cn.zzuit.campus.identity.CampusPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/assistant/sessions/{session}/missions")
public class AgentMissionController {
    private final AgentMissionService missions;
    public AgentMissionController(AgentMissionService missions){this.missions=missions;}
    @GetMapping List<Summary> list(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long session){return missions.list(user,session);}
    @PostMapping View create(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long session,@Valid @RequestBody Create input){return missions.create(user,session,input);}
    @PostMapping(value="/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE) SseEmitter streamCreate(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long session,@Valid @RequestBody Create input){return missions.streamCreate(user,session,input);}
    @GetMapping("/{id}") View detail(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long session,@PathVariable long id){return missions.detail(user,session,id);}
    @PostMapping("/{id}/actions") View action(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long session,@PathVariable long id,@Valid @RequestBody Action input){return missions.action(user,session,id,input);}
    @PostMapping(value="/{id}/actions/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE) SseEmitter streamAction(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long session,@PathVariable long id,@Valid @RequestBody Action input){return missions.streamAction(user,session,id,input);}
}
