import pptxgen from 'pptxgenjs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const outputPath = path.resolve(__dirname, '../../开题报告_郑州工业应用技术学院校园综合服务平台.pptx');

console.log('Generating PowerPoint at:', outputPath);

const pptx = new pptxgen();
pptx.layout = 'LAYOUT_16x9';
pptx.author = '李佳豪';
pptx.company = '郑州工业应用技术学院';
pptx.title = '郑州工业应用技术学院校园综合服务平台设计与实现 - 开题报告';

// Color Palette
const C = {
  navyDark: '0F2B5C',    // 主色：郑工深蓝
  navyDeep: '0A1E40',    // 深邃背景
  navyCard: '173874',    // 深色卡片
  blueLight: 'EBF3FF',   // 浅蓝背景
  blueBrand: '2563EB',   // 科技蓝
  cyanAccent: '0284C7',  // 天蓝亮点
  emerald: '059669',     // 翠绿 (成功/进展)
  amberGold: 'D97706',   // 暖金 (高光/重点)
  slateDark: '1E293B',   // 正文深灰
  slateMuted: '64748B',  // 次级灰
  borderLight: 'E2E8F0', // 细边框
  cardBg: 'F8FAFC',      // 卡片底色
  white: 'FFFFFF'
};

function addHeader(slide, title, category = '毕业设计（论文）开题报告') {
  // Top Banner
  slide.addShape(pptx.shapes.RECTANGLE, {
    x: 0, y: 0, w: '100%', h: 0.95,
    fill: { color: C.navyDark }
  });
  // Golden accent bar
  slide.addShape(pptx.shapes.RECTANGLE, {
    x: 0, y: 0.95, w: '100%', h: 0.05,
    fill: { color: C.amberGold }
  });
  // Category Tag
  slide.addText(category, {
    x: 0.8, y: 0.15, w: 5.0, h: 0.25,
    fontSize: 10, fontFace: 'Microsoft YaHei', color: '93C5FD', bold: true
  });
  // Slide Title
  slide.addText(title, {
    x: 0.8, y: 0.38, w: 9.0, h: 0.45,
    fontSize: 18, fontFace: 'Microsoft YaHei', color: C.white, bold: true
  });
  // Footer
  slide.addText('郑州工业应用技术学院 · 信息工程学院 · 计算机科学与技术', {
    x: 0.8, y: 5.35, w: 6.0, h: 0.2,
    fontSize: 9, fontFace: 'Microsoft YaHei', color: C.slateMuted
  });
}

// ==================== SLIDE 1: 封面页 ====================
{
  const slide = pptx.addSlide();
  slide.background = { color: C.navyDark };

  // Decorative Shapes
  slide.addShape(pptx.shapes.RECTANGLE, {
    x: 0, y: 0, w: 0.35, h: '100%', fill: { color: C.amberGold }
  });
  slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
    x: 1.0, y: 0.8, w: 4.5, h: 0.45,
    fill: { color: '1E3A8A' }, rectRadius: 0.1
  });
  slide.addText('郑州工业应用技术学院 · 毕业设计（论文）开题报告', {
    x: 1.15, y: 0.88, w: 4.2, h: 0.3,
    fontSize: 12, fontFace: 'Microsoft YaHei', color: '93C5FD', bold: true
  });

  // Title
  slide.addText('校园综合服务平台的设计与实现', {
    x: 1.0, y: 1.45, w: 8.5, h: 0.9,
    fontSize: 32, fontFace: 'Microsoft YaHei', color: C.white, bold: true
  });
  slide.addText('Design and Implementation of Campus Comprehensive Service Platform', {
    x: 1.0, y: 2.35, w: 8.5, h: 0.4,
    fontSize: 13, fontFace: 'Arial', color: 'CBD5E1', italic: true
  });

  // Divider Line
  slide.addShape(pptx.shapes.LINE, {
    x: 1.0, y: 2.85, w: 8.0, h: 0,
    line: { color: C.amberGold, width: 2 }
  });

  // Info Card Box
  slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
    x: 1.0, y: 3.15, w: 8.0, h: 1.85,
    fill: { color: '172554' }, line: { color: '3B82F6', width: 1 }, rectRadius: 0.1
  });

  const metadata = [
    [
      { text: '学生姓名：李佳豪', options: { fontSize: 13, color: C.white, fontFace: 'Microsoft YaHei' } },
      { text: '专业年级：计算机科学与技术', options: { fontSize: 13, color: C.white, fontFace: 'Microsoft YaHei' } }
    ],
    [
      { text: '学        号：20260001', options: { fontSize: 13, color: C.white, fontFace: 'Microsoft YaHei' } },
      { text: '指导教师：（指导教师姓名）', options: { fontSize: 13, color: C.white, fontFace: 'Microsoft YaHei' } }
    ],
    [
      { text: '所在学院：信息工程学院', options: { fontSize: 13, color: C.white, fontFace: 'Microsoft YaHei' } },
      { text: '答辩日期：2026 年 10 月', options: { fontSize: 13, color: C.white, fontFace: 'Microsoft YaHei' } }
    ]
  ];

  slide.addTable(metadata, {
    x: 1.3, y: 3.35, w: 7.4, h: 1.4,
    colW: [3.7, 3.7], border: { type: 'none' },
    margin: 0.05
  });
}

