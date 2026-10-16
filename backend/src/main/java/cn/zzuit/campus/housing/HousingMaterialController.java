package cn.zzuit.campus.housing;

import static cn.zzuit.campus.housing.HousingMaterialModels.*;
import cn.zzuit.campus.housing.HousingTransferModels.ActionInput;
import cn.zzuit.campus.identity.CampusPrincipal;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/housing/transfers")
public class HousingMaterialController {
    private final HousingMaterialService service;
    public HousingMaterialController(HousingMaterialService service) {this.service=service;}
    @GetMapping("/{view:mine|review}/{id}/materials") Bundle list(@AuthenticationPrincipal CampusPrincipal user,@PathVariable String view,@PathVariable long id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize) {return service.list(user,view,id,page,pageSize);}
    @PostMapping("/mine/{id}/materials") Bundle upload(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id,@Valid @ModelAttribute Upload input,@RequestParam MultipartFile file)throws java.io.IOException {return service.upload(user,id,input,file);}
    @PostMapping("/mine/{id}/materials/{file}/remove") Bundle remove(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id,@PathVariable long file,@Valid @RequestBody ActionInput input) {return service.remove(user,id,file,input);}
    @GetMapping("/{view:mine|review}/{id}/materials/{file}") ResponseEntity<byte[]> download(@AuthenticationPrincipal CampusPrincipal user,@PathVariable String view,@PathVariable long id,@PathVariable long file,@RequestParam(required=false) Long submission) {
        var data=service.download(user,view,id,file,submission);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(data.type()))
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(data.name(),StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","sandbox").body(data.bytes());
    }
}
