import { campusEvidence, mapFeatures, mapToModel } from './eastCampusLayout'

export type CampusCategory = 'learning' | 'services' | 'sports' | 'landscape'
export type BuildingKind = 'building' | 'plaza' | 'sports' | 'water' | 'entrance' | 'service'

export interface CampusPlace {
  id: string
  name: string
  category: CampusCategory
  kind: BuildingKind
  x: number
  z: number
  width: number
  depth: number
  height: number
  description: string
  features: string[]
  sourceUrl: string
  verification: 'map-observed'
  heightStatus: 'display-only'
  reservableVenues?: string[]
}

export const buildingHeightMap: Record<string, number> = {
  'east-library': 5.8,
  'east-gym': 5.2,
  'statue-huangdi': 4.8,
  'east-swimming': 4.6,
  'east-admin': 4.4,
  'east-shuhua': 4.2,
  'east-zhiyuan': 4.2,
  'east-jingyi': 4.2,
  'east-wenhua': 4.2,
  'east-xinke': 4.0,
  'east-jiangong': 4.0,
  'east-jidian': 4.0,
  'east-construction': 4.0,
  'east-medical': 4.0,
  'east-affairs': 4.0,
  'east-art': 3.8,
  'east-dorms': 3.8,
  'east-h8': 3.8,
  'east-canteen-wenhua': 3.6,
  'east-jinxiu': 3.6,
  'south-gate': 3.6,
  'statue-kongzi': 3.5,
  'east-golf': 2.8,
}

