package cn.zzuit.campus.agent;

import static cn.zzuit.campus.agent.AgentModels.*;
import cn.zzuit.campus.certificate.CertificateModels;
import cn.zzuit.campus.certificate.CertificateService;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Collection only. Identity confirmation and saving stay in the ordinary certificate API. */
@Service
public class AgentCertificateDrafts {
    private final IdentityService identities;
    private final CertificateService certificates;
    private final AgentGuides guides;
    public AgentCertificateDrafts(IdentityService identities,CertificateService certificates,AgentGuides guides) { this.identities=identities; this.certificates=certificates; this.guides=guides; }
    public void require(CampusPrincipal user) { identities.requirePermission(user,"agent:query"); identities.requirePermission(user,"certificate:apply"); }
    public CertificateFields normalize(CertificateFields fields) { return new CertificateFields(fields.purpose()==null?"":fields.purpose().trim()); }
    public CertificateFields merge(CertificateFields original,Plan plan) {
        if(plan.calls()==null || plan.calls().size()!=1 || plan.calls().getFirst()==null || !"collect_certificate_fields".equals(plan.calls().getFirst().name())) throw rejected();
        var patch=plan.calls().getFirst().arguments();
        if(patch==null || !patch.isObject()) throw rejected();
        String purpose=original.purpose();
        for(var entry:patch.properties()) {
            var value=entry.getValue();
            if(!entry.getKey().equals("purpose") || !value.isString() || value.asText().isBlank() || value.asText().length()>300) throw rejected();
            purpose=value.asText().trim();
        }
        return new CertificateFields(purpose);
    }
    public CertificatePreview preview(CampusPrincipal user,String run,Mode mode,CertificateFields fields) {
        require(user);
        var missing=fields.purpose().isBlank()?List.of("purpose"):List.<String>of();
        var issues=new ArrayList<String>();
        CertificateModels.Identity identity=null; boolean canSubmit=false; String eligibilityMessage="";
        try { var eligibility=certificates.eligibility(user); identity=eligibility.identity(); canSubmit=eligibility.canSubmit(); eligibilityMessage=eligibility.message(); }
        catch(ApiException ex) { if(!ex.code().equals("IDENTITY_INCOMPLETE")) throw ex; issues.add(ex.getMessage()); }
        Guide source=null;
        try { source=guides.detail("certificate"); }
        catch(ApiException ex) { if(ex.status()!=HttpStatus.NOT_FOUND) throw ex; issues.add("在校证明指南已撤下，请进入原证明页面核对办理要求。"); }
        boolean ready=missing.isEmpty() && issues.isEmpty() && identity!=null;
        String summary=!missing.isEmpty()?"这份在校证明用来做什么？请补充实际用途，例如实习单位核验在校身份。"
            :!issues.isEmpty()?"暂不能保存预览，请按提示核对资料。":"用途已整理，请核对本人登记资料并亲自确认，再保存草稿。";
        return new CertificatePreview(run,mode,mode==Mode.DEEPSEEK,fields,identity,missing,List.copyOf(issues),ready,summary,source,canSubmit,eligibilityMessage,LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
    }
    private ApiException rejected() { return new ApiException(HttpStatus.BAD_GATEWAY,"MODEL_CERTIFICATE_FIELDS_REJECTED","模型未返回有效的证明用途，原预览已保留，请重试或在表单填写"); }
}
