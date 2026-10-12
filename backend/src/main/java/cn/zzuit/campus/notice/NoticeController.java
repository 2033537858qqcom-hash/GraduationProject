package cn.zzuit.campus.notice;

import cn.zzuit.campus.identity.CampusPrincipal;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/notices")
public class NoticeController {
    private final NoticeService service;
    public NoticeController(NoticeService service) { this.service=service; }
    @GetMapping NoticeService.Page list(@AuthenticationPrincipal CampusPrincipal user,@RequestParam(required=false) Boolean read,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) { return service.list(user,read,page,pageSize); }
    @PostMapping("/{id}/read") Map<String,Boolean> read(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id) { service.read(user,id); return Map.of("read",true); }
}
