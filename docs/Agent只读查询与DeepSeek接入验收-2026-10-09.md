# Agent 首期只读查询与 DeepSeek 接入验收

日期：2026-10-09，Asia/Shanghai。对象：Java / Vue 正式工程，V11 数据库迁移。

用户选择 DeepSeek。本轮完成登录后的动态角色、紧凑对话、版本化办理指南与当前账号的业务查询，以及 DeepSeek 工具规划接口适配。**当前本机已配置加密凭据，并通过真实 DeepSeek API 双工具查询和浏览器自然语言课表查询。** 本地关键词查询仍可使用。下文的模拟契约测试与真实服务联调分别记录；少量成功查询不能当作模型回答质量评测。

后续阶段已完成请假字段收集、可编辑预览与人工确认保存草稿，见[请假辅助验收](./Agent请假预览与人工确认验收-2026-10-09.md)。本文件保留首期接入与配置的验收记录。

## 实际交付

- 学生、教师及其他岗位登录后，在右下角点击动态角色打开对话。导航没有新增“校园助手”或“问一问”。官网与登录页不显示角色，登录后访问官网也不显示业务对话。
- 角色具有漂浮、眨眼、挥手、查询中与回复后动作；遵循减少动态效果偏好。切换页面显示与身份及当前业务有关的轻提示，允许关闭；不自动打开窗口或调用模型。
- 对话在桌面宽 366px、高度最多 550px。390px 验收中使用固定左右边距，修复滚动条造成的左侧越界；消息区域单独滚动，输入和发送按钮留在窗口底部。
- 默认“本地查询”，明确标注“关键词识别”；模型未配置时 DeepSeek 选项不可选。连接后按每个问题确认发送，避免自动调用。
- 结果展示来自服务端业务的事实卡、查询时间及受控业务链接。课表带学期和周参数，成绩与考试带学期参数，申请带种类与编号；打开课表结果能进入实际查询的那一周。
- 请假、证明、学习、消息四类指南采用当前发布版本；可打开完整原文、版本和更新时间。未找到资料、指南撤下、权限不足、输入不足与工具失败分别提示，不生成学校制度。
- 请假和证明详情显式显示当前申请及本人／岗位范围；可清除上下文。服务端先重新读取并鉴权，模型只知道业务类型，不收到从页面提取的具体编号。
- 关闭、重新打开、路由切换、退出或身份权限变化时清理窗口会话，最多保留当前窗口最近六次问答。不保存问题正文和业务结果，也不提供持久聊天历史。

个人教学与事务数据、指南规则仍是毕业设计演示。指南不是学校正式制度，证明不是学校正式证明。

## 工具契约

所有入口要求登录及 `agent:query`。权限来自实时身份和原领域服务；模型不能提供 `userId`、任意 URL 或 SQL。参数必须是已知字段与类型，其他字段拒绝。

| 工具 | 参数 | 范围与输出 | 不足或失败 |
| --- | --- | --- | --- |
| `search_guides` | 必填 `query`，1—200 字 | 所有获准账号；最多三个当前有效指南及原文、版本、链接；当前为小规模关键词检索 | 无匹配为 `NO_DATA`；撤下原文返回 404 |
| `my_timetable` | 可选 ISO `week`、正整数 `termId` | 学生当前教学关系或教师本人任课关系；课程、时间、地点；最多十二条及总数 | 无唯一覆盖日期的学期为 `NEEDS_INPUT`；错误日期拒绝 |
| `my_grades` | 可选 `termId` | 学生本人已发布成绩及学分；最多十二条 | 教师、事务等身份不能调用；未发布记录不可见 |
| `my_exams` | 可选 `termId` | 学生本人已发布考试及时间地点；最多十二条 | 同上，不返回草稿考试 |
| `my_affairs` | 可选 `kind`、`status` 枚举 | 本人请假和证明；总数及最近最多八条，状态、版本、更新时间与业务入口 | 不返回用途、事由、处理意见、联系方式和材料 |
| `my_notice_summary` | 无 | 本人消息总数与全部未读数、消息页面入口 | 查询不会标记消息为已读 |
| `my_review_pending` | 无 | 当前负责班级的辅导员待办／当前学院的证明待办；数量与列表入口 | 无岗位范围则拒绝；草稿和已结束记录不计入待办 |
| `application_status` | 可选 `kind`、`id`、`view` | 明确指定且有权限的申请，或合法页面上下文；仅状态、版本、更新时间及入口 | 缺少种类编号提示补充；不得借用另一业务种类的页面编号；越权不返回记录 |

