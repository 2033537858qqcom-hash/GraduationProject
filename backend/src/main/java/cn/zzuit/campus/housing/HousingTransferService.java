package cn.zzuit.campus.housing;

import static cn.zzuit.campus.housing.HousingTransferModels.*;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import cn.zzuit.campus.notice.NoticeWriter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HousingTransferService {
    private static final List<String> OPEN=List.of("DRAFT","SUBMITTED","RETURNED","AWAITING_ASSIGNMENT","AWAITING_CHECKIN");
    private final JdbcClient jdbc;
    private final IdentityService identities;
    private final Clock clock;
    private final NoticeWriter notices;
    private final HousingMaterialStore materials;
    private static final String SELECT="""
        SELECT t.* FROM housing_transfer t
        """;
    public HousingTransferService(JdbcClient jdbc,IdentityService identities,Clock clock,NoticeWriter notices,HousingMaterialStore materials) {this.jdbc=jdbc;this.identities=identities;this.clock=clock;this.notices=notices;this.materials=materials;}

    public HousingMaterialModels.Access materialAccess(CampusPrincipal user,String view,long id) {
        Row row;
        if("mine".equals(view)) {identities.requirePermission(user,"housing:read");row=own(user,id);}
        else if("review".equals(view)) {identities.requirePermission(user,"housing:review");row=reviewable(user,id);}
        else throw new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","当前岗位不能读取调宿附件");
        return new HousingMaterialModels.Access(row.id(),row.status(),row.version());
    }

    @Transactional public void refreshMine(CampusPrincipal user) {
        identities.requirePermission(user,"housing:read");lock();
        for(long id:jdbc.sql("SELECT id FROM housing_transfer WHERE student_id=:user AND status='AWAITING_CHECKIN' AND reserve_until<=:now")
            .param("user",user.id()).param("now",Timestamp.valueOf(LocalDateTime.now(clock))).query(Long.class).list()) expire(load(id));
    }
    @Transactional public Transfer reviewDetail(CampusPrincipal user,long id) {
        identities.requirePermission(user,"housing:review");lock();expireDue();return view(reviewable(user,id),"review");
    }
    @Transactional public Transfer assignDetail(CampusPrincipal user,long id) {
        identities.requirePermission(user,"housing:assign");lock();expireDue();return view(assignable(user,id),"assign");
    }
    /** Link authorization must not expire reservations or write new notices while listing messages. */
    public void requireNoticeAccess(CampusPrincipal user,long id,String view) {
        switch(view) {
            case "MINE" -> {identities.requirePermission(user,"housing:read");own(user,id);}
            case "REVIEW" -> {identities.requirePermission(user,"housing:review");reviewable(user,id);}
            case "ASSIGN" -> {identities.requirePermission(user,"housing:assign");assignable(user,id);}
            default -> throw missing("调宿申请不存在");
        }
    }

    @Transactional public List<Transfer> mine(CampusPrincipal user) {
        identities.requirePermission(user,"housing:read");lock();expireDue();
        return jdbc.sql(SELECT+"WHERE t.student_id=:id ORDER BY t.id DESC").param("id",user.id()).query(this::row).list().stream().map(row->view(row,"mine")).toList();
    }
    @Transactional public Transfer mineDetail(CampusPrincipal user,long id) {
        identities.requirePermission(user,"housing:read");lock();expireDue();return view(own(user,id),"mine");
    }
    @Transactional public List<Transfer> recentMine(CampusPrincipal user) {
        identities.requirePermission(user,"housing:read");lock();expireDue();
        return jdbc.sql(SELECT+"WHERE t.student_id=:id ORDER BY t.id DESC LIMIT 8").param("id",user.id()).query(this::row).list().stream().map(row->view(row,"mine")).toList();
    }
    @Transactional public Transfer save(CampusPrincipal user,Long id,DraftInput input) {
        identities.requirePermission(user,"housing:read");lock();String digest=digest(input.reason(),input.preferredArea(),input.materialNote());
        var repeated=repeat(user,input.requestKey(),digest);if(repeated!=null) return view(own(user,repeated),"mine");
        if(id==null) {
            if(input.version()!=0) stale();
            var stay=currentStay(user.id());
            if(jdbc.sql("SELECT COUNT(*) FROM housing_transfer WHERE current_student_id=:id").param("id",user.id()).query(Long.class).single()>0) conflict("已有未结束的调宿申请");
            var profile=profileText(user.id());
            var key=new GeneratedKeyHolder();
            jdbc.sql("""
                INSERT INTO housing_transfer(student_id,student_username,student_name,department_id,source_assignment_id,source_version,source_bed_id,source_place,request_key,status,reason,preferred_area,material_note,current_student_id)
                VALUES(:student,:username,:name,:department,:assignment,:version,:bed,:place,:request,'DRAFT',:reason,:area,:note,:student)
                """).param("student",user.id()).param("username",profile[0]).param("name",profile[1]).param("department",Long.parseLong(profile[2])).param("assignment",stay.id()).param("version",stay.version()).param("bed",stay.bedId()).param("place",stay.place()).param("request",input.requestKey()).param("reason",input.reason().trim()).param("area",input.preferredArea().trim()).param("note",input.materialNote().trim()).update(key,"id");
            long created=key.getKey().longValue();
            act(user,created,"SAVE",input.requestKey(),digest);audit(user,"TRANSFER_SAVE",created,stay.place(),input.reason());
            return view(load(created),"mine");
        }
        var row=own(user,id);if(!List.of("DRAFT","RETURNED").contains(row.status())) conflict("当前状态不能修改申请");if(row.version()!=input.version()) stale();
        if(jdbc.sql("UPDATE housing_transfer SET reason=:reason,preferred_area=:area,material_note=:note,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version AND status IN ('DRAFT','RETURNED')").param("reason",input.reason().trim()).param("area",input.preferredArea().trim()).param("note",input.materialNote().trim()).param("id",id).param("version",input.version()).update()!=1) stale();
        act(user,id,"SAVE",input.requestKey(),digest);return view(load(id),"mine");
    }
    @Transactional public Transfer submit(CampusPrincipal user,long id,ActionInput input) {
        identities.requirePermission(user,"housing:read");lock();String digest=digest("submit",input.version());
        var repeated=repeat(user,input.requestKey(),digest);if(repeated!=null) return view(own(user,repeated),"mine");
        var row=own(user,id);if(!"DRAFT".equals(row.status())) conflict("只有草稿可以提交");if(row.version()!=input.version()) stale();
        var stay=currentStay(user.id());
        if(jdbc.sql("SELECT COUNT(*) FROM app_user u WHERE "+reviewer()+" AND u.department_id=:d AND u.id<>:s").param("d",row.departmentId()).param("s",user.id()).query(Long.class).single()==0) conflict("所属学院尚未配置其他学生事务人员，草稿已保留");
        if(jdbc.sql("UPDATE housing_transfer SET status='SUBMITTED',source_assignment_id=:assignment,source_version=:sourceVersion,source_bed_id=:bed,source_place=:place,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version AND status='DRAFT'").param("assignment",stay.id()).param("sourceVersion",stay.version()).param("bed",stay.bedId()).param("place",stay.place()).param("id",id).param("version",input.version()).update()!=1) stale();
        materials.capture(id);
        act(user,id,"SUBMIT",input.requestKey(),digest);notifyReviewers(row.departmentId(),user.id(),id,"有新的调宿申请","请核对本学院学生的调宿资料。");audit(user,"TRANSFER_SUBMIT",id,stay.place(),"提交调宿");
        return view(load(id),"mine");
    }
    @Transactional public Transfer withdraw(CampusPrincipal user,long id,ActionInput input) {
        identities.requirePermission(user,"housing:read");lock();requireComment(input);String digest=digest("withdraw",input.comment());
        var repeated=repeat(user,input.requestKey(),digest);if(repeated!=null) return view(own(user,repeated),"mine");
        var row=own(user,id);if(!List.of("SUBMITTED","RETURNED","AWAITING_ASSIGNMENT").contains(row.status())) conflict("当前状态不能撤回");if(row.version()!=input.version()) stale();
        close(id,input.version(),row.status(),"WITHDRAWN");
        act(user,id,"WITHDRAW",input.requestKey(),digest);audit(user,"TRANSFER_WITHDRAW",id,row.sourcePlace(),input.comment());
        return view(load(id),"mine");
    }
    @Transactional public Transfer supplement(CampusPrincipal user,long id,DraftInput input) {
        identities.requirePermission(user,"housing:read");lock();String digest=digest("supplement",input.reason(),input.preferredArea(),input.materialNote());
        var repeated=repeat(user,input.requestKey(),digest);if(repeated!=null) return view(own(user,repeated),"mine");
        var row=own(user,id);if(!"RETURNED".equals(row.status())) conflict("当前没有需要补交的申请");if(row.version()!=input.version()) stale();
        if(jdbc.sql("UPDATE housing_transfer SET status='SUBMITTED',reason=:reason,preferred_area=:area,material_note=:note,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version AND status='RETURNED'").param("reason",input.reason().trim()).param("area",input.preferredArea().trim()).param("note",input.materialNote().trim()).param("id",id).param("version",input.version()).update()!=1) stale();
        materials.capture(id);
        act(user,id,"SUPPLEMENT",input.requestKey(),digest);notifyReviewers(row.departmentId(),user.id(),id,"调宿申请已补交","学生已补交资料，请继续初审。");
        return view(load(id),"mine");
    }
    @Transactional public Transfer requestCancel(CampusPrincipal user,long id,ActionInput input) {
        identities.requirePermission(user,"housing:read");lock();requireComment(input);String digest=digest("cancel-request",input.comment());
        var repeated=repeat(user,input.requestKey(),digest);if(repeated!=null) return view(own(user,repeated),"mine");
        var row=own(user,id);if(!"AWAITING_CHECKIN".equals(row.status())) conflict("只有待入住的申请可以请求取消");if(row.cancelRequested()) conflict("取消请求已提交");if(row.version()!=input.version()) stale();
        if(jdbc.sql("UPDATE housing_transfer SET cancel_requested=TRUE,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version AND status='AWAITING_CHECKIN' AND cancel_requested=FALSE").param("id",id).param("version",input.version()).update()!=1) stale();
        act(user,id,"CANCEL_REQUEST",input.requestKey(),digest);audit(user,"TRANSFER_CANCEL_REQUEST",id,row.targetPlace(),input.comment());
        notifyManagers(row.targetBuildingId(),id,"调宿申请请求取消","请核对取消请求，再决定是否释放预留床位。");
        return view(load(id),"mine");
    }
    @Transactional public void deleteDraft(CampusPrincipal user,long id,ActionInput input) {
        identities.requirePermission(user,"housing:read");lock();String digest=digest("delete",id);
        var existing=jdbc.sql("SELECT transfer_id,input_digest FROM housing_transfer_action WHERE actor_id=:u AND request_key=:k").param("u",user.id()).param("k",input.requestKey()).query((r,n)->new String[]{String.valueOf(r.getLong(1)),r.getString(2)}).optional();
        if(existing.isPresent()) {if(!existing.get()[1].equals(digest)) conflict("相同操作标识对应不同内容");return;}
        var row=own(user,id);if(!"DRAFT".equals(row.status())) conflict("只有草稿可以删除");if(row.version()!=input.version()) stale();
        act(user,id,"DELETE",input.requestKey(),digest);
        materials.deleteDraft(id);
        if(jdbc.sql("DELETE FROM housing_transfer WHERE id=:id AND status='DRAFT' AND version=:version").param("id",id).param("version",input.version()).update()!=1) stale();
    }

    @Transactional public List<Transfer> review(CampusPrincipal user,String status) {
        identities.requirePermission(user,"housing:review");lock();expireDue();long department=college(user);
        String where=" WHERE t.department_id=:d AND t.student_id<>:u AND t.status<>'DRAFT' AND EXISTS(SELECT 1 FROM app_user owner WHERE owner.id=t.student_id AND owner.department_id=t.department_id)";
        if(status!=null && !status.isBlank()) {if(!OPEN.contains(status) && !List.of("COMPLETED","REJECTED","WITHDRAWN","CANCELLED","EXPIRED").contains(status)) bad("申请状态无效");where+=" AND t.status=:status";}
        var query=jdbc.sql(SELECT+where+" ORDER BY t.updated_at DESC,t.id DESC").param("d",department).param("u",user.id());
        if(status!=null && !status.isBlank()) query=query.param("status",status);
        return query.query(this::row).list().stream().map(row->view(row,"review")).toList();
    }
    @Transactional public Transfer decide(CampusPrincipal user,long id,String action,ActionInput input) {
        identities.requirePermission(user,"housing:review");lock();requireComment(input);String digest=digest(action,input.comment());
        var repeated=repeat(user,input.requestKey(),digest);if(repeated!=null) return view(reviewable(user,repeated),"review");
        var row=reviewable(user,id);if(!"SUBMITTED".equals(row.status())) conflict("当前申请不在待初审");if(row.version()!=input.version()) stale();
        String next="approve".equals(action)?"AWAITING_ASSIGNMENT":"return".equals(action)?"RETURNED":"reject".equals(action)?"REJECTED":"";
        if(next.isEmpty()) bad("审核动作无效");
        if("REJECTED".equals(next)) close(id,input.version(),"SUBMITTED","REJECTED");
        else if(jdbc.sql("UPDATE housing_transfer SET status=:status,review_comment=:comment,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version AND status='SUBMITTED'").param("status",next).param("comment",input.comment().trim()).param("id",id).param("version",input.version()).update()!=1) stale();
        else jdbc.sql("UPDATE housing_transfer SET review_comment=:comment WHERE id=:id").param("comment",input.comment().trim()).param("id",id).update();
        if("REJECTED".equals(next)) jdbc.sql("UPDATE housing_transfer SET review_comment=:comment WHERE id=:id").param("comment",input.comment().trim()).param("id",id).update();
        act(user,id,action.toUpperCase(),input.requestKey(),digest);
        notify(row.studentId(),id,"approve".equals(action)?"调宿初审已通过":"return".equals(action)?"调宿申请需补交":"调宿申请未通过","请打开申请查看初审结果与后续办理要求。");
        audit(user,"TRANSFER_"+action.toUpperCase(),id,row.sourcePlace(),input.comment());
        return view(load(id),"review");
    }

    @Transactional public List<Transfer> assignQueue(CampusPrincipal user,String queue) {
        identities.requirePermission(user,"housing:assign");lock();expireDue();
        if("waiting".equals(queue)) return jdbc.sql(SELECT+"""
            WHERE t.status='AWAITING_ASSIGNMENT'
              AND EXISTS (
                  SELECT 1 FROM dorm_manager_scope ms
                  JOIN dorm_building mb ON mb.id=ms.building_id
                  JOIN dorm_bed sb ON sb.id=t.source_bed_id
                  JOIN dorm_room sr ON sr.id=sb.room_id
                  JOIN dorm_building sbuild ON sbuild.id=sr.building_id
                  WHERE ms.user_id=:user AND ms.enabled=TRUE AND mb.campus_id=sbuild.campus_id
              )
            ORDER BY t.updated_at,t.id
            """).param("user",user.id()).query(this::row).list().stream().map(row->view(row,"assign")).toList();
        if(!"reserved".equals(queue)) bad("调宿队列无效");
        return jdbc.sql(SELECT+"WHERE t.status='AWAITING_CHECKIN' AND t.target_building_id IN ("+effectiveBuildings()+") ORDER BY t.reserve_until,t.id").param("user",user.id()).query(this::row).list().stream().map(row->view(row,"assign")).toList();
    }
    @Transactional public Transfer reserve(CampusPrincipal user,long id,ReserveInput input) {
        identities.requirePermission(user,"housing:assign");lock();String digest=digest("reserve",input.bedId(),input.reason());
        var repeated=repeat(user,input.requestKey(),digest);if(repeated!=null) return view(assignable(user,repeated),"assign");
        var row=load(id);if(!effective(user,bedBuilding(input.bedId()))) throw missing("床位不存在");
        if(!"AWAITING_ASSIGNMENT".equals(row.status())) conflict("当前申请不能安排床位");if(row.version()!=input.version()) stale();
        var bed=bed(input.bedId());if(bed.bedId()==row.sourceBedId()) conflict("不能预留学生当前入住的床位");
        if(!bed.assignable()) conflict(bed.enabledChain()?"床位当前不可预留":"楼栋、房间或床位已停用");
        var stay=currentStay(row.studentId());if(stay.id()!=row.sourceAssignmentId()) conflict("学生当前住宿已变化，请退回后重新申请");
        LocalDateTime until=LocalDateTime.now(clock).plusHours(72);
        if(jdbc.sql("UPDATE dorm_bed SET status='RESERVED',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND status='VACANT' AND enabled=TRUE").param("id",bed.bedId()).update()!=1) conflict("床位状态已变化，请重新选择");
        if(jdbc.sql("""
            UPDATE housing_transfer SET status='AWAITING_CHECKIN',target_bed_id=:bed,target_building_id=:building,target_place=:place,reserve_until=:until,reserved_bed_id=:bed,source_version=:sourceVersion,cancel_requested=FALSE,version=version+1,updated_at=CURRENT_TIMESTAMP
            WHERE id=:id AND version=:version AND status='AWAITING_ASSIGNMENT' AND reserved_bed_id IS NULL
            """).param("bed",bed.bedId()).param("building",bed.buildingId()).param("place",bed.place()).param("until",Timestamp.valueOf(until)).param("sourceVersion",stay.version()).param("id",id).param("version",input.version()).update()!=1) stale();
        act(user,id,"RESERVE",input.requestKey(),digest);notify(row.studentId(),id,"调宿床位已预留","请在 "+until.toString().replace('T',' ').substring(0,16)+" 前办理入住，具体床位请查看申请。");audit(user,"TRANSFER_RESERVE",id,bed.place(),input.reason());
        return view(load(id),"assign");
    }
    @Transactional public Transfer checkIn(CampusPrincipal user,long id,ActionInput input) {
        identities.requirePermission(user,"housing:assign");lock();requireComment(input);String digest=digest("check-in",input.comment());
        var repeated=repeat(user,input.requestKey(),digest);if(repeated!=null) return view(assignable(user,repeated),"assign");
        var row=assignable(user,id);if(!"AWAITING_CHECKIN".equals(row.status())) conflict("当前申请不能确认入住");if(row.version()!=input.version()) stale();
        if(row.cancelRequested()) conflict("学生已请求取消，请先处理取消");
        if(!LocalDateTime.now(clock).isBefore(row.reserveUntil())) {expire(row);conflict("入住期限已过，预留已释放");}
        var stay=jdbc.sql("SELECT id,version,bed_id,status FROM housing_assignment WHERE id=:id").param("id",row.sourceAssignmentId()).query((r,n)->new long[]{r.getLong(1),r.getInt(2),r.getLong(3),r.getString(4).equals("CURRENT")?1:0}).optional().orElseThrow(()->conflictNow("原住宿记录不存在"));
        if(stay[3]!=1 || stay[1]!=row.sourceVersion()) conflict("原住宿已变化，入住未切换，预留仍保留");
        var target=bed(row.targetBedId());
        String moveKey=("M"+id+"-"+input.requestKey());if(moveKey.length()>80) moveKey=moveKey.substring(0,80);
        if(jdbc.sql("UPDATE housing_assignment SET status='ENDED',current_student_id=NULL,current_bed_id=NULL,checkout_key=:move,ended_by=:actor,end_reason=:reason,ended_at=CURRENT_TIMESTAMP,version=version+1,campus_name=:campus,building_name=:building,room_name=:room,floor_label=:floor,bed_label=:label WHERE id=:id AND version=:version AND status='CURRENT'").param("move",moveKey).param("actor",user.id()).param("reason","调宿完成").param("campus",sourceCampus(stay[2])).param("building",sourceBuilding(stay[2])).param("room",sourceRoom(stay[2])).param("floor",sourceFloor(stay[2])).param("label",sourceLabel(stay[2])).param("id",stay[0]).param("version",stay[1]).update()!=1) conflict("原住宿已变化，入住未切换，预留仍保留");
        if(jdbc.sql("UPDATE dorm_bed SET status='VACANT',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND status='OCCUPIED'").param("id",stay[2]).update()!=1) conflict("原床位状态已变化，入住未完成");
        if(jdbc.sql("UPDATE dorm_bed SET status='OCCUPIED',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND status='RESERVED'").param("id",target.bedId()).update()!=1) conflict("预留床位状态已变化，入住未完成");
        var key=new GeneratedKeyHolder();
        jdbc.sql("""
            INSERT INTO housing_assignment(student_id,student_username,student_name,bed_id,status,request_key,current_student_id,current_bed_id,campus_name,building_name,room_name,floor_label,bed_label,assigned_by)
            VALUES(:student,:username,:name,:bed,'CURRENT',:request,:student,:bed,:campus,:building,:room,:floor,:label,:actor)
            """).param("student",row.studentId()).param("username",row.studentUsername()).param("name",row.studentName()).param("bed",target.bedId()).param("request",("T"+id+"-"+input.requestKey()).substring(0,Math.min(80,("T"+id+"-"+input.requestKey()).length()))).param("campus",target.campus()).param("building",target.building()).param("room",target.room()).param("floor",target.floor()).param("label",target.bed()).param("actor",user.id()).update(key,"id");
        if(jdbc.sql("UPDATE housing_transfer SET status='COMPLETED',current_student_id=NULL,reserved_bed_id=NULL,target_place=:place,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version AND status='AWAITING_CHECKIN'").param("place",target.place()).param("id",id).param("version",input.version()).update()!=1) stale();
        act(user,id,"CHECK_IN",input.requestKey(),digest);notify(row.studentId(),id,"调宿入住已确认","新住宿已生效，原床位已释放。请查看申请或我的住宿。");audit(user,"TRANSFER_CHECK_IN",id,row.sourcePlace()+" → "+target.place(),input.comment());
        return view(load(id),"assign");
    }
    @Transactional public Transfer release(CampusPrincipal user,long id,ActionInput input) {
        return finishReservation(user,id,input,"release","AWAITING_ASSIGNMENT","预留已退回待安排");
    }
    @Transactional public Transfer acceptCancel(CampusPrincipal user,long id,ActionInput input) {
        var row=loadForAssign(user,id,input,"accept-cancel");if(row==null) return view(load(repeatId(user,input.requestKey())),"assign");
        if(!row.cancelRequested()) conflict("学生尚未请求取消");
        freeReservation(row,input.version(),"CANCELLED");
        act(user,id,"ACCEPT_CANCEL",input.requestKey(),digest("accept-cancel",input.comment()));notify(row.studentId(),id,"调宿预留已取消","原住宿保持不变。");audit(user,"TRANSFER_CANCEL",id,row.targetPlace(),input.comment());
        return view(load(id),"assign");
    }
    @Transactional public Transfer keep(CampusPrincipal user,long id,ActionInput input) {
        identities.requirePermission(user,"housing:assign");lock();requireComment(input);String digest=digest("keep",input.comment());
        var repeated=repeat(user,input.requestKey(),digest);if(repeated!=null) return view(assignable(user,repeated),"assign");
        var row=assignable(user,id);if(!"AWAITING_CHECKIN".equals(row.status()) || !row.cancelRequested()) conflict("当前没有待处理的取消请求");if(row.version()!=input.version()) stale();
        if(jdbc.sql("UPDATE housing_transfer SET cancel_requested=FALSE,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version AND status='AWAITING_CHECKIN' AND cancel_requested=TRUE").param("id",id).param("version",input.version()).update()!=1) stale();
        act(user,id,"KEEP",input.requestKey(),digest);notify(row.studentId(),id,"调宿取消未同意","预留仍保留，请打开申请查看当前办理状态。");
        return view(load(id),"assign");
    }
    @Transactional public Transfer extend(CampusPrincipal user,long id,ExtendInput input) {
        identities.requirePermission(user,"housing:assign");lock();String digest=digest("extend",input.reserveUntil(),input.reason());
        var repeated=repeat(user,input.requestKey(),digest);if(repeated!=null) return view(assignable(user,repeated),"assign");
        var row=assignable(user,id);if(!"AWAITING_CHECKIN".equals(row.status())) conflict("当前申请不能调整期限");if(row.version()!=input.version()) stale();
        LocalDateTime now=LocalDateTime.now(clock);
        if(!now.isBefore(row.reserveUntil())) {expire(row);conflict("入住期限已过，预留已释放");}
        if(!input.reserveUntil().isAfter(now) || input.reserveUntil().isAfter(now.plusDays(7))) bad("新期限须晚于现在，且不超过 7 天");
        if(jdbc.sql("UPDATE housing_transfer SET reserve_until=:until,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version AND status='AWAITING_CHECKIN'").param("until",Timestamp.valueOf(input.reserveUntil())).param("id",id).param("version",input.version()).update()!=1) stale();
        act(user,id,"EXTEND",input.requestKey(),digest);notify(row.studentId(),id,"调宿入住期限已调整","请在 "+input.reserveUntil().toString().replace('T',' ').substring(0,16)+" 前完成搬迁。");
        return view(load(id),"assign");
    }
    @Transactional public int processDue() {lock();return expireDue();}

    private Transfer finishReservation(CampusPrincipal user,long id,ActionInput input,String action,String next,String title) {
        var row=loadForAssign(user,id,input,action);if(row==null) return view(load(repeatId(user,input.requestKey())),"assign");
        freeReservation(row,input.version(),next.equals("AWAITING_ASSIGNMENT")?null:next);
        if("AWAITING_ASSIGNMENT".equals(next) && jdbc.sql("UPDATE housing_transfer SET status='AWAITING_ASSIGNMENT',target_bed_id=NULL,target_building_id=NULL,target_place=NULL,reserve_until=NULL,reserved_bed_id=NULL,cancel_requested=FALSE,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND status='AWAITING_CHECKIN'").param("id",id).update()!=1) stale();
        act(user,id,action.toUpperCase(),input.requestKey(),digest(action,input.comment()));notify(row.studentId(),id,title,"请查看申请中的当前状态，原住宿保持不变。");audit(user,"TRANSFER_"+action.toUpperCase(),id,row.targetPlace(),input.comment());
        return view(load(id),"assign");
    }
    private Row loadForAssign(CampusPrincipal user,long id,ActionInput input,String action) {
        identities.requirePermission(user,"housing:assign");lock();requireComment(input);String digest=digest(action,input.comment());
        var repeated=repeat(user,input.requestKey(),digest);if(repeated!=null) return null;
        var row=assignable(user,id);if(!"AWAITING_CHECKIN".equals(row.status())) conflict("当前申请没有可释放的预留");if(row.version()!=input.version()) stale();return row;
    }
    private void freeReservation(Row row,int version,String terminal) {
        if(jdbc.sql("UPDATE dorm_bed SET status='VACANT',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND status='RESERVED'").param("id",row.targetBedId()).update()!=1) conflict("预留床位状态已变化");
        if(terminal!=null) close(row.id(),version,"AWAITING_CHECKIN",terminal);
    }
    private void close(long id,int version,String from,String terminal) {
        if(jdbc.sql("UPDATE housing_transfer SET status=:status,current_student_id=NULL,reserved_bed_id=NULL,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version AND status=:from").param("status",terminal).param("id",id).param("version",version).param("from",from).update()!=1) stale();
    }
    private int expireDue() {
        var ids=jdbc.sql("SELECT id FROM housing_transfer WHERE status='AWAITING_CHECKIN' AND reserve_until<=:now").param("now",Timestamp.valueOf(LocalDateTime.now(clock))).query(Long.class).list();
        int count=0;for(long id:ids) if(expire(load(id))) count++;return count;
    }
    private boolean expire(Row row) {
        if(!"AWAITING_CHECKIN".equals(row.status()) || row.reserveUntil()==null || row.reserveUntil().isAfter(LocalDateTime.now(clock))) return false;
        if(jdbc.sql("UPDATE dorm_bed SET status='VACANT',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND status='RESERVED'").param("id",row.targetBedId()).update()!=1) return false;
        close(row.id(),row.version(),"AWAITING_CHECKIN","EXPIRED");
        notify(row.studentId(),row.id(),"调宿预留已过期","未在期限内入住，原住宿保持不变。");auditSystem(row.id(),"TRANSFER_EXPIRE",row.targetPlace());
        return true;
    }
    private long repeatId(CampusPrincipal user,String key) {return jdbc.sql("SELECT transfer_id FROM housing_transfer_action WHERE actor_id=:u AND request_key=:k").param("u",user.id()).param("k",key).query(Long.class).single();}
    private Long repeat(CampusPrincipal user,String key,String digest) {
        var found=jdbc.sql("SELECT transfer_id,input_digest,action FROM housing_transfer_action WHERE actor_id=:u AND request_key=:k").param("u",user.id()).param("k",key).query((r,n)->new Object[]{r.getLong(1),r.getString(2),r.getString(3)}).optional();
        if(found.isEmpty()) return null;if(((String)found.get()[2]).startsWith("MATERIAL_") || !digest.equals(found.get()[1])) conflict("相同操作标识对应不同内容");return (Long)found.get()[0];
    }
    private void act(CampusPrincipal user,long id,String action,String key,String digest) {jdbc.sql("INSERT INTO housing_transfer_action(transfer_id,actor_id,action,request_key,input_digest) VALUES(:id,:u,:a,:k,:d)").param("id",id).param("u",user.id()).param("a",action).param("k",key).param("d",digest).update();}
    private Stay currentStay(long student) {return jdbc.sql("""
        SELECT a.id,a.version,a.bed_id,c.name campus_name,b.name building_name,r.name room_name,d.label FROM housing_assignment a
        JOIN dorm_bed d ON d.id=a.current_bed_id JOIN dorm_room r ON r.id=d.room_id JOIN dorm_building b ON b.id=r.building_id JOIN campus c ON c.id=b.campus_id
        WHERE a.current_student_id=:id
        """).param("id",student).query((r,n)->new Stay(r.getLong(1),r.getInt(2),r.getLong(3),r.getString(4)+" "+r.getString(5)+" "+r.getString(6)+" "+r.getString(7))).optional().orElseThrow(()->conflictNow("没有当前住宿，不能申请调宿"));}
    private BedTarget bed(long id) {return jdbc.sql("""
        SELECT d.id,d.status,d.enabled bed_enabled,r.enabled room_enabled,b.id building_id,b.enabled building_enabled,b.name building_name,r.name room_name,r.floor_label,d.label,c.name campus_name
        FROM dorm_bed d JOIN dorm_room r ON r.id=d.room_id JOIN dorm_building b ON b.id=r.building_id JOIN campus c ON c.id=b.campus_id WHERE d.id=:id
        """).param("id",id).query((r,n)->new BedTarget(r.getLong("id"),r.getLong("building_id"),r.getString("building_name"),r.getString("room_name"),r.getString("floor_label"),r.getString("label"),r.getString("campus_name"),r.getString("status"),r.getBoolean("bed_enabled"),r.getBoolean("room_enabled"),r.getBoolean("building_enabled"))).optional().orElseThrow(()->missing("床位不存在"));}
    private long bedBuilding(long bedId) {return jdbc.sql("SELECT b.id FROM dorm_bed d JOIN dorm_room r ON r.id=d.room_id JOIN dorm_building b ON b.id=r.building_id WHERE d.id=:id").param("id",bedId).query(Long.class).optional().orElseThrow(()->missing("床位不存在"));}
    private boolean effective(CampusPrincipal user,long building) {return jdbc.sql("SELECT COUNT(*) FROM ("+effectiveBuildings()+") s WHERE s.building_id=:building").param("user",user.id()).param("building",building).query(Long.class).single()>0;}
    private String effectiveBuildings() {return """
        SELECT s.building_id building_id FROM dorm_manager_scope s JOIN app_user u ON u.id=s.user_id AND u.enabled=TRUE JOIN dorm_building b ON b.id=s.building_id AND b.enabled=TRUE
        WHERE s.user_id=:user AND s.enabled=TRUE AND EXISTS(SELECT 1 FROM user_role ur JOIN role_permission rp ON rp.role_code=ur.role_code AND rp.permission_code='housing:assign' WHERE ur.user_id=s.user_id)
        """;}
    private String reviewer() {return "u.enabled=TRUE AND EXISTS(SELECT 1 FROM user_role ur JOIN role_permission rp ON rp.role_code=ur.role_code AND rp.permission_code='housing:review' WHERE ur.user_id=u.id)";}
    private long college(CampusPrincipal user) {return jdbc.sql("SELECT u.department_id FROM app_user u JOIN department d ON d.id=u.department_id AND d.kind='COLLEGE' WHERE u.id=:id AND "+reviewer()).param("id",user.id()).query(Long.class).optional().orElseThrow(()->new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","调宿初审岗位须归属学院"));}
    private String[] profileText(long id) {return jdbc.sql("SELECT username,full_name,CAST(department_id AS VARCHAR(20)) FROM app_user WHERE id=:id").param("id",id).query((r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3)}).single();}
    private Row own(CampusPrincipal user,long id) {var row=load(id);if(row.studentId()!=user.id()) throw missing("调宿申请不存在");return row;}
    private Row reviewable(CampusPrincipal user,long id) {var row=load(id);if(row.studentId()==user.id() || row.departmentId()!=college(user) || "DRAFT".equals(row.status()) || jdbc.sql("SELECT COUNT(*) FROM app_user WHERE id=:student AND department_id=:department").param("student",row.studentId()).param("department",row.departmentId()).query(Long.class).single()==0) throw missing("调宿申请不存在");return row;}
    private Row assignable(CampusPrincipal user,long id) {
        var row=load(id);
        if(!List.of("AWAITING_ASSIGNMENT","AWAITING_CHECKIN","COMPLETED","CANCELLED","EXPIRED").contains(row.status())) throw missing("调宿申请不存在");
        if("AWAITING_ASSIGNMENT".equals(row.status())) {
            boolean campusMatch = jdbc.sql("""
                SELECT COUNT(*) FROM dorm_manager_scope ms
                JOIN dorm_building mb ON mb.id=ms.building_id
                JOIN dorm_bed sb ON sb.id=:sourceBedId
                JOIN dorm_room sr ON sr.id=sb.room_id
                JOIN dorm_building sbuild ON sbuild.id=sr.building_id
                WHERE ms.user_id=:user AND ms.enabled=TRUE AND mb.campus_id=sbuild.campus_id
                """).param("user", user.id()).param("sourceBedId", row.sourceBedId()).query(Long.class).single() > 0;
            if (!campusMatch) throw missing("调宿申请不存在");
        } else if(row.targetBuildingId()==null || !effective(user,row.targetBuildingId())) throw missing("调宿申请不存在");
        return row;
    }
    private Row load(long id) {return jdbc.sql(SELECT+"WHERE t.id=:id").param("id",id).query(this::row).optional().orElseThrow(()->missing("调宿申请不存在"));}
    private Transfer view(Row row,String viewer) {return new Transfer(row.id(),row.status(),row.studentUsername(),row.studentName(),row.sourcePlace(),row.sourceAssignmentId(),row.sourceVersion(),row.reason(),row.preferredArea(),row.materialNote(),row.reviewComment(),row.targetPlace(),row.targetBedId(),row.targetBuildingId(),row.reserveUntil(),row.cancelRequested(),row.version(),row.updatedAt(),actions(row,viewer));}
    private List<String> actions(Row row,String viewer) {
        if("mine".equals(viewer)) {if("DRAFT".equals(row.status())) return List.of("edit","delete","submit");if("SUBMITTED".equals(row.status()) || "AWAITING_ASSIGNMENT".equals(row.status())) return List.of("withdraw");if("RETURNED".equals(row.status())) return List.of("supplement","withdraw");if("AWAITING_CHECKIN".equals(row.status()) && !row.cancelRequested()) return List.of("cancel-request");return List.of();}
        if("review".equals(viewer) && "SUBMITTED".equals(row.status())) return List.of("approve","return","reject");
        if("assign".equals(viewer) && "AWAITING_ASSIGNMENT".equals(row.status())) return List.of("reserve");
        if("assign".equals(viewer) && "AWAITING_CHECKIN".equals(row.status())) return row.cancelRequested()?List.of("check-in","release","extend","accept-cancel","keep"):List.of("check-in","release","extend");
        return List.of();
    }
    private Row row(ResultSet r,int n) throws SQLException {var until=r.getTimestamp("reserve_until");var updated=r.getTimestamp("updated_at");return new Row(r.getLong("id"),r.getLong("student_id"),r.getString("student_username"),r.getString("student_name"),r.getLong("department_id"),r.getLong("source_assignment_id"),r.getInt("source_version"),r.getLong("source_bed_id"),r.getString("source_place"),r.getString("status"),r.getString("reason"),r.getString("preferred_area"),r.getString("material_note"),r.getString("review_comment"),r.getObject("target_bed_id",Long.class),r.getObject("target_building_id",Long.class),r.getString("target_place"),until==null?null:until.toLocalDateTime(),r.getBoolean("cancel_requested"),r.getInt("version"),updated.toLocalDateTime());}
    private void notify(long user,long transfer,String title,String body) {notices.write(user,title,"HOUSING",transfer,"MINE",body);}
    private void notifyReviewers(long department,long student,long transfer,String title,String body) {for(long id:jdbc.sql("SELECT u.id FROM app_user u WHERE "+reviewer()+" AND u.department_id=:d AND u.id<>:s").param("d",department).param("s",student).query(Long.class).list()) notices.write(id,title,"HOUSING",transfer,"REVIEW",body);}
    private void notifyManagers(Long building,long transfer,String title,String body) {
        if(building==null) return;
        for(long id:jdbc.sql("""
            SELECT DISTINCT s.user_id FROM dorm_manager_scope s JOIN app_user u ON u.id=s.user_id AND u.enabled=TRUE
            JOIN dorm_building b ON b.id=s.building_id AND b.enabled=TRUE WHERE s.building_id=:building AND s.enabled=TRUE
            AND EXISTS(SELECT 1 FROM user_role ur JOIN role_permission rp ON rp.role_code=ur.role_code AND rp.permission_code='housing:assign' WHERE ur.user_id=s.user_id)
            """).param("building",building).query(Long.class).list()) notices.write(id,title,"HOUSING",transfer,"ASSIGN",body);
    }
    private void audit(CampusPrincipal user,String action,long id,String summary,String reason) {String name=jdbc.sql("SELECT full_name FROM app_user WHERE id=:id").param("id",user.id()).query(String.class).single();jdbc.sql("INSERT INTO foundation_audit(actor_id,actor_name,action,entity_type,entity_id,summary,reason) VALUES(:u,:n,:a,'HOUSING_TRANSFER',:id,:s,:r)").param("u",user.id()).param("n",name).param("a",action).param("id",id).param("s",summary==null?"":summary).param("r",reason==null || reason.isBlank()?"调宿办理":reason.trim()).update();}
    private void auditSystem(long id,String action,String summary) {jdbc.sql("INSERT INTO foundation_audit(actor_id,actor_name,action,entity_type,entity_id,summary,reason) SELECT id,'系统',:a,'HOUSING_TRANSFER',:transfer,:s,'入住期限已到' FROM app_user WHERE username='admin'").param("a",action).param("transfer",id).param("s",summary==null?"":summary).update();}
    private String sourceCampus(long bed) {return part(bed,0);} private String sourceBuilding(long bed) {return part(bed,1);} private String sourceRoom(long bed) {return part(bed,2);} private String sourceFloor(long bed) {return part(bed,3);} private String sourceLabel(long bed) {return part(bed,4);}
    private String part(long bed,int index) {var row=jdbc.sql("SELECT c.name,b.name,r.name,r.floor_label,d.label FROM dorm_bed d JOIN dorm_room r ON r.id=d.room_id JOIN dorm_building b ON b.id=r.building_id JOIN campus c ON c.id=b.campus_id WHERE d.id=:id").param("id",bed).query((r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)}).single();return row[index];}
    private String digest(Object... parts) {return java.util.Arrays.stream(parts).map(part->part==null?"":String.valueOf(part).trim()).reduce((a,b)->a+"|"+b).orElse("");}
    private void requireComment(ActionInput input) {if(input.comment()==null || input.comment().isBlank()) bad("请填写说明");}
    private void lock() {jdbc.sql("UPDATE foundation_write_lock SET revision=revision+1 WHERE id=1").update();}
    private void stale() {conflict("调宿申请已变化，请刷新后重新操作");}
    private void bad(String message) {throw new ApiException(HttpStatus.BAD_REQUEST,"HOUSING_TRANSFER_INVALID",message);}
    private void conflict(String message) {throw new ApiException(HttpStatus.CONFLICT,"HOUSING_TRANSFER_CONFLICT",message);}
    private ApiException conflictNow(String message) {return new ApiException(HttpStatus.CONFLICT,"HOUSING_TRANSFER_CONFLICT",message);}
    private ApiException missing(String message) {return new ApiException(HttpStatus.NOT_FOUND,"HOUSING_TRANSFER_NOT_FOUND",message);}
    private record Stay(long id,int version,long bedId,String place) {}
    private record BedTarget(long bedId,long buildingId,String building,String room,String floor,String bed,String campus,String status,boolean bedEnabled,boolean roomEnabled,boolean buildingEnabled) {
        boolean enabledChain() {return bedEnabled && roomEnabled && buildingEnabled;}
        boolean assignable() {return enabledChain() && "VACANT".equals(status);}
        String place() {return campus+" "+building+" "+room+" "+floor+" "+bed;}
    }
    private record Row(long id,long studentId,String studentUsername,String studentName,long departmentId,long sourceAssignmentId,int sourceVersion,long sourceBedId,String sourcePlace,String status,String reason,String preferredArea,String materialNote,String reviewComment,Long targetBedId,Long targetBuildingId,String targetPlace,LocalDateTime reserveUntil,boolean cancelRequested,int version,LocalDateTime updatedAt) {}
}