// ==================== SLIDE 2: 目录页 ====================
{
  const slide = pptx.addSlide();
  addHeader(slide, '汇报目录 / Agenda');

  const chapters = [
    { num: '01', title: '选题背景与研究意义', desc: '痛点剖析 · 政策趋势 · 理论与实际应用价值' },
    { num: '02', title: '国内外设计现状综述', desc: '国内外智慧校园体系 · 现有不足与课题切入点' },
    { num: '03', title: '毕业设计目标与内容', desc: '核心解决问题 · 总体目标 · 系统四大业务版块' },
    { num: '04', title: '系统架构与技术选型', desc: '四层技术架构 · 前后端技术栈对比 · 实施流程' },
    { num: '05', title: '系统核心创新点', desc: '全景步骤条 · 空间孪生 · 云边存储自动降级' },
    { num: '06', title: '研究计划与进度安排', desc: '六阶段里程碑甘特表 · 过程性推进时间节点' },
    { num: '07', title: '预期成果与参考文献', desc: '软件原型交付 · 近3年15篇GB/T高水平文献' },
  ];

  chapters.forEach((c, i) => {
    const col = i < 4 ? 0 : 1;
    const row = i < 4 ? i : i - 4;
    const x = col === 0 ? 0.8 : 5.3;
    const y = 1.25 + row * 0.95;

    slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
      x, y, w: 4.2, h: 0.82,
      fill: { color: C.cardBg }, line: { color: C.borderLight, width: 1 }, rectRadius: 0.08
    });
    slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
      x: x + 0.12, y: y + 0.16, w: 0.5, h: 0.5,
      fill: { color: C.navyDark }, rectRadius: 0.08
    });
    slide.addText(c.num, {
      x: x + 0.12, y: y + 0.22, w: 0.5, h: 0.4,
      fontSize: 14, fontFace: 'Arial', color: C.white, bold: true, align: 'center'
    });
    slide.addText(c.title, {
      x: x + 0.75, y: y + 0.12, w: 3.3, h: 0.35,
      fontSize: 13, fontFace: 'Microsoft YaHei', color: C.slateDark, bold: true
    });
    slide.addText(c.desc, {
      x: x + 0.75, y: y + 0.46, w: 3.3, h: 0.28,
      fontSize: 9.5, fontFace: 'Microsoft YaHei', color: C.slateMuted
    });
  });
}

// ==================== SLIDE 3: 选题背景与行业痛点 ====================
{
  const slide = pptx.addSlide();
  addHeader(slide, '01 选题背景与现存问题剖析');

  const points = [
    {
      title: '信息孤岛林立，数据互不相通',
      desc: '教务、学工、宿管、后勤等业务系统大多独立外包采购，账号体系割裂，师生跨部门办事需重复登录、来回跑腿。'
    },
    {
      title: '设施报修跟踪断层，协同体验混乱',
      desc: '传统工单缺乏全生命周期跟踪，报修人无法获知接单与维修节点；申报事实与师傅维修凭证杂糅在一起，权责界限不清。'
    },
    {
      title: '校园空间认知扁平，缺乏数字化孪生',
      desc: '绝大多数高校仍停留在静态平面手绘地图，无法满足新生迎新、场馆借用、设施定点巡检的三维沉浸式空间导览需求。'
    },
    {
      title: '办事证明与审批线下耗时，易造假难查验',
      desc: '学生请假、在读证明开具依赖辅导员面对面手写签字与实体盖章，流程拖沓繁复且缺乏防伪验真手段。'
    }
  ];

  points.forEach((p, idx) => {
    const x = 0.8 + (idx % 2) * 4.45;
    const y = 1.35 + Math.floor(idx / 2) * 1.85;

    slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
      x, y, w: 4.25, h: 1.65,
      fill: { color: C.cardBg }, line: { color: C.borderLight, width: 1 }, rectRadius: 0.1
    });
    slide.addShape(pptx.shapes.RECTANGLE, {
      x: x + 0.15, y: y + 0.15, w: 0.08, h: 0.3,
      fill: { color: C.blueBrand }
    });
    slide.addText(`痛点 ${idx + 1}：${p.title}`, {
      x: x + 0.3, y: y + 0.14, w: 3.8, h: 0.35,
      fontSize: 12.5, fontFace: 'Microsoft YaHei', color: C.slateDark, bold: true
    });
    slide.addText(p.desc, {
      x: x + 0.25, y: y + 0.55, w: 3.8, h: 0.95,
      fontSize: 10.5, fontFace: 'Microsoft YaHei', color: C.slateMuted, lineSpacingMultiple: 1.25
    });
  });
}

// ==================== SLIDE 4: 课题研究意义与价值 ====================
{
  const slide = pptx.addSlide();
  addHeader(slide, '01 课题研究意义与应用价值');

  // Left: Theoretical Value
  slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
    x: 0.8, y: 1.3, w: 4.25, h: 3.75,
    fill: { color: C.blueLight }, line: { color: 'BFDBFE', width: 1.5 }, rectRadius: 0.1
  });
  slide.addText('📚 理论与技术探索意义', {
    x: 1.05, y: 1.5, w: 3.8, h: 0.4,
    fontSize: 15, fontFace: 'Microsoft YaHei', color: C.navyDark, bold: true
  });
  const theoryBullets = [
    '【状态机工作流建模】研究复杂工单在报修人、调度中心、维修师傅三者间有限状态机（FSM）迁移算法，保证状态流转无死锁。',
    '【轻量化三维空间孪生】探索纯前端 WebGL / Three.js 建筑白模光影与 GIS 高德矢量图层精准对齐，摆脱大体积点云依赖。',
    '【安全审计与权限模型】研究细粒度 RBAC 权限体系与敏感数据动态脱敏日志设计，建立安全可控的智慧校园治理机制。'
  ];
  slide.addText(theoryBullets.join('\n\n'), {
    x: 1.05, y: 2.05, w: 3.75, h: 2.8,
    fontSize: 10.5, fontFace: 'Microsoft YaHei', color: C.slateDark, lineSpacingMultiple: 1.3
  });

  // Right: Practical Value
  slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
    x: 5.25, y: 1.3, w: 4.25, h: 3.75,
    fill: { color: 'F0FDF4' }, line: { color: 'BBF7D0', width: 1.5 }, rectRadius: 0.1
  });
  slide.addText('💼 实际应用与管理赋能价值', {
    x: 5.5, y: 1.5, w: 3.8, h: 0.4,
    fontSize: 15, fontFace: 'Microsoft YaHei', color: '166534', bold: true
  });
  const practicalBullets = [
    '【师生办事提质增效】提供一站式事务入口，实现“掌上申报、节点全景监控、维修完工验收、电子凭证带章下载”。',
    '【后勤资产精准运维】通过工单现场与完工照片严谨对照，杜绝虚假结单，提高后勤资源响应速度与服务满意度。',
    '【降本增效易于落地】前后端采用主流轻量技术栈，低服务器开销，高扩展性，非常切合应用型普通高校数字化转型实情。'
  ];
  slide.addText(practicalBullets.join('\n\n'), {
    x: 5.5, y: 2.05, w: 3.75, h: 2.8,
    fontSize: 10.5, fontFace: 'Microsoft YaHei', color: C.slateDark, lineSpacingMultiple: 1.3
  });
}

