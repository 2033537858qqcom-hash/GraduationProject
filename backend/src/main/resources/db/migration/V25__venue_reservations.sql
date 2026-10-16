CREATE TABLE venue_resource (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  campus_id BIGINT NOT NULL,
  code VARCHAR(64) NOT NULL,
  name VARCHAR(120) NOT NULL,
  category VARCHAR(24) NOT NULL DEFAULT 'VENUE',
  place_id VARCHAR(64),
  building_name VARCHAR(100),
  location_detail VARCHAR(200) NOT NULL,
  capacity INTEGER DEFAULT 50,
  available_equipment VARCHAR(500),
  rules VARCHAR(1000),
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  version INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (campus_id) REFERENCES campus(id),
  UNIQUE (campus_id, code),
  CHECK (category IN ('VENUE', 'EQUIPMENT')),
  CHECK (capacity IS NULL OR capacity > 0)
);

CREATE TABLE venue_reservation (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  applicant_id BIGINT NOT NULL,
  request_key VARCHAR(80) NOT NULL,
  venue_id BIGINT NOT NULL,
  applicant_name VARCHAR(100) NOT NULL,
  identity_number VARCHAR(64) NOT NULL,
  contact_phone VARCHAR(60) NOT NULL,
  department_name VARCHAR(100) NOT NULL,
  class_name VARCHAR(100),
  venue_name VARCHAR(120) NOT NULL,
  place_id VARCHAR(64),
  location_detail VARCHAR(200) NOT NULL,
  activity_theme VARCHAR(200) NOT NULL,
  activity_type VARCHAR(32) NOT NULL DEFAULT 'ACADEMIC',
  expected_participants INTEGER NOT NULL,
  borrow_equipment VARCHAR(500),
  starts_at TIMESTAMP NOT NULL,
  ends_at TIMESTAMP NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  processing_comment VARCHAR(1000),
  version INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (applicant_id) REFERENCES app_user(id),
  FOREIGN KEY (venue_id) REFERENCES venue_resource(id),
  UNIQUE (applicant_id, request_key),
  CHECK (ends_at > starts_at),
  CHECK (expected_participants > 0),
  CHECK (activity_type IN ('ACADEMIC', 'CULTURAL', 'SPORTS', 'CLASS_MEETING', 'CLUB', 'VOLUNTEER', 'OTHER')),
  CHECK (status IN ('DRAFT', 'SUBMITTED', 'RETURNED', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'CANCELLED'))
);

CREATE TABLE venue_reservation_action (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  reservation_id BIGINT NOT NULL,
  reservation_version INTEGER NOT NULL,
  actor_id BIGINT NOT NULL,
  actor_name VARCHAR(100) NOT NULL,
  action VARCHAR(32) NOT NULL,
  status VARCHAR(24) NOT NULL,
  comment VARCHAR(1000) NOT NULL DEFAULT '',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (reservation_id) REFERENCES venue_reservation(id),
  FOREIGN KEY (actor_id) REFERENCES app_user(id)
);

CREATE INDEX idx_res_venue_time_status ON venue_reservation(venue_id, status, ends_at, starts_at);
CREATE INDEX idx_res_applicant_status ON venue_reservation(applicant_id, status, updated_at, id);
CREATE INDEX idx_res_status_time ON venue_reservation(status, starts_at, id);
CREATE INDEX idx_res_action_res_time ON venue_reservation_action(reservation_id, id);

ALTER TABLE user_notice DROP CONSTRAINT ck_notice_source;
ALTER TABLE user_notice ADD CONSTRAINT ck_notice_source CHECK(
 (source_type IS NULL AND source_id IS NULL AND source_view IS NULL) OR
 (source_type IS NOT NULL AND source_id IS NOT NULL AND source_view IS NOT NULL AND
 ((source_type IN ('LEAVE','CERTIFICATE') AND source_view IN ('MINE','REVIEW')) OR
  (source_type='REPAIR' AND source_view IN ('MINE','DISPATCH','WORK')) OR
  (source_type='HOUSING' AND source_view IN ('MINE','REVIEW','ASSIGN')) OR
  (source_type='RESERVATION' AND source_view IN ('MINE','REVIEW','MANAGE'))))
);

INSERT INTO permission(code, description) VALUES
  ('reservation:apply', '申请公共场地与设备借用，读取本人申请记录与撤回'),
  ('reservation:review', '审批公共场地与设备借用申请，执行同意/退回/拒绝'),
  ('reservation:manage', '维护场地与设备资源目录，配置可借用状态与时段规则');

INSERT INTO role_permission(role_code, permission_code) VALUES
  ('STUDENT', 'reservation:apply'),
  ('TEACHER', 'reservation:apply'),
  ('COUNSELOR', 'reservation:apply'),
  ('STUDENT_AFFAIRS', 'reservation:review'),
  ('STUDENT_AFFAIRS', 'reservation:manage'),
  ('ADMIN', 'reservation:manage');

