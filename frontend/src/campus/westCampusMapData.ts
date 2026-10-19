import type { CampusPlace } from './manifest'
import type { LngLat } from './amapSdk'
import { westCampusEvidence, westLngLatToModel } from './westCampusLayout'

export interface CampusMeta {
  id: 'EAST' | 'WEST'; code: string; name: string; englishName: string; shortName: string
  address: string; center: LngLat; defaultZoom: number; description: string; badge: string
}
export const westCampusMeta: CampusMeta = {
  id: 'WEST', code: 'WEST', name: '西校区', englishName: 'WEST CAMPUS', shortName: '西区（107国道）',
  address: '河南省郑州市新郑市新村镇107国道路东', center: [113.7133, 34.4297], defaultZoom: 16.3,
  description: '新村镇107国道路东 · 校园楼宇、实训空间、学生生活与公共服务。', badge: '新村校区',
}
export const eastCampusMeta: CampusMeta = {
  id: 'EAST', code: 'EAST', name: '东校区', englishName: 'EAST CAMPUS', shortName: '东区（学院路）',
  address: '河南省郑州市新郑市学院路16号', center: [113.7576, 34.4126], defaultZoom: 16.2,
  description: '新郑市学院路16号 · 认识校园楼宇与公共空间，支持高德底图与3D沙盘漫游。', badge: '主校区',
}

interface WestPoi {
  id: string; name: string; category: CampusPlace['category']; kind: CampusPlace['kind']
  poiId: string; lnglat: LngLat; description: string
}
// Exact API results are retained in artifacts/campus-west-2026-10-19. A map POI
// is represented by a point, never by an invented rectangular building envelope.
const pois: WestPoi[] = [
  { id: 'west-teaching-1', name: '1号楼（东院）', category: 'learning', kind: 'building', poiId: 'B0M2P7NZFH', lnglat: [113.714875,34.428725], description: '西校区东院的1号楼。与创业孵化园内同名楼宇分别显示，具体教室与楼层待校内资料确认。' },
  { id: 'west-teaching-2', name: '2号楼', category: 'learning', kind: 'building', poiId: 'B0MUGRN4XM', lnglat: [113.712925,34.428275], description: '高德收录的西校区2号楼。楼内专业教室、房间容量及开放时间尚待学校资料补充。' },
  { id: 'west-lab-3', name: '叁号实验楼', category: 'learning', kind: 'building', poiId: 'B0L3AZRNFW', lnglat: [113.714625,34.427875], description: '高德收录名称为“叁号实验楼”，位于桃园路与万庄路交叉口西北方向。实验室分布与楼层尚待学校资料补充。' },
  { id: 'west-tech-center', name: '工程训练与创新中心', category: 'learning', kind: 'building', poiId: 'B0MUPHEDWG', lnglat: [113.712992,34.427465], description: '西校区的工程训练与创新中心，可在地图和沙盘中查找其收录位置。具体实训场所和开放安排以学校公布的信息为准。' },
  { id: 'west-mingde', name: '明德楼', category: 'learning', kind: 'building', poiId: 'B0M6NRFPLZ', lnglat: [113.712825,34.428525], description: '校方教学检查简报提及明德楼；此处采用高德“明德楼”记录，与附近另一条“明德楼男生宿舍”记录分别核对。' },
  { id: 'west-incubator', name: '大学生创业孵化园', category: 'learning', kind: 'service', poiId: 'B0FFGKQ6SY', lnglat: [113.711664,34.429715], description: '高德以“郑州工业学院大学生创业孵化园”收录，地图名称含学校旧称。园区范围与具体楼宇用途以学校最新资料为准。' },
  { id: 'west-incubator-1', name: '1号楼（创业孵化园）', category: 'learning', kind: 'building', poiId: 'B0L255CX0F', lnglat: [113.711825,34.429775], description: '创业孵化园内的1号楼，与东院1号楼位置不同。查找时请同时核对所在区域，避免同名楼宇混淆。' },
  { id: 'west-mechanical', name: '机电工程学院（地图标注）', category: 'learning', kind: 'service', poiId: 'B0FFL1H0ZE', lnglat: [113.71212,34.428157], description: '高德现有“西校区-机电工程学院”记录。院系办公地点可能调整，当前教学与办公安排请以院系公告为准。' },
  { id: 'west-canteen', name: '郑工餐厅', category: 'services', kind: 'service', poiId: 'B0MGCLFSH6', lnglat: [113.714775,34.427925], description: '餐厅位于叁号实验楼附近。此处分别展示两个地点的位置，具体楼体归属、入口及营业时间尚待校内资料确认。' },
  { id: 'west-dorm-4', name: '4号公寓楼', category: 'housing', kind: 'building', poiId: 'B0M29BRP8H', lnglat: [113.713925,34.428522], description: '西校区4号公寓楼。宿舍房型、床位、空调和门禁配置以学校住宿管理信息为准。' },
  { id: 'west-dorm-5', name: '5号宿舍楼', category: 'housing', kind: 'building', poiId: 'B0M3GSTHZ1', lnglat: [113.711975,34.426975], description: '西校区5号宿舍楼，位于万庄路与107国道交叉口东北方向。' },
  { id: 'west-dorm-7', name: '7号宿舍楼', category: 'housing', kind: 'building', poiId: 'B0LK1ACKWM', lnglat: [113.714425,34.429875], description: '西校区7号宿舍楼，位于育才路与桃园路交叉口西北方向。' },
  { id: 'west-parking', name: '西校区地上停车场', category: 'services', kind: 'service', poiId: 'B0L25OD2HT', lnglat: [113.7119,34.428932], description: '车辆准入与临时停放规定以校园现场管理为准，停车场收录位置可供查找。' },
  { id: 'west-express', name: '校内快递服务点', category: 'services', kind: 'service', poiId: 'B0LRBHN9T5', lnglat: [113.712953,34.428093], description: '高德收录的顺丰速运（工职新区店），地址包含西校区院内。运营商和营业时间可能变化。' },
]
export const westMapPlaces: CampusPlace[] = pois.map(poi => {
  const [x,z] = westLngLatToModel(poi.lnglat)
  return { id: poi.id, campusId: 'WEST', name: poi.name, category: poi.category, kind: poi.kind,
    x, z, width: 2.8, depth: 2.8, height: 5.36, description: poi.description,
    features: [poi.category === 'housing' ? '学生住宿' : poi.category === 'learning' ? '教学与实践' : '生活服务', '楼层与入口待核对'],
    sourceUrl: `https://www.amap.com/place/${poi.poiId}`, sourceScreenshot: westCampusEvidence.screenshot,
    locationType: 'point', verification: 'map-observed', heightStatus: 'display-only', lnglat: poi.lnglat }
})

export const westMapTour = ['west-incubator', 'west-incubator-1', 'west-mingde', 'west-teaching-2',
  'west-tech-center', 'west-lab-3', 'west-canteen', 'west-teaching-1', 'west-dorm-4', 'west-dorm-7', 'west-dorm-5']

export function westCampusMapFrame(width: number, height: number): { center: LngLat; zoom: number } {
  const pixelsPerMetre = Math.min(Math.max(160,width-80)/650, Math.max(160,height-90)/850)
  return { center: westCampusMeta.center, zoom: Math.max(14,Math.min(17,Math.log2(pixelsPerMetre*2*Math.PI*6378137/256))) }
}