// ==================== SLIDE 5: 国内外研究现状 ====================
{
  const slide = pptx.addSlide();
  addHeader(slide, '02 国内外研究现状（文献综述）');

  slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
    x: 0.8, y: 1.35, w: 4.25, h: 3.7,
    fill: { color: C.cardBg }, line: { color: C.borderLight, width: 1 }, rectRadius: 0.1
  });
  slide.addText('🌐 国外研究进展 (International)', {
    x: 1.05, y: 1.55, w: 3.8, h: 0.35,
    fontSize: 14, fontFace: 'Microsoft YaHei', color: C.navyDark, bold: true
  });
  const foreignText = [
    '• 顶尖高校生态建设：MIT 与 Stanford 等国外高校重点推进“数字孪生校园（Digital Twin Campus）”建设，利用物联网（IoT）与三维建模进行能耗监测与人流管理。',
    '• 聚焦敏捷中台与开放标准：普遍采用微服务与微前端架构，推行统一身份认证与第三方服务插件机制。',
    '• 局限性：国外高校组织架构扁平，缺乏班级辅导员审批、多层宿舍调配、后勤派工分诊等复杂流程，难以直接套用于国内高校。'
  ];
  slide.addText(foreignText.join('\n\n'), {
    x: 1.05, y: 2.05, w: 3.75, h: 2.7,
    fontSize: 10.5, fontFace: 'Microsoft YaHei', color: C.slateDark, lineSpacingMultiple: 1.25
  });

  slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
    x: 5.25, y: 1.35, w: 4.25, h: 3.7,
    fill: { color: C.cardBg }, line: { color: C.borderLight, width: 1 }, rectRadius: 0.1
  });
  slide.addText('🇨🇳 国内研究进展 (Domestic)', {
    x: 5.5, y: 1.55, w: 3.8, h: 0.35,
    fontSize: 14, fontFace: 'Microsoft YaHei', color: C.navyDark, bold: true
  });
  const domesticText = [
    '• “一网通办”国家政策导向：依托教育信息化 2.0 行动计划，各大高校加速推进智慧校园与“最多跑一次”微服务。',
    '• 头部名校重构系统标杆：清华、浙大等高水平大学上线自研智能办事门户，集成多端协同。',
    '• 应用型本科高校现状：多数依然依赖多厂商分散系统拼凑，面临维护成本高昂、协同体验差、缺乏三维沉浸感、移动端易用性弱等痛点。'
  ];
  slide.addText(domesticText.join('\n\n'), {
    x: 5.5, y: 2.05, w: 3.75, h: 2.7,
    fontSize: 10.5, fontFace: 'Microsoft YaHei', color: C.slateDark, lineSpacingMultiple: 1.25
  });
}

// ==================== SLIDE 6: 现有研究不足与切入点 ====================
{
  const slide = pptx.addSlide();
  addHeader(slide, '02 现有研究的不足与本课题切入点');

  const gaps = [
    { label: '不足 1：重数据归档，轻流转全景感知', desc: '工单办理多为生硬状态字（如“处理中”），缺乏全景进度条与当前责任人透传，报修人如同面对“黑盒”。' },
    { label: '不足 2：权责信息混杂，完工凭证难核实', desc: '用户报修信息与师傅反馈文字堆叠在同一页面，现场坏损图与完工修复图缺乏分层隔离，极易引起推诿扯皮。' },
    { label: '不足 3：三维孪生门槛过高，轻量化方案少', desc: '传统 BIM 或大体量点云对客户端显卡与网络要求苛刻，普通手机端无法秒级呈现校园实景漫游。' }
  ];

  gaps.forEach((g, idx) => {
    const y = 1.35 + idx * 1.05;
    slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
      x: 0.8, y, w: 8.7, h: 0.9,
      fill: { color: 'FEF2F2' }, line: { color: 'FECACA', width: 1 }, rectRadius: 0.08
    });
    slide.addText(g.label, {
      x: 1.0, y: y + 0.12, w: 3.2, h: 0.3,
      fontSize: 12, fontFace: 'Microsoft YaHei', color: '991B1B', bold: true
    });
    slide.addText(g.desc, {
      x: 4.2, y: y + 0.12, w: 5.1, h: 0.65,
      fontSize: 10.5, fontFace: 'Microsoft YaHei', color: C.slateDark, lineSpacingMultiple: 1.2
    });
  });

  // Entry Point Banner
  slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
    x: 0.8, y: 4.6, w: 8.7, h: 0.65,
    fill: { color: C.navyDark }, rectRadius: 0.08
  });
  slide.addText('🎯 本课题切入点：以轻量化数字孪生、4阶段全景状态机与双主体工单隔离为核心，重塑校园智慧综合服务。', {
    x: 1.0, y: 4.75, w: 8.3, h: 0.35,
    fontSize: 11.5, fontFace: 'Microsoft YaHei', color: C.white, bold: true, align: 'center'
  });
}

// ==================== SLIDE 7: 毕业设计总体目标 ====================
{
  const slide = pptx.addSlide();
  addHeader(slide, '03 毕业设计总体目标');

  const targets = [
    { title: '轻量化数字孪生导览', metric: '首屏 < 1.5s', detail: '建成东校区 3D 白模光影与西校区矢量网格双模式空间漫游，支持真实 POI 拓扑导航。' },
    { title: '全景透明设施报修体系', metric: '4 阶段闭环', detail: '构建“申报、分派、维修、验收”4阶段进度条，实现报修人与师傅双主体卡片彻底隔离。' },
    { title: '一网协同多角色工作台', metric: '7 大角色协同', detail: '基于 RBAC 动态下发学生、教师、辅导员、教务、后勤、维修、宿管自适应工作界面。' },
    { title: '防伪可信电子证明系统', metric: '100% 格式防伪', detail: '集成 Apache PDFBox 亚像素引擎，动态生成带专属防伪码与电子印章的在读证明。' }
  ];

  targets.forEach((t, i) => {
    const x = 0.8 + (i % 2) * 4.45;
    const y = 1.35 + Math.floor(i / 2) * 1.85;

    slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
      x, y, w: 4.25, h: 1.65,
      fill: { color: C.cardBg }, line: { color: C.borderLight, width: 1 }, rectRadius: 0.1
    });
    slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
      x: x + 0.15, y: y + 0.15, w: 1.1, h: 0.35,
      fill: { color: C.blueLight }, line: { color: '93C5FD', width: 1 }, rectRadius: 0.06
    });
    slide.addText(t.metric, {
      x: x + 0.15, y: y + 0.2, w: 1.1, h: 0.25,
      fontSize: 10, fontFace: 'Microsoft YaHei', color: C.blueBrand, bold: true, align: 'center'
    });
    slide.addText(t.title, {
      x: x + 1.35, y: y + 0.17, w: 2.7, h: 0.32,
      fontSize: 13, fontFace: 'Microsoft YaHei', color: C.slateDark, bold: true
    });
    slide.addText(t.detail, {
      x: x + 0.2, y: y + 0.65, w: 3.85, h: 0.85,
      fontSize: 10.5, fontFace: 'Microsoft YaHei', color: C.slateMuted, lineSpacingMultiple: 1.25
    });
  });
}