const entries: {
  id: string
  name: string
  category: CampusCategory
  kind: BuildingKind
  description: string
  features: string[]
  reservableVenues?: string[]
}[] = [
  {
    id: 'east-gate',
    name: '东门 (正大门)',
    category: 'landscape',
    kind: 'entrance',
    description: '郑州工业应用技术学院东校区正门，东临万福路。主干道路正对校园中心绿化带及图书馆中轴线，校门立面与电动伸缩门按地图坐标定位。',
    features: ['万福路东入口', '校园主中轴起点', '车牌识别门禁'],
  },
  {
    id: 'east-library',
    name: '图书馆',
    category: 'learning',
    kind: 'building',
    description: '东校区核心标志性建筑，位于中心环路内，呈马蹄形环抱南向开放内庭。主体高约7层，藏书丰富，配备学术报告厅、数字化自修研讨舱及电子阅览室。',
    features: ['中心环岛地标', '开放式内庭', '学术报告中心'],
    reservableVenues: ['一楼学术报告厅(300座)', '二楼多功能研讨室(201)', '三楼学术自习研修舱(A-D)', '电子阅览室多媒体教学区'],
  },
  {
    id: 'east-square',
    name: '英才广场',
    category: 'landscape',
    kind: 'plaza',
    description: '位于图书馆西侧、艺术楼与体育区之间，是大型文艺演出、社团纳新文化节、开学迎新与毕业典礼的户外核心聚集广场。',
    features: ['图书馆西侧', '校园文化盛典集聚地', '开阔铺装广场'],
    reservableVenues: ['英才广场户外展演区', '社团文化集市指定展位'],
  },
  {
    id: 'east-shuhua',
    name: '书华楼',
    category: 'learning',
    kind: 'building',
    description: '教学主楼之一，位于图书馆正北侧、致远楼西侧。拥有方形合院式建筑格局，主要分布公共多媒体阶梯大教室与通识课研讨室。',
    features: ['北区教学主楼', '四合院式采光中庭', '阶梯智慧教室'],
    reservableVenues: ['书华楼101阶梯报告厅(240座)', '书华楼205录播研讨教室', '书华楼308多媒体大教室'],
  },
  {
    id: 'east-zhiyuan',
    name: '致远楼',
    category: 'learning',
    kind: 'building',
    description: '教学实验楼，位于书华楼东侧、秋月塘北岸西侧。同样采用围合内院建筑结构，内设信息技术、计算机基础及公共实训微机房。',
    features: ['北区实验教学楼', '围合内院', '公共计算机实验室'],
    reservableVenues: ['致远楼401公共微机实验室(120机位)', '致远楼403网络工程实训室', '致远楼208研讨型多媒体教室'],
  },
  {
    id: 'east-jingyi',
    name: '静逸楼',
    category: 'learning',
    kind: 'building',
    description: '教学综合楼，位于书华楼与致远楼南侧、紧邻图书馆东北环路，由两座相连建筑组团及双内院构成，承担跨学科教学研讨与外语视听说教学。',
    features: ['双内院组团', '图书馆东北侧', '多功能学术研讨区'],
    reservableVenues: ['静逸楼106外语语音实验室', '静逸楼201跨学科研讨间', '静逸楼305综合多媒体教室'],
  },
  {
    id: 'east-h8',
    name: 'H8学生公寓',
    category: 'services',
    kind: 'building',
    description: '北区学生公寓标杆楼栋，位于书华楼西北侧近北门区域，一楼设有宿管值班室、辅导员谈心谈话室及学生党建自治工作坊。',
    features: ['北区核心公寓', '一楼生活服务驿站', '智慧门禁宿管'],
    reservableVenues: ['H8一楼辅导员谈心谈话室', 'H8学生自治委员会活动室'],
  },
  {
    id: 'east-art',
    name: '艺术楼',
    category: 'learning',
    kind: 'building',
    description: '位于英才广场东北侧、游泳馆东侧，呈独特折线斜向组合。内设艺术设计展厅、声乐合唱排练厅、形体舞蹈房及美术画室。',
    features: ['艺术学院驻地', '斜向组合轮廓', '演艺排练多功能厅'],
    reservableVenues: ['艺术楼一层综合美术展厅', '艺术楼202形体舞蹈排练房', '艺术楼301声乐合唱排练厅', '艺术楼405琴房研习室'],
  },
  {
    id: 'east-xinke',
    name: '信科楼',
    category: 'learning',
    kind: 'building',
    description: '信息工程学院教学科研楼，位于图书馆西南侧主干道拐角。涵盖人工智能实验室、大数据工程实训室、物联网实验室及学科竞赛工作室。',
    features: ['信息工程学院', '软件实训基地', '竞赛创新创客工坊'],
    reservableVenues: ['信科楼302人工智能创新创客实验室', '信科楼401大数据研发实训机房', '信科楼503大学生IT创新创业研讨室'],
  },
  {
    id: 'east-jiangong',
    name: '建工楼',
    category: 'learning',
    kind: 'building',
    description: '建筑工程教学实训中心，位于信科楼南侧。建筑拥有斜向中庭，内设BIM虚拟仿真设计实验室、工程制图室及力学校准实训区。',
    features: ['斜向围合中庭', 'BIM工程设计中心', '结构实验大厅'],
    reservableVenues: ['建工楼201 BIM虚拟仿真实验大厅', '建工楼304工程制图专用教室'],
  },
  {
    id: 'east-wenhua',
    name: '文华楼',
    category: 'learning',
    kind: 'building',
    description: '位于图书馆东南侧的大型综合教学组团，拥有两个通透采光内庭及斜向翼楼，是文科、商贸、外语等专业的大班授课与阶梯报告厅集中区。',
    features: ['东南教学主枢纽', '双采光天井', '阶梯多媒体报告大厅'],
    reservableVenues: ['文华楼一层多功能学术报告厅(350座)', '文华楼208多媒体智慧阶梯教室', '文华楼402文创商务研讨工作坊'],
  },
  {
    id: 'east-clinic',
    name: '校医院服务点 (医务室)',
    category: 'services',
    kind: 'service',
    description: '位于校园便民核心区，提供日常急诊分诊、健康体检、常见病诊治、医保报销咨询及应急医疗保障服务。',
    features: ['全天候急诊医疗保障', '日常健康驿站', '医保政策咨询窗口'],
  },
  {
    id: 'east-jinxiu',
    name: '锦绣食府 (第一学生餐厅)',
    category: 'services',
    kind: 'building',
    description: '东校区主要餐饮生活中心，位于秋月塘西侧、文华楼东北侧。共三层，汇集各地风味小吃、大众自选餐线、清真餐厅及研学交流休闲角。',
    features: ['三层综合餐饮', '风味特色档口', '就餐交流休闲区'],
    reservableVenues: ['锦绣食府二层师生研学交流沙龙区'],
  },
  {
    id: 'east-swimming',
    name: '游泳馆',
    category: 'sports',
    kind: 'building',
    description: '位于校园西北角文体区，采用大跨度穹顶钢架结构。配备50米室内恒温标准游泳池与观众看台，满足体育课游泳教学与水上项目训练。',
    features: ['50米标准泳池', '大跨度穹顶结构', '恒温水处理系统'],
    reservableVenues: ['室内恒温泳池教学训练赛道(1-4号道)', '水上安全救生实训区'],
  },
  {
    id: 'east-gym',
    name: '室内综合体育馆 (红体馆)',
    category: 'sports',
    kind: 'building',
    description: '校园大型室内综合文体活动中心，位于西侧田径场东南侧。内设专业木地板室内篮球比赛主馆、羽毛球馆、乒乓球室及体能训练健身中心。',
    features: ['专业运动木地板', '千人观众看台', '室内多功能赛场'],
    reservableVenues: ['室内主篮球场(比赛/大型活动)', '羽毛球场(1-6号标准场地)', '乒乓球实训室(10副球台)', '体能力量训练健身房'],
  },
  {
    id: 'east-sports',
    name: '西区田径运动场',
    category: 'sports',
    kind: 'sports',
    description: '大型室外标准运动区，包括400米塑胶跑道、天然草坪足球场、室外排球场、灯光塑胶篮球场集群以及室外网球场。',
    features: ['400米标准塑胶跑道', '标准真草足球场', '成组灯光篮球场', '塑胶网球场'],
    reservableVenues: ['标准田径场天然草坪主足球场', '室外塑胶网球场(A/B场)', '西区室外灯光篮球场(3-6号半场)'],
  },
  {
    id: 'east-construction',
    name: '建筑工程学院教学组团',
    category: 'learning',
    kind: 'building',
    description: '位于校园南部大道西侧，包含土木工程力学实验室、建筑材料检测大厅、路桥工程模型室等实践教学基地。',
    features: ['南部工科实验组团', '建筑力学实验大厅', '路桥测量实训场地'],
    reservableVenues: ['土木工程实验力学示范大厅', '建筑材料检测实验室'],
  },
  {
    id: 'east-medical',
    name: '医学院教学实验组团',
    category: 'learning',
    kind: 'building',
    description: '位于校园南部大道东侧，设有医学形态学实验室、机能学实验室、临床护理模拟病房及虚拟解剖多媒体教学室。',
    features: ['南部医学教学基地', '临床模拟实训病房', '回字形采光天井'],
    reservableVenues: ['医学院临床护理模拟示范病房', '基础医学虚拟仿真实验室', '机能学多媒体综合实验厅'],
  },
  {
    id: 'east-water',
    name: '秋月塘景观水系',
    category: 'landscape',
    kind: 'water',
    description: '贯穿校园东侧的核心生态水景园林，南接生态湖面，北通东门亲水栈道。湖畔依柳环水，设有亲水平台与水上高尔夫挥杆练习场。',
    features: ['校园生态水景', '亲水绿化步道', '水上高尔夫湖面'],
  },
  {
    id: 'east-golf',
    name: '水上高尔夫练习场',
    category: 'sports',
    kind: 'building',
    description: '坐落于秋月塘东岸的特色体育教学与训练设施。设有临水挥杆发球打位及专业击球安全网，供体育特色选修课及学生课外拓展使用。',
    features: ['临湖水上打位', '特色体育选修设施', '专业发球练习台'],
    reservableVenues: ['水上高尔夫挥杆练习打位(1-8号打位)'],
  },
  {
    id: 'east-dorms',
    name: '北区学生公寓群',
    category: 'services',
    kind: 'building',
    description: '涵盖北区H1至H7多栋学生标准公寓，配备空调、独立卫浴、智慧洗衣房与热水供应系统，为数千名在校本科生提供优良住宿环境。',
    features: ['标准化学生公寓群', '智慧生活设施', '围合式宿舍园区'],
    reservableVenues: ['北区青年书舍自主研习角', '北区学生社区多功能活动室'],
  },
  {
    id: 'east-affairs',
    name: '学生事务综合服务中心',
    category: 'services',
    kind: 'service',
    description: '位于南区核心服务地带，集中设立一站式办事大厅，覆盖学籍成绩打印、奖助学金办理、共青团活动、心理健康团辅及资助咨询。',
    features: ['一站式师生服务大厅', '共青团活动阵地', '心理健康中心'],
    reservableVenues: ['综合服务中心一楼青年活动多功能厅', '综合服务中心203团学会议室', '心理健康教育中心团体辅导室'],
  },
  {
    id: 'east-admin',
    name: '行政管理中心',
    category: 'services',
    kind: 'building',
    description: '学校党政机关与各行政职能部门办公场所，位于学生事务中心南侧。内设第一行政会议室、校务信息发布厅及智慧校园指挥中心。',
    features: ['党政办公中枢', '多功能行政会议厅', '智慧校园指挥中心'],
    reservableVenues: ['行政中心第一多功能会议室', '行政中心远程视频会议室'],
  },
  {
    id: 'south-gate',
    name: '南大门 (正南门·学院路16号)',
    category: 'landscape',
    kind: 'entrance',
    description: '郑州工业应用技术学院东校区正南门（南大门），坐落于新郑市学院路16号，是学校法定注册校址所在地及礼仪性主校门。门前设有宽阔的迎宾集散广场与校名牌坊，北连迎宾大道南主轴。',
    features: ['学院路16号主大门', '南中轴礼仪主入口', '迎宾集散大广场', '智能化出入车牌识别'],
    reservableVenues: ['南大门迎宾集散广场(校级迎新/大型集会)'],
  },
  {
    id: 'statue-huangdi',
    name: '轩辕黄帝塑像',
    category: 'landscape',
    kind: 'building',
    description: '轩辕黄帝青铜塑像，巍然伫立于南大门内迎宾大道核心景观中轴。新郑市作为中华人文始祖轩辕黄帝故里，学校特立黄帝青铜雕像于主轴核心，象征传承华夏文明血脉与弘扬开拓创新之工匠精神。',
    features: ['迎宾大道核心主轴', '黄帝故里文化地标', '青铜一体铸造塑像', '校级文化瞻仰纪念广场'],
    reservableVenues: ['轩辕黄帝像瞻仰礼仪区'],
  },
  {
    id: 'statue-kongzi',
    name: '孔子行教圣像',
    category: 'landscape',
    kind: 'building',
    description: '孔子行教汉白玉圣像，坐落于图书馆西侧文化园林与博雅庭院内，面朝英才广场。圣像神态庄严睿智，拱手垂立，体现“立德树人、学以致用”的大学之道与尊师崇文深厚底蕴。',
    features: ['图书馆西侧文化庭院', '至圣先师行教像', '博雅晨读文化角'],
    reservableVenues: ['孔子像博雅晨读文化区'],
  },
  {
    id: 'east-jidian',
    name: '机电工程学院 (智能制造基地)',
    category: 'learning',
    kind: 'building',
    description: '机电工程学院教学实训与科研基地，位于西区文体场馆南侧、建工楼西南侧。涵盖智能制造现代加工实训大厅、工业机器人创新工坊、新能源汽车整车电控实验室及机电一体化产教融合中心。',
    features: ['智能制造现代加工大厅', '新能源汽车工程实验室', '工业机器人创新应用中心'],
    reservableVenues: ['机电工程智能制造加工实训大厅', '工业机器人技术应用实训室', '新能源汽车综合检测教学实验室'],
  },
  {
    id: 'east-canteen-wenhua',
    name: '文华餐厅 (第二学生食堂)',
    category: 'services',
    kind: 'building',
    description: '东校区第二学生餐饮中心（文华餐厅），位于文华楼南侧与东南生活园区。共二层，主营特色大众自选快餐、风味面食小吃、休闲饮品水吧及师生就餐休憩交流空间。',
    features: ['二层特色学生餐饮', '文华楼南侧生活配套', '大众自选餐线与风味小吃'],
    reservableVenues: ['文华餐厅二层创新美食实践体验角'],
  },
]

