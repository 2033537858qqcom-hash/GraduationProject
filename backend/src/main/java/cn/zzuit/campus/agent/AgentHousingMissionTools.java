package cn.zzuit.campus.agent;

import static cn.zzuit.campus.agent.AgentMissionModels.*;
import cn.zzuit.campus.agent.AgentModels.*;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.housing.*;
import cn.zzuit.campus.identity.CampusPrincipal;
import java.util.*;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Housing-transfer mission execution uses current stay and guide rules without choosing beds. */
@Service
public class AgentHousingMissionTools {
    private final AgentHousingDrafts drafts;
    private final HousingTransferService transfers;

    public AgentHousingMissionTools(AgentHousingDrafts drafts, HousingTransferService transfers) {
        this.drafts = drafts;
        this.transfers = transfers;
    }

    public HousingFields normalize(HousingFields fields) {
        return drafts.normalize(fields == null ? new HousingFields("", "", "") : fields);
    }

    private HousingPreview preview(CampusPrincipal user, State state) {
        return drafts.preview(user, "mission-preview", Mode.LOCAL, state.housingFields);
    }

    private List<String> missing(State state) {
        var f = state.housingFields;
        var list = new ArrayList<String>();
        if (f.reason().isBlank()) list.add("reason");
        if (f.preferredArea().isBlank()) list.add("preferredArea");
        if (f.materialNote().isBlank()) list.add("materialNote");
        return list;
    }

    public List<String> allowed(State state) {
        if (state.draftId != null) return List.of(state.verified ? "finish" : "verify_housing");
        if (!missing(state).isEmpty()) return List.of("request_input");
        if (!state.housingChecked) return List.of("check_housing_requirements");
        if (state.housingPreview != null && !state.housingPreview.ready()) return List.of("request_input");
        if (!state.housingStayChecked) return List.of("inspect_housing_stay");
        if (!state.proposalReady) return List.of("prepare_housing");
        return state.confirmed ? List.of("save_housing") : List.of();
    }

    public Object fingerprint(State state, CampusPrincipal user) {
        var p = state.housingPreview;
        return Map.of(
            "kind", "HOUSING",
            "fields", state.housingFields,
            "stayToken", Objects.toString(p == null ? null : p.stayToken(), ""),
            "guideVersion", p == null || p.source() == null ? -1 : p.source().version(),
            "user", user.id(),
            "securityVersion", user.securityVersion());
    }

    public void invalidate(State state, String message) {
        state.confirmed = false;
        state.proposalReady = false;
        state.proposalHash = null;
        state.housingChecked = false;
        state.housingStayChecked = false;
        state.housingPreview = null;
        state.housingStayToken = null;
        state.missing = List.of("reason", "preferredArea", "materialNote");
        state.message = message;
    }

    private AgentMissionTools.Outcome result(String action, String status, String stage, State state) {
        return new AgentMissionTools.Outcome(stage, new Observation(action, status, 0, state.missing));
    }

    private void ready(CampusPrincipal user, State state) {
        var current = preview(user, state);
        if (!current.ready()) throw changed("调宿资料或住宿状态已变化，请重新核对：" + current.summary());
        if (!Objects.equals(state.housingStayToken, current.stayToken())) {
            throw changed("当前住宿或指南版本已变化，请重新核对并确认");
        }
        state.housingPreview = current;
    }

    public AgentMissionTools.Outcome execute(
        CampusPrincipal user, String action, State state, String key, Function<Object, String> hash) {
        drafts.require(user);
        switch (action) {
            case "request_input" -> {
                state.housingPreview = preview(user, state);
                state.missing = state.housingPreview.missing();
                state.message = state.housingPreview.summary();
                return result(action, "NEEDS_INPUT", "WAITING_INPUT", state);
            }
            case "check_housing_requirements" -> {
                state.housingPreview = preview(user, state);
                state.missing = state.housingPreview.missing();
                state.housingChecked = true;
                state.message = state.housingPreview.ready() ? "已核对调宿原因、意向区域、材料说明与有效指南。" : state.housingPreview.summary();
                return result(action, state.housingPreview.ready() ? "OK" : "NEEDS_INPUT", "RUNNING", state);
            }
            case "inspect_housing_stay" -> {
                state.housingPreview = preview(user, state);
                state.housingStayToken = state.housingPreview.stayToken();
                state.housingStayChecked = true;
                state.message = state.housingPreview.currentPlace() == null
                    ? "暂无当前入住记录，请在本地核对住宿状态。"
                    : "已读取本人当前住宿记录，请在本地核对后确认。";
                return result(action, "OK", "RUNNING", state);
            }
            case "prepare_housing" -> {
                ready(user, state);
                state.proposalHash = hash.apply(fingerprint(state, user));
                state.proposalReady = true;
                state.confirmed = false;
                state.message = "请核对当前住宿、调宿原因、意向区域和材料说明，确认后仅保存本人调宿草稿。";
                return result(action, "WAITING_CONFIRMATION", "WAITING_CONFIRMATION", state);
            }
            case "save_housing" -> {
                if (!state.confirmed || !Objects.equals(state.proposalHash, hash.apply(fingerprint(state, user)))) {
                    throw changed("调宿预览已变化，请重新确认");
                }
                ready(user, state);
                var f = state.housingFields;
                var saved = transfers.save(user, null, new HousingTransferModels.DraftInput(key, 0, f.reason(), f.preferredArea(), f.materialNote()));
                state.draftId = saved.id();
                state.message = "调宿草稿已保存，正在读取原申请核验。";
                return result(action, "SAVED", "RUNNING", state);
            }
            case "verify_housing" -> {
                var detail = transfers.mineDetail(user, state.draftId);
                var f = state.housingFields;
                if (!detail.status().equals("DRAFT")
                    || !detail.reason().equals(f.reason())
                    || !detail.preferredArea().equals(f.preferredArea())
                    || !detail.materialNote().equals(f.materialNote())) {
                    throw new ApiException(HttpStatus.CONFLICT, "MISSION_RESULT_CHANGED", "原调宿申请已变化，请进入原页面核对，Agent不会覆盖修改");
                }
                state.verified = true;
                state.message = "已读取本人原调宿申请核验草稿字段和状态。";
                return result(action, "VERIFIED", "RUNNING", state);
            }
            case "finish" -> {
                if (!state.verified) throw changed("原调宿申请尚未核验");
                state.message = "任务完成：本人调宿草稿已保存并核验。可进入原页面或在此确认后提交审核。";
                return result(action, "DONE", "DONE", state);
            }
            default -> throw new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_ACTION_REJECTED", "当前调宿任务不允许该动作");
        }
    }

    public Business business(CampusPrincipal user, State state) {
        if (state.draftId == null) return null;
        var detail = transfers.mineDetail(user, state.draftId);
        return new Business(detail.id(), detail.status(), detail.version(), "/housing/transfer?id=" + detail.id() + "&tab=details");
    }

    private ApiException changed(String message) {
        return new ApiException(HttpStatus.CONFLICT, "MISSION_REPLAN_REQUIRED", message);
    }
}
