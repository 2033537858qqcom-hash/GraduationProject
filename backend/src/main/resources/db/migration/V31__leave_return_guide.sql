-- Upgrade the original published demo guide, preserving withdrawn or independently versioned content.
UPDATE agent_guide
SET body=REPLACE(body,
 '6. 已通过、拒绝或撤回的记录只读；销假与通过后变更尚未实现。',
 '6. 请假已批准且已经开始后，学生返校可在原申请申报返校，填写实际返校说明，提交后等待负责辅导员确认销假。
7. 辅导员核实后确认销假，或填写理由退回学生重新核对。确认后保留原批准时间及材料，个人请假日程按返校申报时间结束。
8. 请假到期未申报返校时提醒学生及当前辅导员，不自动认定已经返校。原申请的返校、确认及退回动作保留在办理记录中。
9. 已拒绝或撤回的记录只读；已批准行程的续假或变更尚未实现，请联系辅导员核对。'),
 keywords=CONCAT(keywords,',返校'),version=version+1,updated_at=CURRENT_TIMESTAMP
WHERE slug='leave' AND version=1 AND status='PUBLISHED'
  AND body LIKE '%销假与通过后变更尚未实现%';
