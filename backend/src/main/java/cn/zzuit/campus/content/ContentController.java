package cn.zzuit.campus.content;

import cn.zzuit.campus.identity.CampusPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.time.LocalDateTime;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class ContentController {
    private final ContentService content;
    public ContentController(ContentService content) { this.content = content; }

    @GetMapping("/api/public/content")
    List<PublicContent> published(@RequestParam(required=false) String category) {
        return content.published(category).stream().map(PublicContent::from).toList();
    }
    @GetMapping("/api/public/content/{slug}")
    PublicContent detail(@PathVariable String slug) { return PublicContent.from(content.publicDetail(slug)); }
    @GetMapping("/api/admin/content")
    List<ContentService.Content> all(@AuthenticationPrincipal CampusPrincipal user) { return content.all(user); }
    @PostMapping("/api/admin/content")
    @PreAuthorize("hasAuthority('content:edit')")
    ContentService.Content create(@Valid @RequestBody ContentService.ContentInput input,
                                  @AuthenticationPrincipal CampusPrincipal user) { return content.create(input,user); }
    @PutMapping("/api/admin/content/{id}")
    @PreAuthorize("hasAuthority('content:edit')")
    ContentService.Content update(@PathVariable long id, @Valid @RequestBody ContentService.ContentInput input,
                                  @AuthenticationPrincipal CampusPrincipal user) { return content.update(id,input,user); }
    @PostMapping("/api/admin/content/{id}/submit")
    @PreAuthorize("hasAuthority('content:edit')")
    ContentService.Content submit(@PathVariable long id, @Valid @RequestBody Version input,
                                  @AuthenticationPrincipal CampusPrincipal user) { return content.submit(id,input.version(),user); }
    @PostMapping("/api/admin/content/{id}/publish")
    @PreAuthorize("hasAuthority('content:review')")
    ContentService.Content publish(@PathVariable long id, @Valid @RequestBody Version input,
                                   @AuthenticationPrincipal CampusPrincipal user) { return content.publish(id,input.version(),user); }
    @PostMapping("/api/admin/content/{id}/reject")
    @PreAuthorize("hasAuthority('content:review')")
    ContentService.Content reject(@PathVariable long id,@Valid @RequestBody Reason input,@AuthenticationPrincipal CampusPrincipal user) { return content.reject(id,input.version(),input.reason(),user); }
    @PostMapping("/api/admin/content/{id}/recall")
    @PreAuthorize("hasAuthority('content:edit')")
    ContentService.Content recall(@PathVariable long id,@Valid @RequestBody Reason input,@AuthenticationPrincipal CampusPrincipal user) { return content.recall(id,input.version(),input.reason(),user); }
    @PostMapping("/api/admin/content/{id}/revise")
    @PreAuthorize("hasAuthority('content:edit')")
    ContentService.Content revise(@PathVariable long id,@Valid @RequestBody Reason input,@AuthenticationPrincipal CampusPrincipal user) { return content.revise(id,input.version(),input.reason(),user); }
    @PostMapping("/api/admin/content/{id}/unpublish")
    @PreAuthorize("hasAuthority('content:review')")
    ContentService.Content unpublish(@PathVariable long id,@Valid @RequestBody Reason input,@AuthenticationPrincipal CampusPrincipal user) { return content.unpublish(id,input.version(),input.reason(),user); }
    @GetMapping("/api/admin/content/{id}/history")
    ContentService.HistoryPage history(@PathVariable long id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize,@AuthenticationPrincipal CampusPrincipal user) { return content.history(id,user,page,pageSize); }
    @GetMapping("/api/admin/content/{id}/revisions/{version}")
    ContentService.Revision revision(@PathVariable long id,@PathVariable int version,@AuthenticationPrincipal CampusPrincipal user) { return content.revision(id,version,user); }
    public record Version(@Min(0) int version) {}
    public record Reason(@Min(0) int version,@NotBlank @Size(max=480) String reason) {}
    public record PublicContent(long id, String slug, String category, String title, String summary,
                                String body, String sourceUrl, String sourceType, LocalDateTime publishedAt) {
        public static PublicContent from(ContentService.Content source) {
            return new PublicContent(source.id(),source.slug(),source.category(),source.title(),source.summary(),
                source.body(),source.sourceUrl(),source.sourceType(),source.publishedAt());
        }
    }
}