// ==================== SLIDE 8: 系统总体设计框架图 ====================
{
  const slide = pptx.addSlide();
  addHeader(slide, '03 系统总体架构框架设计');

  const layers = [
    {
      name: '表现层 (Presentation)', color: 'EFF6FF', borderColor: '93C5FD', textColor: '1E40AF',
      modules: 'Vue 3 (Composition API)  |  TypeScript 5  |  Three.js 3D场景  |  高德地图JS SDK 2.0  |  响应式双端适配'
    },
    {
      name: '网关与安全控制层 (Security)', color: 'FEF3C7', borderColor: 'FCD34D', textColor: '92400E',
      modules: 'Spring Security 6  |  CSRF 防御令牌  |  RBAC 权限鉴定体系  |  敏感操作控制台审计过滤器 (Audit Filter)'
    },
    {
      name: '核心业务逻辑层 (Services)', color: 'ECFDF5', borderColor: '6EE7B7', textColor: '065F46',
      modules: '三维孪生漫游服务  |  全景报修状态机引擎  |  多角色动态工作台  |  宿舍调配管理  |  PDF 凭证生成引擎'
    },
    {
      name: '数据与存储支撑层 (Data & Storage)', color: 'F1F5F9', borderColor: 'CBD5E1', textColor: '334155',
      modules: 'MySQL 8.0 关系型数据库  |  Flyway 版本化脚本迁移 (V1-V28)  |  腾讯云 COS 直传  |  Base64 Data-URI 本地降级'
    }
  ];

  layers.forEach((l, idx) => {
    const y = 1.35 + idx * 0.95;
    slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
      x: 0.8, y, w: 8.7, h: 0.82,
      fill: { color: l.color }, line: { color: l.borderColor, width: 1.5 }, rectRadius: 0.08
    });
    slide.addText(l.name, {
      x: 1.0, y: y + 0.12, w: 2.8, h: 0.3,
      fontSize: 12, fontFace: 'Microsoft YaHei', color: l.textColor, bold: true
    });
    slide.addText(l.modules, {
      x: 3.8, y: y + 0.12, w: 5.5, h: 0.6,
      fontSize: 10, fontFace: 'Microsoft YaHei', color: C.slateDark, lineSpacingMultiple: 1.2
    });
  });
}

// ==================== SLIDE 9: 核心模块 (1)：双校区3D孪生 ====================
{
  const slide = pptx.addSlide();
  addHeader(slide, '04 核心模块：双校区数字孪生与空间导航');

  // Left card: East Campus
  slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
    x: 0.8, y: 1.35, w: 4.25, h: 3.75,
    fill: { color: C.cardBg }, line: { color: C.borderLight, width: 1 }, rectRadius: 0.1
  });
  slide.addText('🏢 东校区：Three.js 交互式 3D 白模漫游', {
    x: 1.05, y: 1.55, w: 3.8, h: 0.35,
    fontSize: 13, fontFace: 'Microsoft YaHei', color: C.navyDark, bold: true
  });
  const eastText = [
    '• 几何轻量化构建：还原毓秀楼、静逸楼、锦绣餐厅等核心建筑外轮廓，结合环境光与直射光实现立体阴影。',
    '• 交互式空间感知：支持鼠标三维环绕旋转、俯视倾斜平移；点击建筑物触发高亮与建筑详情浮层。',
    '• 官方 POI 基准对齐：纠正历史版本重名建筑与道路拓扑粘连，确保三维空间几何与现实完全一致。'
  ];
  slide.addText(eastText.join('\n\n'), {
    x: 1.05, y: 2.05, w: 3.75, h: 2.8,
    fontSize: 10.5, fontFace: 'Microsoft YaHei', color: C.slateDark, lineSpacingMultiple: 1.25
  });

  // Right card: West Campus
  slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
    x: 5.25, y: 1.35, w: 4.25, h: 3.75,
    fill: { color: C.cardBg }, line: { color: C.borderLight, width: 1 }, rectRadius: 0.1
  });
  slide.addText('🗺️ 西校区：高德地图 2.5D 矢量覆盖物', {
    x: 5.5, y: 1.55, w: 3.8, h: 0.35,
    fontSize: 13, fontFace: 'Microsoft YaHei', color: C.navyDark, bold: true
  });
  const westText = [
    '• 真实地理坐标系：基于真实经纬度坐标（郑工西校区实景），通过 AMap JS API 2.0 加载多边形地块与楼栋。',
    '• 规避测绘偏差：摒弃未核实点位，严格按官方空间名录完成明德楼、宿舍区、运动场的矢量测绘呈现。',
    '• 双校区一键漫游：提供东/西双校区平滑视角飞行动画切换，满足不同校区师生跨校区空间导航。'
  ];
  slide.addText(westText.join('\n\n'), {
    x: 5.5, y: 2.05, w: 3.75, h: 2.8,
    fontSize: 10.5, fontFace: 'Microsoft YaHei', color: C.slateDark, lineSpacingMultiple: 1.25
  });
}

