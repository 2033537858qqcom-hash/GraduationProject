-- V26: 场地预约授权边界与负责关系精细化
-- 建立场地资源明确负责关系表 venue_manager
CREATE TABLE venue_manager (
  venue_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  PRIMARY KEY (venue_id, user_id),
  FOREIGN KEY (venue_id) REFERENCES venue_resource(id) ON DELETE CASCADE,
  FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE INDEX idx_venue_manager_user ON venue_manager(user_id);
CREATE INDEX idx_venue_manager_venue ON venue_manager(venue_id);

-- 从 ADMIN 角色中移除 reservation:manage 默认授权，将资源与技术管理分离
DELETE FROM role_permission WHERE role_code = 'ADMIN' AND permission_code = 'reservation:manage';

-- 将现有的场地资源初始负责人绑定到已有的 STUDENT_AFFAIRS 角色人员
INSERT INTO venue_manager (venue_id, user_id)
SELECT v.id, u.id
FROM venue_resource v
CROSS JOIN app_user u
JOIN user_role ur ON ur.user_id = u.id
WHERE ur.role_code = 'STUDENT_AFFAIRS';