INSERT INTO venue_resource (campus_id, code, name, category, place_id, building_name, location_detail, capacity, available_equipment, rules, enabled) VALUES
  (1, 'VEN-LIB-101', '图书馆一楼学术报告厅', 'VENUE', 'east-library', '图书馆', '图书馆一楼南侧大厅东翼', 300, '超清LED大屏,无线扩音麦克风(4支),舞台灯光,录播云台摄像机', '需提前24小时预约；严禁携带易燃易爆品；使用完毕需清理场地并关闭设备电源。', TRUE),
  (1, 'VEN-LIB-201', '图书馆二楼多功能研讨室', 'VENUE', 'east-library', '图书馆', '图书馆二楼201室', 40, '互动触控智慧黑板,视频会议终端,移动翻页白板', '限学术研讨与课题答辩借用；爱护电子设备，禁止大声喧哗。', TRUE),
  (1, 'VEN-LIB-301', '图书馆三楼学术研修舱A', 'VENUE', 'east-library', '图书馆', '图书馆三楼301区', 8, '多媒体投屏电视,降噪静音舱体,高速无线AP', '限学术创新小组研学；单次借用不超过4小时。', TRUE),
  (1, 'VEN-GYM-01', '室内综合体育馆主篮球馆', 'VENUE', 'east-gym', '室内综合体育馆', '体育馆一层主赛场', 800, '专业比赛电子计时计分大屏,主副裁判台,防撞保护垫,比赛用球', '必须穿软底运动鞋入场；禁止携带含糖饮料与外卖食品；文明用球。', TRUE),
  (1, 'VEN-GYM-02', '室内羽毛球馆1-2号场地', 'VENUE', 'east-gym', '室内综合体育馆', '体育馆副馆一层', 20, '羽毛球标准比赛网架,裁判高椅,场地照明系统', '限体育教学、校队训练及学生社团活动预约；需爱护塑胶地板。', TRUE),
  (1, 'VEN-SPO-01', '西区田径场天然草坪主足球场', 'VENUE', 'east-sports', '西区田径运动场', '西侧田径场中心草坪', 200, '标准足球门,角旗,训练标志碟,替补席遮阳棚', '禁止穿硬底钢钉足球鞋踩踏草坪；雨雪天气草皮养护期间暂停开放借用。', TRUE),
  (1, 'VEN-SPO-02', '西区室外灯光网球场A场', 'VENUE', 'east-sports', '西区田径运动场', '西侧球场区北侧', 10, '网球比赛网,球拍租借柜,高杆照明灯', '夜间使用请提前确认照明开关；借用完毕请随手关灯。', TRUE),
  (1, 'VEN-SQU-01', '英才广场户外展演中央区', 'VENUE', 'east-square', '英才广场', '图书馆西侧英才广场中央区域', 500, '户外便携式大功率移动音柱,折叠展架帐篷(10顶),活动配电箱', '需提交大型户外活动安全预案；分贝需符合校园声环境管控标准。', TRUE),
  (1, 'VEN-ART-101', '艺术楼一层综合美术展厅', 'VENUE', 'east-art', '艺术楼', '艺术楼一楼大厅展区', 150, '专业轨道射灯,可移动画架展墙,作品展示台', '布展严禁在展墙直接打钉或涂改；展览完毕须复原展厅。', TRUE),
  (1, 'VEN-ART-202', '艺术楼形体舞蹈排练房', 'VENUE', 'east-art', '艺术楼', '艺术楼二楼202室', 50, '落地镜墙,专业舞蹈把杆,高保真蓝牙功放音响', '入室换专用练功鞋；禁止携带水杯入木地板区域。', TRUE),
  (1, 'VEN-WEN-101', '文华楼一层学术报告大厅', 'VENUE', 'east-wenhua', '文华楼', '文华楼一楼东侧101大厅', 350, '双投影融合巨幕,有线报告台话筒,中央空调', '校级学术讲座、通识大讲堂优先保障；保持场内整洁。', TRUE),
  (1, 'VEN-SHU-101', '书华楼101多媒体阶梯教室', 'VENUE', 'east-shuhua', '书华楼', '书华楼一楼101室', 240, '激光多媒体投影仪,无线领夹麦,阶梯固定桌椅', '用于大班教学研讨与年级会议；严禁在桌面上刻画涂鸦。', TRUE),
  (1, 'VEN-ZHI-401', '致远楼401公共微机实验室', 'VENUE', 'east-zhiyuan', '致远楼', '致远楼四楼401实验室', 120, '120台极域电子教学机房,千兆交换机,教师演示中控', '禁止私自拔插网线或更改系统IP配置；遵守机房安全守则。', TRUE),
  (1, 'VEN-GLF-01', '秋月塘水上高尔夫1-4号打位', 'VENUE', 'east-golf', '水上高尔夫练习场', '秋月塘东岸挥杆区', 16, '专业高尔夫铁杆/木杆组,水上漂浮练习球(200颗),发球打位垫', '必须在划定打位内挥杆，严禁向岸边人员方向击球；穿防滑运动鞋。', TRUE),
  (1, 'VEN-AFF-101', '学生事务综合服务中心青年活动厅', 'VENUE', 'east-affairs', '学生事务综合服务中心', '一楼108活动大厅', 80, '活动高清投影,无线麦克风,拼接讨论桌椅,活动展板', '主要面向团学活动、志愿服务与社团骨干培训；可灵活拼组桌椅。', TRUE),
  (1, 'EQP-AV-01', '移动便携式扩音音响套装', 'EQUIPMENT', 'east-affairs', '学生事务综合服务中心', '一楼器材设备管理室', 1, '便携大功率拉杆音响,无线手持麦克风(2支),充电电源', '设备借用需凭有效证件登记；爱护设备，归还时当面验机。', TRUE),
  (1, 'EQP-REC-01', '会议与学术活动录播云台一体机', 'EQUIPMENT', 'east-library', '图书馆', '二楼技术支持服务台', 1, '4K AI追踪云台摄像机,便携式三角架,专业拾音麦克风阵列', '限教师与学术研讨团队申请；借用时由工作人员统一配置调试。', TRUE);