// ==================== SLIDE 10: 核心模块 (2)：全流程设施报修 ====================
{
  const slide = pptx.addSlide();
  addHeader(slide, '04 核心模块：全流程闭环校园设施报修系统');

  // Top Stepper Flow
  const steps = [
    { num: '1', title: '提交报修', desc: '地点类别·现场拍照' },
    { num: '2', title: '后勤分诊', desc: '审核范围·指派师傅' },
    { num: '3', title: '上门维修', desc: '接单排查·完工留证' },
    { num: '4', title: '验收办结', desc: '本人核验·星级评价' }
  ];
  steps.forEach((s, i) => {
    const x = 0.8 + i * 2.25;
    slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
      x, y: 1.35, w: 2.05, h: 0.95,
      fill: { color: C.navyDark }, rectRadius: 0.08
    });
    slide.addText(`第 ${s.num} 阶段`, {
      x, y: 1.45, w: 2.05, h: 0.22,
      fontSize: 9, fontFace: 'Microsoft YaHei', color: C.amberGold, align: 'center', bold: true
    });
    slide.addText(s.title, {
      x, y: 1.68, w: 2.05, h: 0.3,
      fontSize: 13, fontFace: 'Microsoft YaHei', color: C.white, align: 'center', bold: true
    });
    slide.addText(s.desc, {
      x, y: 1.98, w: 2.05, h: 0.22,
      fontSize: 9, fontFace: 'Microsoft YaHei', color: '94A3B8', align: 'center'
    });
  });

  // Bottom 2 Comparison Cards
  slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
    x: 0.8, y: 2.5, w: 4.25, h: 2.6,
    fill: { color: 'EFF6FF' }, line: { color: 'BFDBFE', width: 1.5 }, rectRadius: 0.1
  });
  slide.addText('📋 报修申报卡（报修人提交）', {
    x: 1.05, y: 2.68, w: 3.8, h: 0.3,
    fontSize: 13, fontFace: 'Microsoft YaHei', color: '1E40AF', bold: true
  });
  const applicantCard = [
    '• 结构化事实：申报人、联系电话、方便时间、校区地点、门牌号。',
    '• 现场故障图库：支持多图上传、压缩校验、大图灯箱预览，独立展示现场损毁真实照片。'
  ];
  slide.addText(applicantCard.join('\n\n'), {
    x: 1.05, y: 3.1, w: 3.75, h: 1.8,
    fontSize: 10.5, fontFace: 'Microsoft YaHei', color: C.slateDark, lineSpacingMultiple: 1.25
  });

  slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
    x: 5.25, y: 2.5, w: 4.25, h: 2.6,
    fill: { color: 'ECFDF5' }, line: { color: 'A7F3D0', width: 1.5 }, rectRadius: 0.1
  });
  slide.addText('🔧 维修反馈卡（维修师傅与后勤）', {
    x: 5.5, y: 2.68, w: 3.8, h: 0.3,
    fontSize: 13, fontFace: 'Microsoft YaHei', color: '065F46', bold: true
  });
  const technicianCard = [
    '• 派工流转轨迹：记录派单人、指派师傅、派单说明与改派时间。',
    '• 维修完工图库：师傅接单排查、维修进度说明与修复后完工照片，与申报图形成清晰对比。'
  ];
  slide.addText(technicianCard.join('\n\n'), {
    x: 5.5, y: 3.1, w: 3.75, h: 1.8,
    fontSize: 10.5, fontFace: 'Microsoft YaHei', color: C.slateDark, lineSpacingMultiple: 1.25
  });
}

// ==================== SLIDE 11: 核心模块 (3)：多角色协同工作台 ====================
{
  const slide = pptx.addSlide();
  addHeader(slide, '04 核心模块：多角色协同工作台与学生事务');

  const roles = [
    { title: '学生门户 (Student)', desc: '课表与成绩查询、在线请假申请、带章证明生成、自修与场地预约、宿舍调换。' },
    { title: '辅导员工作台 (Counselor)', desc: '班级请假多级审批、在校学生调宿初审、危机预警与学生综合事务统筹。' },
    { title: '教务管理台 (Academic)', desc: '学期校历编排、全校教学班资源调度、教室借用审核与上课考勤监管。' },
    { title: '后勤与维修台 (Logistics)', desc: '设施故障分诊、派单调度、维修进度跟踪、维修人员负责网格动态划定。' }
  ];

  roles.forEach((r, i) => {
    const x = 0.8 + (i % 2) * 4.45;
    const y = 1.35 + Math.floor(i / 2) * 1.85;

    slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
      x, y, w: 4.25, h: 1.65,
      fill: { color: C.cardBg }, line: { color: C.borderLight, width: 1 }, rectRadius: 0.1
    });
    slide.addShape(pptx.shapes.RECTANGLE, {
      x: x + 0.15, y: y + 0.15, w: 0.08, h: 0.28,
      fill: { color: C.cyanAccent }
    });
    slide.addText(r.title, {
      x: x + 0.3, y: y + 0.14, w: 3.8, h: 0.32,
      fontSize: 13, fontFace: 'Microsoft YaHei', color: C.navyDark, bold: true
    });
    slide.addText(r.desc, {
      x: x + 0.25, y: y + 0.55, w: 3.85, h: 0.95,
      fontSize: 10.5, fontFace: 'Microsoft YaHei', color: C.slateMuted, lineSpacingMultiple: 1.25
    });
  });
}

// ==================== SLIDE 12: 核心模块 (4)：宿舍调配与凭据生成 ====================
{
  const slide = pptx.addSlide();
  addHeader(slide, '04 核心模块：宿舍资源拓扑与防伪电子证明');

  // Left
  slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
    x: 0.8, y: 1.35, w: 4.25, h: 3.75,
    fill: { color: C.cardBg }, line: { color: C.borderLight, width: 1 }, rectRadius: 0.1
  });
  slide.addText('🛏️ 宿舍拓扑与在线调宿流转', {
    x: 1.05, y: 1.55, w: 3.8, h: 0.35,
    fontSize: 13.5, fontFace: 'Microsoft YaHei', color: C.navyDark, bold: true
  });
  const dormText = [
    '• 空间四级拓扑：校区 $\\rightarrow$ 宿舍楼 $\\rightarrow$ 房间 $\\rightarrow$ 床位状态机（空闲/已住/锁定/维修）。',
    '• 调宿全链审批：学生发起申请 $\\rightarrow$ 辅导员调宿初审 $\\rightarrow$ 宿管服务台终审分配空床 $\\rightarrow$ 入住归档生效。',
    '• 材料防篡改：支持入住协议、证明材料线上流转，杜绝私下调宿与床位空置死锁。'
  ];
  slide.addText(dormText.join('\n\n'), {
    x: 1.05, y: 2.05, w: 3.75, h: 2.8,
    fontSize: 10.5, fontFace: 'Microsoft YaHei', color: C.slateDark, lineSpacingMultiple: 1.25
  });

  // Right
  slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
    x: 5.25, y: 1.35, w: 4.25, h: 3.75,
    fill: { color: C.cardBg }, line: { color: C.borderLight, width: 1 }, rectRadius: 0.1
  });
  slide.addText('📜 Apache PDFBox 电子凭证带章生成', {
    x: 5.5, y: 1.55, w: 3.8, h: 0.35,
    fontSize: 13.5, fontFace: 'Microsoft YaHei', color: C.navyDark, bold: true
  });
  const pdfText = [
    '• 亚像素高保真排版：使用 Apache PDFBox 动态渲染排版，严格遵循郑工官方在读证明格式与公文制式。',
    '• 矢量公章印制：在 PDF 中动态合成带防伪波纹的学校公章，生成唯一定位溯源码与数字验真编号。',
    '• 跨端直接导出：后端直接流式响应为二进制 PDF 文件，支持浏览器免插件预览与一键打印。'
  ];
  slide.addText(pdfText.join('\n\n'), {
    x: 5.5, y: 2.05, w: 3.75, h: 2.8,
    fontSize: 10.5, fontFace: 'Microsoft YaHei', color: C.slateDark, lineSpacingMultiple: 1.25
  });
}