export const campusPlaces: CampusPlace[] = entries.map(entry => {
  const points = mapFeatures.filter(feature => feature.placeId === entry.id).flatMap(feature => feature.outline.map(mapToModel))
  const xs = points.map(point => point[0]), zs = points.map(point => point[1])
  const minX = Math.min(...xs), maxX = Math.max(...xs), minZ = Math.min(...zs), maxZ = Math.max(...zs)
  const h = entry.kind === 'building' ? (buildingHeightMap[entry.id] ?? 3.4) : entry.kind === 'service' ? 3.6 : 0.2
  return {
    ...entry,
    x: (minX + maxX) / 2,
    z: (minZ + maxZ) / 2,
    width: Math.max(3, maxX - minX),
    depth: Math.max(3, maxZ - minZ),
    height: h,
    sourceUrl: campusEvidence.mapUrl,
    verification: 'map-observed',
    heightStatus: 'display-only',
  }
})

export const campusCategories = [
  { id: 'all', label: '全部' },
  { id: 'learning', label: '教学' },
  { id: 'sports', label: '文体' },
  { id: 'landscape', label: '景观' },
  { id: 'services', label: '综合' },
] as const

export const campusTour = [
  'south-gate',
  'statue-huangdi',
  'east-admin',
  'east-library',
  'east-square',
  'statue-kongzi',
  'east-art',
  'east-gym',
  'east-sports',
  'east-jidian',
  'east-shuhua',
  'east-wenhua',
  'east-canteen-wenhua',
  'east-water',
  'east-gate',
]
