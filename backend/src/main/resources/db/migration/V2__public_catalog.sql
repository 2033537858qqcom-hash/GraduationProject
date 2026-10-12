INSERT INTO campus(id,code,name,address) VALUES
  (1,'EAST','东校区','河南省郑州市新郑市学院路16号'),
  (2,'WEST','西校区','河南省郑州市新郑市新村镇107国道路东');
INSERT INTO department(id,name,kind) VALUES
  (1,'信息工程学院','COLLEGE'),(2,'机电工程学院','COLLEGE'),
  (3,'建筑工程学院','COLLEGE'),(4,'医学院','COLLEGE'),
  (5,'管理学院','COLLEGE'),(6,'艺术设计学院','COLLEGE');
INSERT INTO app_role(code,name) VALUES
  ('STUDENT','学生'),('TEACHER','教师'),('SERVICE_STAFF','校园服务人员'),
  ('CONTENT_EDITOR','内容编辑'),('CONTENT_REVIEWER','内容审核'),('ADMIN','系统管理员');
INSERT INTO permission(code,description) VALUES
  ('portal:read','读取本人学生门户'),('workbench:read','访问本人岗位入口'),
  ('content:edit','创建与编辑本人官网草稿'),('content:review','审核发布他人官网内容');
INSERT INTO role_permission(role_code,permission_code) VALUES
  ('STUDENT','portal:read'),('TEACHER','workbench:read'),('SERVICE_STAFF','workbench:read'),
  ('CONTENT_EDITOR','workbench:read'),('CONTENT_EDITOR','content:edit'),
  ('CONTENT_REVIEWER','workbench:read'),('CONTENT_REVIEWER','content:review'),
  ('ADMIN','workbench:read'),('ADMIN','content:edit'),('ADMIN','content:review');
INSERT INTO website_content(slug,category,title,summary,body,source_url,source_type,status,published_at) VALUES
  ('school-introduction','about','认识郑州工业应用技术学院',
   '郑州工业应用技术学院位于河南省新郑市，设东、西两个校区。',
   '本项目以郑州工业应用技术学院为背景，采用原创官网与校园服务平台设计。
学校公开名称及校区信息参考学校招生资料。院系、校历及建筑资料在正式发布前逐项核验，并保留来源与更新时间。',
   'https://zsb.zzuit.edu.cn/info/1003/1554.htm','PUBLIC_SOURCE','PUBLISHED',CURRENT_TIMESTAMP),
  ('platform-development','notice','校园综合服务平台开发说明',
   '本网站为毕业设计演示。学生账号、课程及通知使用演示数据。',
   '当前已建立学校信息展示、会话登录、个人资料、课表与消息查询，以及官网内容草稿和审核发布流程。
校园照片为视觉占位，3D 建筑位置将在核验学校地图后更新。预约、报修、成绩查询和 Agent 办理将在后续迭代接入。',
   NULL,'DEMO','PUBLISHED',CURRENT_TIMESTAMP);