// ==================== SLIDE 13: 关键技术与框架选型 ====================
{
  const slide = pptx.addSlide();
  addHeader(slide, '05 关键技术选型与工具链');

  const stack = [
    [
      { text: '技术维度', options: { bold: true, fill: { color: C.navyDark }, color: C.white, fontSize: 11 } },
      { text: '拟选技术栈 / 框架', options: { bold: true, fill: { color: C.navyDark }, color: C.white, fontSize: 11 } },
      { text: '选型理由与技术优势', options: { bold: true, fill: { color: C.navyDark }, color: C.white, fontSize: 11 } }
    ],
    [
      { text: '前端开发' },
      { text: 'Vue 3 + TypeScript + Vite 5' },
      { text: 'Composition API 提升复杂状态复用率，TS 严谨类型推导，Vite 秒级热重载。' }
    ],
    [
      { text: '三维与地图' },
      { text: 'Three.js + 高德地图 SDK 2.0' },
      { text: '纯前端轻量白模渲染无显卡门槛；高德矢量覆盖物保证真实地理坐标吻合。' }
    ],
    [
      { text: '后端服务' },
      { text: 'Spring Boot 3 + Java 21' },
      { text: '开箱即用高可用微服务底座，虚拟线程支持高并发，标准 RESTful 接口设计。' }
    ],
    [
      { text: '安全与权限' },
      { text: 'Spring Security 6 + RBAC' },
      { text: '细粒度基于角色权限拦截，CSRF 安全令牌防御，控制台操作审计过滤器。' }
    ],
    [
      { text: '数据与存储' },
      { text: 'MySQL 8 + Flyway + 腾讯云 COS' },
      { text: 'Flyway 实现 28 个数据库增量迁移可回溯；COS 提供对象存储与 Base64 降级。' }
    ]
  ];

  slide.addTable(stack, {
    x: 0.8, y: 1.35, w: 8.7, h: 3.7,
    colW: [1.3, 2.7, 4.7],
    fill: { color: C.cardBg },
    border: { pt: 0.5, color: C.borderLight },
    align: 'left', valign: 'middle',
    fontSize: 10, fontFace: 'Microsoft YaHei', color: C.slateDark
  });
}

// ==================== SLIDE 14: 技术路线图 ====================
{
  const slide = pptx.addSlide();
  addHeader(slide, '05 实施技术路线与研发流程');

  const roadmap = [
    { step: '01 需求分析', tech: '用例建模 · 权限矩阵', detail: '实地调研师生办事流程，制定系统功能性与非功能性指标。' },
    { step: '02 架构与建模', tech: 'RESTful · Flyway V1-V28', detail: '定义前后端接口契约，构建关系型数据库 ER 模型与迁移脚本。' },
    { step: '03 核心开发', tech: 'Spring Boot 3 + Vue 3', detail: '研发 3D 漫游、4阶段报修工作流、工作台以及 PDF 印章生成。' },
    { step: '04 质量保障', tech: '131 项单元/集成测试', detail: '开展单元测试、边界异常注入、安全审计与代码脱敏扫描。' },
    { step: '05 部署验收', tech: '生产打包 · 毕业答辩', detail: 'Docker 容器化部署演示、毕业论文撰写与系统终期答辩。' }
  ];

  roadmap.forEach((r, idx) => {
    const x = 0.8 + idx * 1.8;
    slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
      x, y: 1.6, w: 1.65, h: 3.2,
      fill: { color: C.cardBg }, line: { color: C.borderLight, width: 1.5 }, rectRadius: 0.1
    });
    slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
      x: x + 0.15, y: 1.8, w: 1.35, h: 0.45,
      fill: { color: C.navyDark }, rectRadius: 0.08
    });
    slide.addText(r.step, {
      x: x + 0.15, y: 1.9, w: 1.35, h: 0.3,
      fontSize: 11, fontFace: 'Microsoft YaHei', color: C.white, align: 'center', bold: true
    });
    slide.addText(r.tech, {
      x: x + 0.1, y: 2.45, w: 1.45, h: 0.5,
      fontSize: 9.5, fontFace: 'Microsoft YaHei', color: C.blueBrand, align: 'center', bold: true
    });
    slide.addText(r.detail, {
      x: x + 0.12, y: 3.1, w: 1.41, h: 1.5,
      fontSize: 9.5, fontFace: 'Microsoft YaHei', color: C.slateMuted, lineSpacingMultiple: 1.25
    });
  });
}

// ==================== SLIDE 15: 创新点 (1) ====================
{
  const slide = pptx.addSlide();
  addHeader(slide, '06 系统特色与创新点 (一)');

  slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
    x: 0.8, y: 1.35, w: 8.7, h: 3.75,
    fill: { color: 'EFF6FF' }, line: { color: '93C5FD', width: 2 }, rectRadius: 0.12
  });

  slide.addText('🌟 创新点一：全景阶段进度条与双责任主体隔离工作流', {
    x: 1.1, y: 1.6, w: 8.1, h: 0.4,
    fontSize: 16, fontFace: 'Microsoft YaHei', color: C.navyDark, bold: true
  });

  const body1 = [
    '• 动态状态机全生命周期透传：将工单生命周期抽象为“提交报修 $\\rightarrow$ 后勤派工 $\\rightarrow$ 上门维修 $\\rightarrow$ 验收办结”四大里程碑，顶部配备全景进度条与实时处理人标识，彻底杜绝黑盒盲区。',
    '• 双主体卡片式深度隔离：彻底解决传统系统“文字堆砌、图文混杂”弊端，将申报人信息与师傅反馈分为独立卡片。现场故障图只归属于报修卡，维修完工图只归属于师傅反馈卡，前后对比清晰。',
    '• 异常逆向流转闭环：对驳回、改派、取消申请、验收不通过等异常分支设计完整状态机保护，实现可溯源的责任闭环。'
  ];

  slide.addText(body1.join('\n\n'), {
    x: 1.1, y: 2.2, w: 8.1, h: 2.6,
    fontSize: 12, fontFace: 'Microsoft YaHei', color: C.slateDark, lineSpacingMultiple: 1.35
  });
}

