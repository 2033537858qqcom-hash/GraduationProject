import type { CampusPlace } from './manifest'
import { westPlanDefinitions, westPlanEvidence, westPlanFeatures, westPlanToModel, westPlanVerticalRange } from './westCampusPlan'
export { westCampusMeta, eastCampusMeta, westCampusMapFrame, westMapPlaces, westMapTour } from './westCampusMapData'

// Independent sandbox: the supplied campus distribution plan determines identity
// and relative position. No unmeasured GCJ-02 coordinates are assigned to it.
export const westCampusPlaces: CampusPlace[] = westPlanDefinitions.map(entry=>{
  const features=westPlanFeatures.filter(feature=>feature.placeId===entry.id)
  const [x,z]=westPlanToModel(entry.center)
  const parent=westPlanDefinitions.find(p=>p.id===entry.relatedPlaceId)
  const pointBase=parent?(parent.height??4.8)+0.44:5.2
  const height=features.length?Math.max(...features.map(f=>{const r=westPlanVerticalRange(f);return r.base+r.height})):pointBase+0.16
  return {id:entry.id,campusId:'WEST',name:entry.name,category:entry.category,kind:entry.kind,
    x,z,width:entry.rect?entry.rect[2]*0.16:2.8,depth:entry.rect?entry.rect[3]*0.16:2.8,height,
    description:entry.description,features:entry.features??['校内平面图标注','楼层与入口待核对'],
    sourceUrl:westPlanEvidence.url,sourceScreenshot:westPlanEvidence.file,
    locationType:features.length?'outline':'point',verification:'reference-located',heightStatus:'display-only',
    relatedPlaceId:entry.relatedPlaceId}
})

// These names occur in other school documents, but this diagram does not name
// them separately. Do not silently alias “实验楼” to “一号实验楼”.
export const westUnlocatedBuildings=[
  {name:'一号实验楼（与图示实验楼的关系待核对）',source:'https://pjb.zzuit.edu.cn/zhengzhougongyeyingyongjishuxueyuanbenkejiaoyujiaoxueshenhepinggugongzuojianbao2025niandi4qi.pdf'},
  {name:'明德南楼（图中未单独标出）',source:'https://center.zzuit.edu.cn/xnfw.htm'},
]
export const westCampusTour=['west-incubator-1','west-building-4','west-sports-ground','west-dorm-7',
  'west-flag-square','west-chengyi','west-library','west-experiment-building','west-information',
  'west-culture-square','west-tech-center','west-auto-training','west-rail-training','west-mingde',
  'west-steel-canteen','west-siyuan-canteen','west-dorm-4','west-dorm-24']
