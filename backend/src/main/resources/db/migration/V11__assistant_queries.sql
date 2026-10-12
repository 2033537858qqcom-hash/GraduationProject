INSERT INTO permission(code,description) VALUES('agent:query','规则检索及当前账号只读业务查询');
INSERT INTO role_permission(role_code,permission_code) SELECT code,'agent:query' FROM app_role;
CREATE TABLE agent_guide (
  slug VARCHAR(80) PRIMARY KEY,
  title VARCHAR(160) NOT NULL,
  keywords VARCHAR(500) NOT NULL,
  body TEXT NOT NULL,
  version INT NOT NULL DEFAULT 1,
  status VARCHAR(24) NOT NULL DEFAULT 'PUBLISHED',
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK (version > 0),
  CHECK (status IN ('PUBLISHED','WITHDRAWN'))
);
CREATE TABLE agent_run (
  id VARCHAR(36) PRIMARY KEY,
  user_id BIGINT NOT NULL,
  mode VARCHAR(24) NOT NULL,
  outcome VARCHAR(32) NOT NULL,
  tool_names VARCHAR(500) NOT NULL,
  prompt_tokens INT,
  completion_tokens INT,
  duration_ms BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(user_id) REFERENCES app_user(id),
  CHECK(mode IN ('LOCAL','DEEPSEEK'))
);
CREATE INDEX idx_agent_run_user ON agent_run(user_id,created_at);
INSERT INTO agent_guide(slug,title,keywords,body) VALUES
('leave','学生请假办理指南','请假,病假,事假,假别,销假',
'范围：校园综合服务平台的毕业设计演示规则，并非学校实际制度。
1. 从“我的办事”进入请假，填写类型、起止时间、事由及联系手机或邮箱，保存草稿。
2. 演示流程单次最多 30 天。提交时开始时间必须在未来；没有当前有效的负责辅导员或存在重叠请假时不能提交。
3. 当前 PDF / UTF-8 TXT 材料可选，最大 2 MB。辅导员可通过退回意见要求补充。按假别或时长配置的必需材料策略尚未接入。
4. 待补交时修改原申请、补充资料后重新提交。草稿、待审核和待补交可以撤回。
5. 负责行政班的辅导员记录意见后通过、拒绝或退回。通过后生成本人日程，不自动更改课程或考勤。
6. 已通过、拒绝或撤回的记录只读；销假与通过后变更尚未实现。'),
('certificate','在校证明申请与下载指南','证明,在校,学籍,证件,领取',
'范围：平台登记资料的毕业设计演示证明，不具备学校正式证明效力。
1. 从“我的办事”选择在校证明，填写用途并核对本人姓名、学号、学院、专业、行政班和校区，保存草稿后提交。
2. 当前只支持线上下载 PDF，不提供纸质领取。所属学院须有其他有效的学生事务人员，否则不能提交。
3. 学院学生事务人员核对资料和用途，可退回补交、拒绝或通过并生成结果。任课教师和管理员身份不自动获得审批权。
4. 补交沿用原申请；修改时重新确认当前登记资料。生成前再次核对资料快照，资料变化需重新确认。
5. 结果、状态、历史和消息同一事务保存；生成失败不会把申请标记为完成。
6. 完成后本人及当前授权的学院学生事务人员可下载带演示标记的 PDF；文件保留生成时资料，不能普通编辑覆盖。'),
('learning','学习信息查询指南','课表,课程,成绩,考试,学期,教学,学习',
'范围：平台教学演示记录，不代表学校实际教学计划。
1. 学生在“学习与课表”查看当前有效教学关系内的课程安排、已发布成绩与考试。
2. 教师查看本人授课安排；任课关系不授予学生私人请假或证明阅读权。
3. 未发布成绩、考试及其他学生记录不能通过学生查询获得。
4. 助手根据查询日期选择覆盖该日期的学期；没有唯一匹配学期时明确提示，并保留常规页面选择学期的入口。'),
('messages','个人消息与办事进度指南','消息,通知,未读,办事,进度,补交,待办,审核',
'范围：校园平台的毕业设计演示流程。
1. 个人消息支持分页和已读筛选，未读数统计本人全部未读消息。
2. 新请假和证明消息可进入当前仍有权限的原申请；旧普通消息不补造业务链接。
3. “我的办事”汇总本人请假和证明，区分草稿、待审核、待补交及已结束记录。
4. 辅导员待办只统计当前负责班级；学生事务人员待办只统计当前学院。
5. 助手第一期只查询，不能提交、撤回、审批、发布或代替本人确认。办理请进入对应页面。');