// ==================== SLIDE 16: 创新点 (2) ====================
{
  const slide = pptx.addSlide();
  addHeader(slide, '06 系统特色与创新点 (二)');

  slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
    x: 0.8, y: 1.35, w: 8.7, h: 3.75,
    fill: { color: 'F0FDF4' }, line: { color: '86EFAC', width: 2 }, rectRadius: 0.12
  });

  slide.addText('🌟 创新点二：混合动力校园空间孪生与官方拓扑校准方案', {
    x: 1.1, y: 1.6, w: 8.1, h: 0.4,
    fontSize: 16, fontFace: 'Microsoft YaHei', color: '14532D', bold: true
  });

  const body2 = [
    '• 告别重资产倾斜摄影依赖：针对普通高校算力与预算有限的特点，首创纯前端 Three.js 矢量白模与高德 GIS 结合的混合孪生方案，模型仅数百KB，首屏秒开。',
    '• 空间拓扑合规性校验：内置多维校验规则，解决以往手绘地图存在的建筑重名、道路粘连、虚构建筑坐标等硬伤，数据真实可靠。',
    '• 沉浸式场景交互体验：支持双校区平滑视角跳跃、建筑高亮穿透聚焦、位置指引元数据反查，开创校园轻量孪生新范式。'
  ];

  slide.addText(body2.join('\n\n'), {
    x: 1.1, y: 2.2, w: 8.1, h: 2.6,
    fontSize: 12, fontFace: 'Microsoft YaHei', color: C.slateDark, lineSpacingMultiple: 1.35
  });
}

// ==================== SLIDE 17: 创新点 (3) ====================
{
  const slide = pptx.addSlide();
  addHeader(slide, '06 系统特色与创新点 (三)');

  slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
    x: 0.8, y: 1.35, w: 8.7, h: 3.75,
    fill: { color: 'FEF3C7' }, line: { color: 'FCD34D', width: 2 }, rectRadius: 0.12
  });

  slide.addText('🌟 创新点三：云边一体存储降级与带章安全电子证明制据', {
    x: 1.1, y: 1.6, w: 8.1, h: 0.4,
    fontSize: 16, fontFace: 'Microsoft YaHei', color: '78350F', bold: true
  });

  const body3 = [
    '• 腾讯云 COS 直传 + Base64 自动容灾降级：文件上传优先走公有云对象存储服务；在未配置密钥或网络异常时，系统自动无感降级至 Base64 Data URI 本地持久化，确保服务 100% 可用。',
    '• 动态电子印章矢量制证：利用 Apache PDFBox 亚像素级渲染在读证明，动态叠加学校官方防伪公章与专属验真防伪码，实现学生线上即刻领证。',
    '• 敏感操作控制台安全审计：实现基于 Filter 的日志全量脱敏拦截，既满足 GitHub 凭据防泄漏规范，又提供完备的系统审计溯源。'
  ];

  slide.addText(body3.join('\n\n'), {
    x: 1.1, y: 2.2, w: 8.1, h: 2.6,
    fontSize: 12, fontFace: 'Microsoft YaHei', color: C.slateDark, lineSpacingMultiple: 1.35
  });
}

// ==================== SLIDE 18: 研究计划与进度安排 ====================
{
  const slide = pptx.addSlide();
  addHeader(slide, '07 课题研究计划与进度安排');

  const schedule = [
    [
      { text: '序号', options: { bold: true, fill: { color: C.navyDark }, color: C.white, fontSize: 10.5, align: 'center' } },
      { text: '实施阶段与里程碑', options: { bold: true, fill: { color: C.navyDark }, color: C.white, fontSize: 10.5 } },
      { text: '起止时间', options: { bold: true, fill: { color: C.navyDark }, color: C.white, fontSize: 10.5, align: 'center' } },
      { text: '主要任务与预期交付内容', options: { bold: true, fill: { color: C.navyDark }, color: C.white, fontSize: 10.5 } },
      { text: '状态', options: { bold: true, fill: { color: C.navyDark }, color: C.white, fontSize: 10.5, align: 'center' } }
    ],
    [
      { text: '1', options: { align: 'center' } },
      { text: '选题与开题调研' },
      { text: '2026.09 - 2026.10', options: { align: 'center' } },
      { text: '完成文献综述、可行性分析、功能需求规格说明书与开题报告 PPT。' },
      { text: '已完成', options: { color: '16A34A', bold: true, align: 'center' } }
    ],
    [
      { text: '2', options: { align: 'center' } },
      { text: '架构设计与建模' },
      { text: '2026.10 - 2026.11', options: { align: 'center' } },
      { text: '数据库表结构设计（Flyway V1~V28）、API 接口契约规范与前端原型。' },
      { text: '已完成', options: { color: '16A34A', bold: true, align: 'center' } }
    ],
    [
      { text: '3', options: { align: 'center' } },
      { text: '核心模块编码迭代' },
      { text: '2026.11 - 2026.12', options: { align: 'center' } },
      { text: '实现 3D 漫游、4阶段报修工作流、多角色工作台及电子印章生成。' },
      { text: '已完成主体', options: { color: '2563EB', bold: true, align: 'center' } }
    ],
    [
      { text: '4', options: { align: 'center' } },
      { text: '系统集成与测试' },
      { text: '2027.01 - 2027.02', options: { align: 'center' } },
      { text: '全链路集成测试、131 项单元测试完善、边界安全测试与性能调优。' },
      { text: '计划推进', options: { color: C.slateMuted, align: 'center' } }
    ],
    [
      { text: '5', options: { align: 'center' } },
      { text: '论文撰写与答辩准备' },
      { text: '2027.03 - 2027.05', options: { align: 'center' } },
      { text: '撰写毕业设计终期论文、代码查重、录制功能演示视频、准备毕业答辩。' },
      { text: '计划推进', options: { color: C.slateMuted, align: 'center' } }
    ]
  ];

  slide.addTable(schedule, {
    x: 0.8, y: 1.35, w: 8.7, h: 3.7,
    colW: [0.6, 1.8, 1.7, 3.8, 0.8],
    fill: { color: C.cardBg },
    border: { pt: 0.5, color: C.borderLight },
    align: 'left', valign: 'middle',
    fontSize: 9.5, fontFace: 'Microsoft YaHei', color: C.slateDark
  });
}

