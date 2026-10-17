import type { CampusCategory, BuildingKind } from './manifest'

export interface PlaceDefinition {
  id: string
  name: string
  category: CampusCategory
  kind: BuildingKind
  description: string
  features: string[]
  relatedPlaceId?: string
  locationSource?: 'layout'
}

// Names/function zones follow the user-provided school distribution plan.
// Roof outlines follow preserved satellite imagery; no room inventory is inferred.
export const campusPlaceDefinitions: PlaceDefinition[] = [
  { id: 'east-gate', name: '东门', category: 'landscape', kind: 'entrance', description: '校园东侧出入口，邻近万福路。保留实景位置标记，校门形制及通行安排未作推断。', features: ['东侧入口', '邻万福路', '位置标记'] },
  { id: 'south-gate', name: '南门', category: 'landscape', kind: 'entrance', description: '学院路北侧、人文大道南端的校园主入口。场景用位置点表示，不用实心体块遮挡道路。', features: ['学院路', '人文大道', '校园南入口'] },
  { id: 'north-gate', name: '北门', category: 'landscape', kind: 'entrance', locationSource: 'layout', description: '分布图中位于外环北路、书华楼与H8北侧的校园入口。位置为图示对应，未复原门楼。', features: ['外环北路', '北侧入口', '图示位置'] },
  { id: 'east-library', name: '图书馆', category: 'learning', kind: 'building', description: '中环路核心环抱形建筑，南向开敞内庭。高德地图收录为图书馆，保留影像可见轮廓和中环路环岛关系。', features: ['中环路核心', '开放内庭', '南向开口'] },
  { id: 'east-square', name: '英才广场', category: 'landscape', kind: 'plaza', description: '位于图书馆西侧、体育区和艺术楼之间，按影像可见铺装范围近似描摹。', features: ['英才大道东南', '图书馆西侧', '开阔铺装'] },
  { id: 'east-shuhua', name: '书华楼', category: 'learning', kind: 'building', description: '位于中环路北侧、致远楼西侧。高德地图POI对应教学区，保留影像中的围合楼翼与内院。', features: ['中环路北侧', '围合内院', '北侧教学区'] },
  { id: 'east-zhiyuan', name: '致远楼', category: 'learning', kind: 'building', description: '位于书华楼东侧、静逸楼北侧。依据影像保留围合轮廓及内院，西临中环路北段。', features: ['书华楼东侧', '内院轮廓', '北侧教学区'] },
  { id: 'east-jingyi', name: '静逸楼', category: 'learning', kind: 'building', description: '位于中环路北侧、致远楼南侧。与西侧锦绣餐厅由校内道路明确分隔，独立成栋，保留围合楼体与内院绿化轮廓。', features: ['北部教学区', '独立内院', '邻中环路'] },
  { id: 'east-jinxiu-dining', name: '锦绣餐厅', category: 'services', kind: 'building', description: '位于中环路西北侧，书华楼南侧；高德地图收录为锦绣食府。与东侧静逸楼由道路自然分隔，为独立成栋的东校区主餐饮中心。', features: ['锦绣食府', '中环路西北', '独立餐饮楼', '师生食堂'] },
  { id: 'east-h8', name: 'H8', category: 'learning', kind: 'building', description: '位于外环北路南侧、书华楼西北侧；高德地图收录为H8主楼及H8副楼。按影像可见屋顶表达。', features: ['H8主楼', 'H8副楼', '外环北路南侧'] },
  { id: 'east-h1', name: 'H1', category: 'services', kind: 'service', locationSource: 'layout', description: '分布图标注在体育场东侧、信科楼组团西侧。以图示位置点保留，不臆造楼栋轮廓、层数或入口。', features: ['体育场东侧', '图示位置', '楼体待对应'] },
  { id: 'east-h2', name: 'H2', category: 'services', kind: 'service', locationSource: 'layout', description: '分布图标注在H1南侧、文华餐厅西北侧。以图示位置点展示，具体建筑轮廓尚待核对。', features: ['H1南侧', '图示位置', '楼体待对应'] },
  { id: 'east-art', name: '美术、音乐楼组团', category: 'learning', kind: 'building', description: '位于艺馨路西侧、游泳馆东侧。高德地图收录为艺术设计学院与音乐学院。按影像中的相连楼翼整体展示。', features: ['艺馨路', '艺术设计学院', '音乐学院', '斜向楼翼'] },
  { id: 'east-xinke', name: '信科楼组团', category: 'learning', kind: 'building', description: '位于图书馆西南侧、人文大道西侧。高德地图收录为信息工程学院与软件学院，分布图含大数据管理中心；按可见组合轮廓展示。', features: ['信息工程学院', '软件学院', '大数据管理中心'] },
  { id: 'east-jiangong', name: '建工楼', category: 'learning', kind: 'building', description: '位于信科楼南侧。高德地图与留存资料标注建工楼，保留斜向内庭轮廓，与南部建工学院和建工实训中心分别记录。', features: ['斜向围合', '开放内庭', '工科组团'] },
  { id: 'east-wenhua', name: '文华楼组团', category: 'learning', kind: 'building', description: '位于图书馆东南侧、中环路与人文大道交汇东侧。高德地图收录为商学院、外国语学院与管理学院。按双内院组合轮廓展示。', features: ['商学院', '外国语学院', '管理学院', '双内院'] },
  { id: 'east-clinic', name: '医务室（地图服务点）', category: 'services', kind: 'service', description: '依据留存地图中的医务室POI展示位置，不代表独立医院楼、实测楼层或入口。开放安排应以学校通知为准。', features: ['医疗服务点', '入口待核实'] },
  { id: 'east-jinxiu', name: '毓秀楼', category: 'learning', kind: 'building', description: '经高德地图与校方核准为毓秀楼，位于文华楼组团东侧、东侧水系西岸。与南侧的风华楼共同构成东校区临水经典教学楼宇组团。', features: ['毓秀楼', '东侧教学楼', '围合内院', '邻水景观'] },
  { id: 'east-fenghua', name: '风华楼', category: 'learning', kind: 'building', description: '位于锦绣楼南侧、东侧水系西岸。依据相应位置的可见围合屋顶表达，不并入锦绣楼或餐厅。', features: ['锦绣楼南侧', '独立内院', '邻水景观'] },
  { id: 'east-swimming', name: '游泳馆', category: 'sports', kind: 'building', description: '位于英才大道南侧，紧邻红体馆东南，美术音乐组团西侧。高德地图收录为游泳馆，保留近椭圆屋顶轮廓。', features: ['英才大道南侧', '紧邻红体馆', '近椭圆轮廓'] },
  { id: 'east-gym', name: '红体馆', category: 'sports', kind: 'building', description: '位于中华路与英才大道校内西门处。高德地图收录为红体馆与体育学院。对应影像中近圆形标志性屋顶。', features: ['英才大道', '华信西门内', '近圆形屋顶', '高度示意'] },
  { id: 'east-sports', name: '西侧体育场', category: 'sports', kind: 'sports', description: '西侧紧临中华路，影像可见田径场、场内球场及东南侧成组球场。保留平面方向和场地关系，看台与弧形屋盖尚待深化。', features: ['田径场', '成组球场', '邻中华路'] },
  { id: 'east-canteen-wenhua', name: '文华餐厅', category: 'services', kind: 'building', description: '位于文华餐厅路段、行政楼西侧、建工实训中心北侧。高德地图与分布图均对应此服务中心位置。', features: ['西南生活区', '行政楼西侧', '师生食堂'] },
  { id: 'east-construction-training', name: '建工实训中心', category: 'learning', kind: 'building', description: '位于文华餐厅南侧、建工学院北侧。按实景可见屋顶表示，对应建工实训基地，不推断具体实验室房间。', features: ['文华餐厅南侧', '南部实训区', '实训车间'] },
  { id: 'east-admin', name: '行政楼', category: 'services', kind: 'building', description: '位于人文大道中轴北端、文华餐厅东侧、学术报告厅西侧。高德地图收录为行政管理中心，关联对应位置影像轮廓。', features: ['行政管理中心', '南部中轴', '学术报告厅西侧'] },
  { id: 'east-auditorium', name: '学术报告厅', category: 'learning', kind: 'building', description: '位于行政管理中心东侧、外环东路与水系西岸。对应该处可见屋顶，不再将这一建筑标为高尔夫练习场。', features: ['学术报告厅', '行政管理中心东侧', '南部公共建筑'] },
  { id: 'east-affairs', name: '学生综合事务服务中心', category: 'services', kind: 'service', locationSource: 'layout', description: '高德地图收录为学生事务综合服务中心（位于外环东路）。分布图标注在致远楼东侧，当前保留图示位置点，楼体轮廓尚待测绘对应。', features: ['外环东路', '学生事务综合服务中心', '图示位置', '楼体待对应'] },
  { id: 'east-construction', name: '建工学院', category: 'learning', kind: 'building', description: '位于人文大道西南侧、建工实训中心南侧。高德地图收录为建筑工程学院。保留可见中庭与建筑轮廓。', features: ['建筑工程学院', '人文大道西南侧', '围合内院'] },
  { id: 'east-medical', name: '医学院、药学院组团', category: 'learning', kind: 'building', description: '位于南部人文大道东侧、邻外环东路。高德地图收录有药学院（及医学院教学区），按可见围合屋顶整体表达。', features: ['药学院', '医学院', '人文大道东南侧'] },
  { id: 'east-water', name: '东侧水系', category: 'landscape', kind: 'water', description: '依据实景表达东侧纵向景观水系（近外环东路与东门段）。分布图用于核对岸边建筑关系，保留水体波光流动效果。', features: ['东侧景观水系', '邻外环东路', '轮廓示意'] },
]
