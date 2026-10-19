import type { MapFeature, MapPoint, MapRoad } from './eastCampusLayout'
import type { CampusCategory, BuildingKind } from './manifest'

// The photograph is 1920×1440. Authoring coordinates below use its proportional
// 1824×1368 display frame. This is a schematic: never transform these positions
// into GPS/GCJ-02 or use this scene as the live-map building overlay.
export const westPlanEvidence = {
  receivedAt: '2026-10-19', date: 'unknown', source: 'user-supplied-campus-distribution-plan',
  url: '/campus/west-campus-layout-reference.jpg',
  file: 'frontend/public/campus/west-campus-layout-reference.jpg',
  originalSize: [1920,1440], authoringSize: [1824,1368],
  scale: 'not-to-scale', north: '-z', height: 'display-only',
} as const
export const westPlanToModel = ([x,y]: MapPoint): [number,number] => [(x-950)*0.16,(y-665)*0.16]

export interface WestPlanPlace {
  id: string; name: string; category: CampusCategory; kind: BuildingKind
  center: MapPoint; rect?: readonly [number,number,number,number]
  description: string; features?: string[]; relatedPlaceId?: string
  height?: number
}

// A box represents exactly the box on the supplied diagram, not a surveyed roof.
// Mingde's classrooms, dormitories and medical room share one physical block;
// the bathhouse is a service point at the east end of the steel-structure canteen.
export const westPlanDefinitions: WestPlanPlace[] = [
  { id:'west-incubator-1', name:'1号楼', category:'learning', kind:'building', center:[275,172], rect:[201,116,149,112], description:'平面图北部西侧的1号楼，在4号楼西侧、华信路北侧。' },
  { id:'west-building-4', name:'4号楼', category:'learning', kind:'building', center:[498,173], rect:[419,132,159,80], description:'位于1号楼东侧、华信路北侧，与东侧学生公寓区的4号公寓分别标注。' },
  { id:'west-building-8', name:'8号楼', category:'learning', kind:'service', center:[1089,179], description:'平面图标在运动场东南、7号楼北侧。图中没有单独描绘其楼体，因此使用位置标记。' },
  { id:'west-dorm-7', name:'7号楼', category:'housing', kind:'building', center:[1093,265], rect:[1008,246,172,41], description:'华信路北侧、桃园路西侧的7号楼，位于8号楼南侧。' },
  { id:'west-sports-ground', name:'运动场', category:'sports', kind:'sports', center:[964,78], rect:[818,25,292,111], height:0.42, description:'平面图最北侧的运动场。按图纳入校园分布展示，当前开放及预约安排仍以学校公告为准。' },
  { id:'west-flag-square', name:'国旗广场', category:'landscape', kind:'plaza', center:[254,546], rect:[170,433,169,223], height:0.32, description:'诚毅楼西侧、华信路与成才大道之间的国旗广场。' },
  { id:'west-chengyi', name:'诚毅楼', category:'learning', kind:'building', center:[505,572], rect:[407,470,201,205], description:'图书馆西侧的诚毅楼，北邻文明大道、南邻成才大道，西侧为国旗广场。' },
  { id:'west-parking', name:'停车处', category:'services', kind:'service', center:[712,468], description:'文明大道南侧、图书馆西北侧标注的停车处。该位置为平面图位置，不代表精确车辆出入口。' },
  { id:'west-library', name:'图书馆', category:'learning', kind:'building', center:[836,578], rect:[750,507,175,141], height:6.2, description:'位于诚毅楼东侧、实验楼西侧，北邻文明大道、南邻成才大道。' },
  { id:'west-electricity', name:'购电中心', category:'services', kind:'service', center:[994,435], description:'平面图在实验楼西北侧标注购电中心，当前购电办理方式和开放时间待确认。' },
  { id:'west-experiment-building', name:'实验楼', category:'learning', kind:'building', center:[1110,530], rect:[1016,389,165,305], description:'桃园路西侧、图书馆东侧的实验楼，图示购电中心在其西北侧。尚不将其直接等同于校方其他资料中的“一号实验楼”。' },
  { id:'west-information', name:'科技信息楼', category:'learning', kind:'building', center:[313,846], rect:[222,768,186,151], description:'成才大道南侧的西侧楼宇，北侧为国旗广场方向，南侧为文化广场。' },
  { id:'west-culture-square', name:'文化广场', category:'landscape', kind:'plaza', center:[315,1037], rect:[224,963,185,145], height:0.32, description:'科技信息楼与5号楼之间的文化广场。' },
  { id:'west-dorm-5', name:'5号楼', category:'housing', kind:'building', center:[319,1230], rect:[232,1159,174,144], description:'西南侧楼宇，北侧为文化广场，东侧为城轨实训室方向。' },
  { id:'west-tech-center', name:'工程中心', category:'learning', kind:'building', center:[584,870], rect:[484,789,203,158], description:'成才大道南侧的工程中心，南侧依次为汽车实训室与城轨实训室。', features:['工程实践','平面图标注'] },
  { id:'west-auto-training', name:'汽车实训室', category:'learning', kind:'building', center:[585,1026], rect:[485,966,200,123], height:3.2, description:'工程中心南侧、城轨实训室北侧的汽车实训室。模型依照平面图表示其相对位置。' },
  { id:'west-rail-training', name:'城轨实训室', category:'learning', kind:'building', center:[585,1160], rect:[485,1100,200,127], height:3.2, description:'西南部实训区的城轨实训室，位于汽车实训室南侧、5号楼东侧。' },
  { id:'west-mingde', name:'明德楼', category:'learning', kind:'building', center:[842,907], rect:[761,791,163,236], description:'平面图在同一楼块内标注明德楼教室、明德楼宿舍与医务室，保留一个楼体，分别显示其功能。', features:['明德楼教室','明德楼宿舍','医务室'] },
  { id:'west-clinic', name:'医务室', category:'services', kind:'service', center:[876,1006], relatedPlaceId:'west-mingde', description:'平面图标在明德楼楼块南端的医务室，与明德楼关联显示，不另建独立楼体。' },
  { id:'west-teaching-2', name:'2号楼', category:'learning', kind:'building', center:[1056,857], rect:[975,826,161,61], description:'成才大道南侧、明德楼东侧的2号楼，南侧为3号楼。此处采用平面图位置，与高德同名记录分别呈现。' },
  { id:'west-lab-3', name:'3号楼', category:'learning', kind:'building', center:[1056,980], rect:[977,949,159,61], description:'2号楼南侧的3号楼，东侧为桃园路。图中未把它标为“叁号实验楼”，不直接合并两个来源的名称。' },
  { id:'west-steel-canteen', name:'钢构餐厅', category:'services', kind:'building', center:[964,1147], rect:[744,1060,435,167], height:3.2, description:'明德楼与3号楼南侧的钢构餐厅，图示澡堂在其东端。与学生公寓区的思源餐厅分别展示。' },
  { id:'west-bathhouse', name:'澡堂', category:'services', kind:'service', center:[1151,1147], relatedPlaceId:'west-steel-canteen', description:'平面图标在钢构餐厅东端、桃园路西侧的澡堂。尚未确认是否有独立楼体，使用关联位置标记。' },
  { id:'west-dorm-12', name:'12号楼', category:'housing', kind:'building', center:[1355,434], rect:[1300,410,109,46], description:'学生公寓区西北侧的12号楼，北侧为华信路、东侧为13号楼。' },
  { id:'west-dorm-13', name:'13号楼', category:'housing', kind:'building', center:[1527,434], rect:[1477,411,100,45], description:'学生公寓区北侧的13号楼，位于12号楼东侧、华信路南侧。' },
  { id:'west-dorm-11', name:'11号楼', category:'housing', kind:'building', center:[1358,539], rect:[1300,515,114,47], description:'12号楼南侧、思源餐厅北侧的11号楼。' },
  { id:'west-dorm-20', name:'20号楼', category:'housing', kind:'building', center:[1527,541], rect:[1475,519,102,44], description:'学生公寓区中部的20号楼，位于11号楼东侧、21号楼西侧。' },
  { id:'west-dorm-21', name:'21号楼', category:'housing', kind:'building', center:[1645,543], rect:[1600,519,91,47], description:'20号楼东侧、24号楼北侧的21号楼。' },
  { id:'west-siyuan-canteen', name:'思源餐厅', category:'services', kind:'building', center:[1353,664], rect:[1266,616,177,93], height:3.2, description:'桃园路东侧学生公寓区内的思源餐厅，北侧为11号楼，东侧为4号公寓。' },
  { id:'west-dorm-4', name:'4号公寓', category:'housing', kind:'building', center:[1528,675], rect:[1475,653,104,44], description:'学生公寓区的4号公寓，位于思源餐厅东侧、22号楼西侧，与华信路北侧的4号楼分别显示。' },
  { id:'west-dorm-22', name:'22号楼', category:'housing', kind:'building', center:[1642,676], rect:[1604,652,76,47], description:'4号公寓东侧、24号楼西侧的22号楼。' },
  { id:'west-dorm-24', name:'24号楼', category:'housing', kind:'building', center:[1722,676], rect:[1690,570,61,197], description:'学生公寓区东侧竖向楼宇，西侧为21号楼与22号楼方向。' },
  { id:'west-food-street', name:'小吃街', category:'services', kind:'plaza', center:[1361,805], rect:[1266,781,177,48], height:0.32, description:'成才大道东段南侧的小吃街，位于19号楼西侧。商铺及营业时间以现场信息为准。' },
  { id:'west-dorm-19', name:'19号楼', category:'housing', kind:'building', center:[1525,805], rect:[1476,781,101,48], description:'小吃街东侧、23号楼西侧的19号楼。' },
  { id:'west-dorm-23', name:'23号楼', category:'housing', kind:'building', center:[1651,797], rect:[1602,773,148,49], description:'学生公寓区东南侧的23号楼，位于19号楼东侧、24号楼南侧。' },
  { id:'west-road-huaxin', name:'华信路', category:'landscape', kind:'service', center:[698,337], description:'平面图北部的东西向道路，连接北侧楼宇、运动场与主体教学区。' },
  { id:'west-road-wenming', name:'文明大道', category:'landscape', kind:'service', center:[657,407], description:'华信路南侧的校内道路，位于诚毅楼与图书馆北侧。' },
  { id:'west-road-chengcai', name:'成才大道', category:'landscape', kind:'service', center:[795,737], description:'平面图中部东西向道路，分隔北侧教学区域与南侧工程、实训、生活区域。' },
  { id:'west-road-taoyuan', name:'桃园路', category:'landscape', kind:'service', center:[1225,590], description:'平面图中的南北向道路，西侧为教学与实训区，东侧为学生公寓区。' },
]

