package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Repair assistant uses its own task kind. Leave and certificate tasks stay unchanged. */
public class V16__repair_agent extends BaseJavaMigration {
    private static final String GUIDE = """
        范围：校园综合服务平台的毕业设计演示规则，并非学校现行后勤制度。
        1. 师生从“我的校园报修”选择当前开放地点，填写故障类别、标题、现象和联系方式，保存草稿后再提交。助手不能猜测地点，也不能代替提交。
        2. 地点来自已配置的教室、教学楼公共部位或校园公共区域。私人宿舍房间暂不开放。地点停用或来源不完整时不能新选。
        3. 提交后由当前校区和类别的后勤人员分诊、派单或要求补充。维修人员只处理当前分配给本人的工单。
        4. 草稿、待分诊和待补充可以撤回。待接单之后需申请取消，由后勤同意或驳回。助手不能派单、接单、完成维修或代替验收。
        5. 维修结果由报修人验收。超过 48 小时未验收会提示并发送站内提醒，不自动完成。完成后可评价一次。
        6. 联系方式、图片和故障说明只在原工单对当前相关人员显示。助手查询只给出数量、状态和业务链接。
        """;
    @Override public void migrate(Context context) throws Exception {
        try (var statement = context.getConnection().createStatement()) {
            statement.execute("ALTER TABLE agent_task DROP CONSTRAINT ck_agent_task_kind");
            statement.execute("ALTER TABLE agent_task ADD CONSTRAINT ck_agent_task_kind CHECK (kind IN ('LEAVE','CERTIFICATE','REPAIR'))");
        }
        try (var insert = context.getConnection().prepareStatement("INSERT INTO agent_guide(slug,title,keywords,body) VALUES (?,?,?,?)")) {
            insert.setString(1, "repair");
            insert.setString(2, "校园报修办理指南");
            insert.setString(3, "报修,维修,故障,派单,分诊,后勤,验收,地点");
            insert.setString(4, GUIDE);
            insert.executeUpdate();
        }
    }
}
