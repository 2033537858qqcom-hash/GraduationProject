package cn.zzuit.campus.agent;

import static cn.zzuit.campus.agent.AgentSessionModels.*;
import cn.zzuit.campus.identity.CampusPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/assistant/sessions")
public class AgentSessionController {
    private final AgentSessions sessions;
    public AgentSessionController(AgentSessions sessions) { this.sessions=sessions; }
    @GetMapping Page<Session> list(@AuthenticationPrincipal CampusPrincipal user,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize) { return sessions.list(user,page,pageSize); }
    @PostMapping Session create(@AuthenticationPrincipal CampusPrincipal user,@Valid @RequestBody CreateSession input) { return sessions.create(user,input); }
    @GetMapping("/{id}") SessionDetail detail(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id,@RequestParam(required=false) Long before) { return sessions.detail(user,id,before); }
    @PostMapping("/{id}/query") QueryResult query(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id,@Valid @RequestBody SessionQuery input) { return sessions.query(user,id,input); }
    @PostMapping("/{id}/tasks") TaskView createTask(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id,@Valid @RequestBody CreateTask input) { return sessions.createTask(user,id,input); }
    @GetMapping("/{id}/tasks/{task}") TaskView task(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id,@PathVariable long task) { return sessions.task(user,id,task); }
    @PostMapping("/{id}/tasks/{task}/leave") TaskView leave(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id,@PathVariable long task,@Valid @RequestBody TaskLeave input) { return sessions.prepareLeave(user,id,task,input); }
    @PostMapping("/{id}/tasks/{task}/certificate") TaskView certificate(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id,@PathVariable long task,@Valid @RequestBody TaskCertificate input) { return sessions.prepareCertificate(user,id,task,input); }
    @PostMapping("/{id}/tasks/{task}/repair") TaskView repair(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id,@PathVariable long task,@Valid @RequestBody TaskRepair input) { return sessions.prepareRepair(user,id,task,input); }
    @PostMapping("/{id}/tasks/{task}/housing") TaskView housing(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id,@PathVariable long task,@Valid @RequestBody TaskHousing input) { return sessions.prepareHousing(user,id,task,input); }
    @PostMapping("/{id}/tasks/{task}/save") TaskView save(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id,@PathVariable long task,@Valid @RequestBody ConfirmTask input) { return sessions.save(user,id,task,input); }
}
