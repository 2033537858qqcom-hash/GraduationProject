import { campusEvidence, mapFeatures, mapToModel } from './eastCampusLayout'
export type CampusCategory = 'learning' | 'services' | 'sports' | 'landscape'
export type BuildingKind = 'building' | 'plaza' | 'sports' | 'water' | 'entrance' | 'service'
export interface CampusPlace {
  id: string; name: string; category: CampusCategory; kind: BuildingKind
  x: number; z: number; width: number; depth: number; height: number
  description: string; features: string[]; sourceUrl: string
  verification: 'map-observed'; heightStatus: 'display-only'
}
const entries: { id: string; name: string; category: CampusCategory; kind: BuildingKind; description: string; features: string[] }[] = [
  { id: 'east-gate', name: '东门', category: 'landscape', kind: 'entrance', description: '高德搜索结果标注的东校区东门，位于东侧道路与校内横向道路连接处。场景只标记入口位置，校门立面尚未建模。', features: ['地图入口标注', '东侧入口'] },
  { id: 'east-library', name: '图书馆', category: 'learning', kind: 'building', description: '图书馆位于校园中心环路内，楼体环抱内庭、南侧开口。此次放大核对后修正了此前填满内庭的轮廓，并补入南侧前场；广场名称也有校方通知的文字依据。', features: ['南侧开口', '中心环路'] },
  { id: 'east-square', name: '英才广场', category: 'landscape', kind: 'plaza', description: '高德地图标注的英才广场，位于图书馆西侧、游泳馆东南侧。广场范围按可见影像近似描摹。', features: ['图书馆西侧', '地图景观标注'] },
  { id: 'east-shuhua', name: '书华楼', category: 'learning', kind: 'building', description: '地图标注位于图书馆北侧、致远楼西侧。放大底图和卫星屋顶显示围合内院，已按可见轮廓独立呈现；当前用途和层数待核实。', features: ['北侧建筑', '围合内院'] },
  { id: 'east-zhiyuan', name: '致远楼', category: 'learning', kind: 'building', description: '高德放大图标注的北侧楼栋，位于书华楼东侧、静逸楼北侧。按可见围合屋顶和内院描摹，未推断其当前院系用途。', features: ['书华楼东侧', '围合内院'] },
  { id: 'east-jingyi', name: '静逸楼', category: 'learning', kind: 'building', description: '地图标注位于书华楼和致远楼南侧、图书馆东北侧，放大影像可见相连楼体与两处内院。附近虽有餐饮商户标注，但不据此认定整栋楼的用途。', features: ['两处内院', '图书馆东北侧'] },
  { id: 'east-h8', name: 'H8副楼', category: 'learning', kind: 'building', description: '地图标注位于书华楼西侧、北门附近。这里只关联标注周边可见屋顶，不扩展认定宿舍编号、主副楼关系或楼层。', features: ['北侧楼栋', '地图楼名'] },
  { id: 'east-art', name: '艺术楼', category: 'learning', kind: 'building', description: '地图标注位于游泳馆东侧、英才广场东北侧。建筑采用影像中可见的斜向组合轮廓，立面细节尚未复刻。', features: ['斜向建筑组合', '地图楼名'] },
  { id: 'east-xinke', name: '信科楼', category: 'learning', kind: 'building', description: '地图标注位于图书馆西南侧。按卫星图描摹建筑组合的位置与方向，具体教学功能仍待核实。', features: ['图书馆西南侧', '地图楼名'] },
  { id: 'east-jiangong', name: '建工楼', category: 'learning', kind: 'building', description: '放大地图标注位于信科楼南侧、图书馆西南侧。场景拆分了此前合并的南部楼体，按可见斜向轮廓和内院呈现；与更南侧的建筑工程学院地图标注分别记录。', features: ['信科楼南侧', '斜向内院'] },
  { id: 'east-wenhua', name: '文华楼', category: 'learning', kind: 'building', description: '地图标注位于图书馆东南侧。此次按放大底图与卫星屋顶修正了斜向围合和两处可辨内院，补入北侧接入道路；附近院系标注不作为楼栋用途的认定。', features: ['斜向围合', '两处可辨内院'] },
  { id: 'east-clinic', name: '医务室（地图标注）', category: 'services', kind: 'service', description: '高德在图书馆西南侧附近标注“郑州工业应用技术学院医务室”。这里显示位置符号；所属楼栋、楼层、门口及开放时间尚未确认，请以学校实际安排为准。', features: ['服务位置标注', '所属楼栋待核实'] },
  { id: 'east-jinxiu', name: '锦绣楼', category: 'learning', kind: 'building', description: '地图标注位于校园东侧水系以西，附近可见围合屋顶。场景只关联标注附近的轮廓，南侧相邻楼体保持未命名；当前用途待核实。', features: ['东侧建筑', '可见内庭'] },
  { id: 'east-swimming', name: '游泳馆', category: 'sports', kind: 'building', description: '地图标注位于校园西北部，英才广场西北侧。轮廓依据可见屋顶描摹，馆内空间与开放安排不在本场景中推断。', features: ['西北侧设施', '地图场馆标注'] },
  { id: 'east-sports', name: '西侧体育区', category: 'sports', kind: 'sports', description: '西侧卫星图中可见田径场、内部球场及东南侧成组球场，附近标注为体育学院。保留场地的真实方向和相对位置。', features: ['田径场', '成组球场'] },
  { id: 'east-construction', name: '建筑工程学院（地图标注）', category: 'learning', kind: 'building', description: '地图中的院系标注位于南部、南北向主路西侧。此处沿用地图名称，不据此认定当前院系名称或全部楼栋归属。', features: ['南部建筑', '院系归属待核实'] },
  { id: 'east-medical', name: '医学院（地图标注）', category: 'learning', kind: 'building', description: '地图中的院系标注位于南部、南北向主路东侧。可见围合楼体轮廓已描摹，现行楼名与用途待进一步核实。', features: ['南部建筑', '院系归属待核实'] },
  { id: 'east-water', name: '东侧水系', category: 'landscape', kind: 'water', description: '卫星图显示东侧纵向水面，以及东门北侧的另一段水面。按可见岸线近似描摹，水体正式名称尚未核实。', features: ['可见水面', '岸线描摹'] },
]
export const campusPlaces: CampusPlace[] = entries.map(entry => {
  const points = mapFeatures.filter(feature => feature.placeId === entry.id).flatMap(feature => feature.outline.map(mapToModel))
  const xs = points.map(point => point[0]), zs = points.map(point => point[1])
  const minX = Math.min(...xs), maxX = Math.max(...xs), minZ = Math.min(...zs), maxZ = Math.max(...zs)
  return { ...entry, x: (minX + maxX) / 2, z: (minZ + maxZ) / 2,
    width: Math.max(3, maxX - minX), depth: Math.max(3, maxZ - minZ), height: entry.kind === 'building' ? 3.2 : 0.2,
    sourceUrl: campusEvidence.mapUrl, verification: 'map-observed', heightStatus: 'display-only' }
})
export const campusCategories = [
  { id: 'all', label: '全部' }, { id: 'learning', label: '建筑' },
  { id: 'sports', label: '运动' }, { id: 'landscape', label: '景观' },
  { id: 'services', label: '服务' },
] as const
export const campusTour = ['east-gate', 'east-library', 'east-square', 'east-art', 'east-sports']