export const westPlanFeatures: MapFeature[] = westPlanDefinitions.filter(place=>place.rect).map(place=>{
  const [x,y,w,h]=place.rect!
  return { id:`${place.id}-plan`, placeId:place.id,
    kind:place.kind==='sports'?'track':place.kind==='plaza'?'plaza':'building',
    outline:[[x,y],[x+w,y],[x+w,y+h],[x,y+h]] as MapPoint[] }
})
// A smaller pitch inside the diagram's north sports block; the rectangle is the
// diagram envelope, not a claim about the real running track's length or shape.
westPlanFeatures.push({id:'west-plan-field',placeId:'west-sports-ground',kind:'field',outline:[[860,51],[1070,51],[1070,110],[860,110]]})
export const westPlanRoads: MapRoad[] = [
  {id:'west-plan-huaxin',name:'华信路',width:25,points:[[161,333],[1760,343]]},
  {id:'west-plan-wenming',name:'文明大道',width:17,points:[[381,405],[949,410]]},
  {id:'west-plan-chengcai',name:'成才大道',width:23,points:[[202,732],[1580,744]]},
  {id:'west-plan-taoyuan',name:'桃园路',width:26,points:[[1233,24],[1221,1224]]},
  {id:'west-plan-west',width:17,points:[[379,120],[370,734],[201,734],[208,1292]]},
  {id:'west-plan-southwest',width:17,points:[[434,739],[435,1292]]},
  {id:'west-plan-middle',width:17,points:[[655,346],[648,730]]},
  {id:'west-plan-southmiddle',width:17,points:[[728,749],[725,1227]]},
  {id:'west-plan-axis',width:17,points:[[963,135],[956,1055]]},
]
export const westPlanCoverage: MapPoint[] = [[160,19],[1260,19],[1260,389],[1765,390],[1765,851],[1259,850],[1259,1310],[180,1310],[180,744],[155,744]]
export const westPlanBounds={minX:-135,maxX:140,minZ:-108,maxZ:112}
export function westPlanVerticalRange(feature: MapFeature) {
  const place=westPlanDefinitions.find(p=>p.id===feature.placeId)
  if(feature.kind==='point')return {base:feature.markerHeight??5.2,height:0.16}
  return {base:feature.kind==='field'?0.34:0.24,height:feature.kind==='building'?(place?.height??4.8):0.08}
}