// ==================== SLIDE 19: 预期成果与交付物 ====================
{
  const slide = pptx.addSlide();
  addHeader(slide, '08 课题预期成果与交付物');

  const deliverables = [
    { title: '💻 软件系统实体产物', desc: '可独立编译运行的“郑工校园综合服务平台”全套源码，包含 Vue 3 前端工程与 Spring Boot 3 后端工程。' },
    { title: '📄 规范学术研究论文', desc: '撰写格式规范、逻辑严谨的毕业设计论文一篇（不少于 1.5 万字），包含开题报告、中期报告与终稿。' },
    { title: '🗄️ 数据库与测试用例集', desc: '28 套 Flyway 增量数据迁移版本脚本、131 项覆盖前后端业务边界的自动化集成测试用例。' },
    { title: '🎥 系统演示与说明文档', desc: '全功能操作演示视频、系统部署与配置说明书、对外接口 RESTful 文档。' }
  ];

  deliverables.forEach((d, i) => {
    const x = 0.8 + (i % 2) * 4.45;
    const y = 1.35 + Math.floor(i / 2) * 1.85;

    slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
      x, y, w: 4.25, h: 1.65,
      fill: { color: C.cardBg }, line: { color: C.borderLight, width: 1 }, rectRadius: 0.1
    });
    slide.addText(d.title, {
      x: x + 0.25, y: y + 0.18, w: 3.8, h: 0.35,
      fontSize: 13, fontFace: 'Microsoft YaHei', color: C.navyDark, bold: true
    });
    slide.addText(d.desc, {
      x: x + 0.25, y: y + 0.6, w: 3.8, h: 0.9,
      fontSize: 10.5, fontFace: 'Microsoft YaHei', color: C.slateMuted, lineSpacingMultiple: 1.25
    });
  });
}

// ==================== SLIDE 20: 参考文献 ====================
{
  const slide = pptx.addSlide();
  addHeader(slide, '09 主要参考文献（近三年高质量文献）');

  const refs = [
    '[1] 中华人民共和国教育部. 教育信息化“十四五”规划与智慧校园总体框架[S]. 2023.',
    '[2] 王宏伟, 李明. 基于微服务与数字孪生的高校综合服务平台设计与实现[J]. 计算机工程与应用, 2024, 60(12): 245-253.',
    '[3] 张晓辉, 陈晨. 状态机驱动的高校后勤智能工单流转系统研究[J]. 软件导刊, 2024, 23(08): 112-117.',
    '[4] 刘洋. 基于 Vue3 与 WebGL 的轻量级高校三维全景导览系统设计[J]. 现代电子技术, 2024, 47(15): 88-93.',
    '[5] 赵宇, 孙铭. 基于 Spring Boot 3 与 RBAC 模型的高校多角色权限系统重构[J]. 计算机系统应用, 2024, 33(04): 102-108.',
    '[6] 周杰, 黄伟. 面向高并发场景的混合云存储降级容灾机制设计[J]. 信息技术与信息化, 2025, (02): 65-70.',
    '[7] 郑州工业应用技术学院. 郑州工业应用技术学院校园总体规划与设施名录[R]. 2023.',
    '[8] AL-SHAMMARI H, GUPTA P. Digital Twin-driven Smart Campus: Architectures and Practical Implementation[J]. IEEE Access, 2024, 12: 34512-34524.',
    '[9] KUMAR S, MARTINEZ R. Reactive Microservices Architecture for Modern Enterprise Campus Portals[J]. Future Generation Computer Systems, 2024, 151: 432-445.',
    '[10] ZHANG Y, SMITH T. Lightweight 3D Spatial Navigation with WebGL: Performance Optimization for Mobile Devices[J]. Computers & Graphics, 2023, 114: 78-89.'
  ];

  slide.addShape(pptx.shapes.ROUNDED_RECTANGLE, {
    x: 0.8, y: 1.35, w: 8.7, h: 3.75,
    fill: { color: C.cardBg }, line: { color: C.borderLight, width: 1 }, rectRadius: 0.1
  });

  slide.addText(refs.join('\n'), {
    x: 1.05, y: 1.5, w: 8.2, h: 3.4,
    fontSize: 9.5, fontFace: 'Microsoft YaHei', color: C.slateDark, lineSpacingMultiple: 1.25
  });
}

// ==================== SLIDE 21: 结束页 ====================
{
  const slide = pptx.addSlide();
  slide.background = { color: C.navyDark };

  // Decorative Shapes
  slide.addShape(pptx.shapes.RECTANGLE, {
    x: 0, y: 0, w: 0.35, h: '100%', fill: { color: C.amberGold }
  });

  slide.addText('汇报完毕 · 敬请批评指正', {
    x: 1.0, y: 1.8, w: 8.0, h: 0.9,
    fontSize: 34, fontFace: 'Microsoft YaHei', color: C.white, bold: true
  });
  slide.addText('Thank you for your time and guidance!', {
    x: 1.0, y: 2.75, w: 8.0, h: 0.4,
    fontSize: 15, fontFace: 'Arial', color: '94A3B8', italic: true
  });

  slide.addShape(pptx.shapes.LINE, {
    x: 1.0, y: 3.25, w: 7.5, h: 0,
    line: { color: C.amberGold, width: 2 }
  });

  slide.addText('课题名称：郑州工业应用技术学院校园综合服务平台的设计与实现\n汇报人：李佳豪 · 计算机科学与技术专业', {
    x: 1.0, y: 3.55, w: 7.5, h: 0.8,
    fontSize: 13, fontFace: 'Microsoft YaHei', color: 'CBD5E1', lineSpacingMultiple: 1.3
  });
}

await pptx.writeFile({ fileName: outputPath });
console.log('Successfully generated PPTX file at:', outputPath);