所有工具均只读，没有提交、撤回、审批、生成证明、发布、派单或授权能力。岗位范围变化后按最新授权重新核对；模型等待期间会话版本改变，结果交付被终止。显示的旧卡片只是当时的查询快照，重新查询与点击原页面均重新鉴权。

学期默认依据查询日期唯一匹配；没有唯一匹配不猜测，常规页面仍可查看并选择学期，DeepSeek 查询可在问题中明确学期编号。本地模式不解析任意自然语言参数。此处只补齐学习页面接收受控 `term` / `week` 深链接；原检查 F07 的常规页面默认学期选择、F08 的教务 URL / 草稿问题仍未完成整体修复。

## DeepSeek 接入与数据边界

接入采用 DeepSeek 的普通 Chat Completions Function Calling。默认模型 `deepseek-flash`，显式关闭 thinking，输出上限 800 tokens、非流式，一次问题最多四个不同工具。配置默认模型依据本轮核验的[官方 API 文档](https://api-docs.deepseek.com/api/create-chat-completion/)，工具协议见[官方 Tool Calls 指南](https://api-docs.deepseek.com/guides/tool_calls/)。模型名称可能调整，可在本地通过 `DEEPSEEK_MODEL` 选择账户实际可用模型。

实际流程：

1. 本地校验身份、页面上下文及本次发送确认。
2. 将当前问题、页面业务类型、日期和可用工具契约发送到固定 HTTPS API。没有发送历史问答、账号身份编号、从页面提取的申请编号或私有业务结果。**用户自己写在问题中的内容会发送给 DeepSeek**，因此不要在问题中粘贴密钥、证件或私有材料。
3. 只接受模型给出的工具名及参数；模型自由文本和推理内容不展示、不作为业务结论。未知、重复、超额或当前身份不可用的工具计划，在执行之前整体拒绝；每个允许的工具另校验参数。
4. 原 Java 业务服务在本地执行查询，并生成事实卡、来源与受控链接。工具结果不继续传给模型，不做第二轮模型总结。
5. 模型失败明确报错，允许用户主动选择本地查询；不会把失败伪装成 DeepSeek 成功。请求在本地边界检查中被拒绝时，也标明没有调用模型。

连接超时 5 秒，请求超时 25 秒。每个账号最多一项进行中的查询、每分钟三十次；当前限制存在单个后端实例内，后续多实例需要共享限流。固定 API 不跟随重定向，没有自动重试。浏览器“停止等待”只停止等待响应，服务器或外部模型请求可能继续到超时，因此当前按钮没有声称取消模型任务。

`agent_run` 只保存账号、模式、结果类别、工具名、用量（如果上游提供）、耗时和时间。没有问题正文、模型文本、具体工具参数、个人结果和密钥；目前没有用量管理页面或费用结算。模型账户计费与额度以服务方实际记录为准。

## 本地配置

推荐使用项目启动脚本。首次配置或更换密钥，在根目录运行：

```powershell
cd D:\GraduationProject
.\scripts\Set-DeepSeekKey.ps1
```

输入不回显，凭据使用 Windows 当前用户 DPAPI 加密，保存在 `%LOCALAPPDATA%\ZzuitCampus\deepseek-api-key.dpapi`，不写入项目。此加密文件不能跨 Windows 用户直接使用。随后在两个 PowerShell 终端分别启动：

```powershell
# 后端终端
cd D:\GraduationProject
$env:SERVER_PORT = '18080'
.\scripts\Start-Backend.ps1
```

```powershell
# 前端终端
cd D:\GraduationProject
$env:CAMPUS_API_TARGET = 'http://127.0.0.1:18080'
.\scripts\Start-Frontend.ps1 -Port 18090
```

后端脚本读取加密凭据，只传给后端进程环境；退出时清理脚本加载的密钥环境变量。未指定 `DEEPSEEK_ENABLED` 时自动启用，明确设置为 `false` 时保持关闭；外部已设置的密钥优先使用。当前本机已按此方式运行，访问 `http://127.0.0.1:18090/portal`，无需重复配置。

也可仅在后端运行终端临时配置。关闭当前后端后，在新的 PowerShell 终端运行以下步骤；已运行的 Java 进程不会自动读取另一个终端刚设置的环境变量。密钥不放前端或仓库。

```powershell
cd D:\GraduationProject\backend
$env:DEEPSEEK_ENABLED = 'true'
$env:DEEPSEEK_MODEL = 'deepseek-flash'
$agentSecret = Read-Host '输入本地 DeepSeek API Key' -AsSecureString
$agentSecretPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($agentSecret)
try {
    $env:DEEPSEEK_API_KEY = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($agentSecretPtr)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($agentSecretPtr)
    $agentSecret.Dispose()
    Remove-Variable agentSecret, agentSecretPtr
}
$env:SERVER_PORT = '18080'
try {
    mvn spring-boot:run
} finally {
    Remove-Item Env:DEEPSEEK_API_KEY -ErrorAction SilentlyContinue
}
```

前端按工程 README 设置 `CAMPUS_API_TARGET` 并访问 18090。后端启动且配置具备时，DeepSeek 选项可用；这代表本地配置就绪，**不代表每次启动都探测账户密钥、额度或模型可用性**。本次另执行了下文的真实调用核验。遇到错误保留问题，显示可用的错误提示，不展示上游原文或密钥。用户可随时选“本地查询”；后端禁用模型时将 `DEEPSEEK_ENABLED` 设为 `false` 后重启。

## 自动验证

| 检查 | 实际结果 | 证据 |
| --- | --- | --- |
| 后端全量 `mvn -B package` | 122 项通过；新增 21 项 Agent HTTP 集成与 4 项 DeepSeek 客户端契约测试 | `artifacts/agent-2026-10-09/backend-package-final.log`、Surefire |
| 前端 `pnpm test` | 24 项通过；包含上下文、受控链接、身份提示和学习深链接 | `frontend-tests-final.log` |
| ESLint / 类型 / Vite 构建 | 通过，无 ESLint 警告；Three.js 独立块仍有既有 500KB 提示 | `frontend-lint-final.log`、`frontend-build-final.log` |
| 原库升级及只读复核 | V10 → V11；服务 UP，角色工具不同；初次验收时模型尚未配置 | `main-backend.log`、`main-backend-final.log`、`main-smoke.json` |

新增检查覆盖匿名与 CSRF、来源可读及撤下、无来源不编造、他人上下文在模型前拦截、本人资料最小化、岗位差异、写操作拒绝、参数注入、模型假结论不显示、错误密钥原文不暴露、真实 HTTP 超时、未配置或未确认发送、并发、查询期间身份变化、错误／重叠学期、上下文种类不匹配，以及本地与模型模式区分。上述自动测试使用隔离 H2 和本机 HTTP 模拟服务，没有真实 DeepSeek 网络调用，也没有测得模型正确率或 token 实际费用。真实服务验证另见下节。

## 真实服务联调补充

收到用户用于本地接入的密钥后，完成加密保存、接口和页面核验。记录只包含状态、工具、耗时与用量，不包含密钥、问题正文或个人业务结果。

| 场景 | 实际结果 | 证据（`artifacts/agent-2026-10-09/`） |
| --- | --- | --- |
| 模型目录 | DeepSeek `/models` 返回 200，账户可用 `deepseek-flash`、`deepseek-v4-pro` | `deepseek-models-live.json` |
| 真实 API 查询 | 普通演示问题同时查下周课表和请假天数；`my_timetable`、`search_guides` 均 OK，`modelUsed=true`；耗时 1697ms，上游报告输入 1130 / 输出 91 tokens | `deepseek-live-smoke.json` |
| 浏览器自然语言 | 输入“帮我看看下个星期的课程安排。”，返回 2026-10-12 起一周的两条演示课程，显示“DeepSeek 规划 · 本地执行” | `deepseek-live-browser.jpg`、`deepseek-live-browser-checks.json` |
| 逐次发送确认 | 未勾选时发送不可用；成功回复后勾选状态自动重置；本次页面 error / warn 为 0 | `deepseek-live-browser-checks.json` |
| 启动脚本 | Windows 加密凭据可读取，脚本退出清理密钥，明确禁用配置不被覆盖；前端默认 5173、指定 18090 的参数均可传递 | `deepseek-startup-checks.json`、`frontend-startup-checks.json` |

当晚原开发服务停止后，使用上述两个启动脚本恢复主库后端 18080 和前端 18090，再完成页面核验。本次主库新增助手查询审计；没有保存、提交或审批申请，联调后的本人统一办事仍为 0。模型调用仅负责工具规划，个人查询结果留在本地。

这些记录验证本次账户与连接可用、选定问题能得到预期工具计划；没有评测所有问法、角色或复杂任务，也没有测得模型正确率和实际费用。

## 浏览器验收

使用 `localhost:18091 → 18081` 与独立内存库，主库通过 `127.0.0.1:18090 → 18080` 保留。浏览器验收不是自动回归流水线。

| 场景 | 已确认结果 |
| --- | --- |
| 学生对话与课表 | 动态入口、DeepSeek 未连接、本地两条下周课程；结果跳转后显示 10/12—10/18 的实际课表 |
| 请假指南 | 明确演示规则、30 天、来源 V1；打开完整原文，路由切换清除上一问答 |
| 当前证明 | 通过普通页面保存一份隔离草稿；助手只显示本人 `#1` 草稿 V0，不展示用途和身份字段；允许清除上下文 |
| 越权与写请求 | 查询其他同学成绩、要求直接提交被拒绝；原申请仍是草稿 V0 |
| 教师 | 本人授课可读，链接进入 `/teaching/timetable` 对应周；成绩查询为权限不足，不展示学生成绩；教师首页建议为课表和未读消息 |
| 学生事务 | 待办最初为 0；用普通学生接口提交隔离草稿后重新查询为 1，链接可进入学院处理；选中申请显示岗位上下文与待审核 V1 |
| 手机 | 390×844 下对话位于视口内，无窗口内部横向溢出；输入、按钮与滚动区域可见；临时视口已复位 |
| 登录与官网边界 | 退出后角色消失；指南深链接要求登录并成功回到原指南；登录后访问官网也没有助手 |
| 浏览器日志 | 本轮最终页面捕获的 error / warn 为空 |

初次隔离验收截图保存在 `artifacts/agent-2026-10-09/`：`student-chat-desktop.jpg`、`student-guide-desktop.jpg`、`application-context-desktop.jpg`、`teacher-chat-desktop.jpg`、`affairs-chat-desktop.jpg`、`student-chat-mobile.jpg`。测试草稿及后续提交只在内存库；当时主库复核请假、证明、统一办事与证明待办均为 0，待补录教室仍为 6。初次验收未在主库 POST 助手查询或申请；后续真实助手联调见上一节，仍未写入申请。

停主库后保存的升级前备份为 `artifacts/agent-2026-10-09/campus-before-v11.mv.db`，335872 字节，SHA-256：`CEB19CA2F06023F95B27CB42D7D74948C9AB1290D09647119B0A37634711DDC3`。备份与现有 V10 PDF 验收文件均保留。不要直接把旧数据库覆盖到正在运行的服务上。

## 剩余范围

本轮是 AGT-01 / AGT-02 的首批只读能力与 AGT-08 / AGT-09 的部分上下文和契约，不代表 AGT 全部完成。持久会话（AGT-07）、流式回复、知识库上传／审校管理、文档索引、真实模型评测、运维用量面板、MySQL 联调和多实例限流尚未完成。

下一阶段建议先完成“辅助整理请假／证明草稿 → 人工检查 → 普通页面确认提交”。审批仍由授权岗位处理；完整活动、预约、报修和住宿能力后续按业务闭环推进。
