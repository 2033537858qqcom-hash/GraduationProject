package cn.zzuit.campus.agent;

import static cn.zzuit.campus.agent.AgentMissionModels.*;
import cn.zzuit.campus.agent.AgentModels.*;
import cn.zzuit.campus.certificate.CertificateModels;
import cn.zzuit.campus.certificate.CertificateService;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import java.util.*;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Certificate identity, purpose and guide snapshots stay local and are rechecked before saving. */
@Service
public class AgentCertificateMissionTools {
    private final AgentCertificateDrafts drafts;
    private final CertificateService certificates;
    public AgentCertificateMissionTools(AgentCertificateDrafts drafts,CertificateService certificates){this.drafts=drafts;this.certificates=certificates;}
    public CertificateFields normalize(CertificateFields fields){return drafts.normalize(fields==null?new CertificateFields(""):fields);}
    private CertificatePreview preview(CampusPrincipal user,State state){return drafts.preview(user,"mission-preview",Mode.LOCAL,state.certificateFields);}
    public List<String> allowed(State state){
        if(state.draftId!=null)return List.of(state.verified?"finish":"verify_certificate");
        if(state.certificateFields.purpose().isBlank())return List.of("request_input");
        if(!state.certificateChecked)return List.of("check_certificate_requirements");
        if(state.certificatePreview!=null&&!state.certificatePreview.ready())return List.of("request_input");
        if(!state.certificateIdentityChecked)return List.of("inspect_certificate_identity");
        if(!state.proposalReady)return List.of("prepare_certificate");
        return state.confirmed?List.of("save_certificate"):List.of();
    }
    public Object fingerprint(State state,CampusPrincipal user){
        var preview=state.certificatePreview;
        return Map.of("kind","CERTIFICATE","fields",state.certificateFields,"identity",preview.identity(),
            "guideVersion",preview.source().version(),"canSubmit",preview.canSubmit(),"user",user.id(),"securityVersion",user.securityVersion());
    }
    public void invalidate(State state,String message){
        state.confirmed=false;state.proposalReady=false;state.proposalHash=null;state.certificateChecked=false;
        state.certificateIdentityChecked=false;state.certificatePreview=null;state.missing=List.of();state.message=message;
    }
    private AgentMissionTools.Outcome result(String action,String status,String stage,State state){return new AgentMissionTools.Outcome(stage,new Observation(action,status,0,state.missing));}
    private void ready(CampusPrincipal user,State state){
        var current=preview(user,state);var expected=state.certificatePreview;
        if(!current.ready())throw changed("证明资料或指南已变化，请重新核对："+current.summary());
        if(expected==null||!Objects.equals(expected.identity(),current.identity())||expected.source()==null
            ||expected.source().version()!=current.source().version()||expected.canSubmit()!=current.canSubmit())
            throw changed("本人登记资料、办理资格或指南版本已变化，请重新核对并确认");
    }
    public AgentMissionTools.Outcome execute(CampusPrincipal user,String action,State state,String key,Function<Object,String> hash){
        drafts.require(user);
        switch(action){
            case "request_input" -> {
                state.certificatePreview=preview(user,state);state.missing=state.certificatePreview.missing();state.message=state.certificatePreview.summary();
                return result(action,"NEEDS_INPUT","WAITING_INPUT",state);
            }
            case "check_certificate_requirements" -> {
                state.certificatePreview=preview(user,state);state.missing=state.certificatePreview.missing();state.certificateChecked=true;
                state.message=state.certificatePreview.ready()?"已核对实际证明用途和有效办理指南。":state.certificatePreview.summary();
                return result(action,state.certificatePreview.ready()?"OK":"NEEDS_INPUT","RUNNING",state);
            }
            case "inspect_certificate_identity" -> {
                state.certificatePreview=preview(user,state);state.certificateIdentityChecked=true;
                state.message="已读取本人当前登记资料与所属学院办理资格，请在本地预览核对。";
                return result(action,"OK","RUNNING",state);
            }
            case "prepare_certificate" -> {
                ready(user,state);state.proposalHash=hash.apply(fingerprint(state,user));state.proposalReady=true;state.confirmed=false;
                state.message="请核对本人姓名、学号、学院、专业、班级、校区和实际用途，确认后仅保存证明草稿。";
                return result(action,"WAITING_CONFIRMATION","WAITING_CONFIRMATION",state);
            }
            case "save_certificate" -> {
                if(!state.confirmed||!Objects.equals(state.proposalHash,hash.apply(fingerprint(state,user))))throw changed("证明预览已变化，请重新确认");
                ready(user,state);
                var draft=certificates.save(user,null,new CertificateModels.Draft(key,state.certificateFields.purpose(),"DIGITAL",true,0,state.certificatePreview.identity()));
                state.draftId=draft.id();state.message="证明草稿已保存，正在读取原申请核验。";
                return result(action,"SAVED","RUNNING",state);
            }
            case "verify_certificate" -> {
                var draft=certificates.detail(user,"mine",state.draftId);
                if(draft.studentId()!=user.id()||!draft.requestKey().equals(key)||!draft.status().equals("DRAFT")
                    ||!draft.purpose().equals(state.certificateFields.purpose())||!draft.identity().equals(state.certificatePreview.identity())
                    ||!draft.identityConfirmed()||!draft.deliveryMethod().equals("DIGITAL")||draft.version()!=0)
                    throw new ApiException(HttpStatus.CONFLICT,"MISSION_RESULT_CHANGED","原证明申请已变化，请到原页面核对，Agent不会覆盖修改");
                state.verified=true;state.message="已读取本人原证明申请核验用途、登记快照和草稿状态。";
                return result(action,"VERIFIED","RUNNING",state);
            }
            case "finish" -> {
                if(!state.verified)throw changed("原证明申请尚未核验");
                state.message="任务完成：本人证明草稿已保存并核验。请到原页面手动提交，等待学院学生事务人员审核。";
                return result(action,"DONE","DONE",state);
            }
            default -> throw new ApiException(HttpStatus.BAD_GATEWAY,"MODEL_ACTION_REJECTED","当前证明任务不允许该动作");
        }
    }
    public Business business(CampusPrincipal user,State state){
        if(state.draftId==null)return null;var draft=certificates.detail(user,"mine",state.draftId);
        return new Business(draft.id(),draft.status(),draft.version(),"/portal/certificates?id="+draft.id()+"&tab=details");
    }
    private ApiException changed(String message){return new ApiException(HttpStatus.CONFLICT,"MISSION_REPLAN_REQUIRED",message);}
}
