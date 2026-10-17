export type MapPoint = readonly [number, number]
export type SurfaceKind = 'building' | 'water' | 'plaza' | 'track' | 'field' | 'court' | 'point'
export interface MapFeature {
  id: string
  kind: SurfaceKind
  outline: MapPoint[]
  holes?: MapPoint[][]
  placeId?: string
  evidenceView?: DetailView
  markerHeight?: number
}

// Manual tracing of visible Amap views, not a cadastral survey. Points are pixels
// in the saved north-up satellite image, not geographical coordinates. Roof
// outlines approximate footprints; guide-only markers are approximate correspondences
// in that image frame. The viewport URL is not a georeferencing transform.
export const campusEvidence = {
  date: '2026-10-17', version: 'east-layout-reference-v4',
  mapUrl: 'https://www.amap.com/ssr/search?query_type=TQUERY&query=%E9%83%91%E5%B7%9E%E5%B7%A5%E4%B8%9A%E5%BA%94%E7%94%A8%E6%8A%80%E6%9C%AF%E5%AD%A6%E9%99%A2%E4%B8%9C%E6%A0%A1%E5%8C%BA&city=410100&geoobj=113.757616%7C34.408394%7C113.763734%7C34.418557&zoom=16.05',
  tencentMapUrl: 'https://map.qq.com/?type=point&pointx=113.7599&pointy=34.4132&title=%E9%83%91%E5%B7%9E%E5%B7%A5%E4%B8%9A%E5%BA%94%E7%94%A8%E6%8A%80%E6%9C%AF%E5%AD%A6%E9%99%A2%E4%B8%9C%E6%A0%A1%E5%8C%BA',
  layoutReferenceUrl: '/campus/east-campus-layout-reference.jpg',
  layoutReferenceFile: 'frontend/public/campus/east-campus-layout-reference.jpg',
  layoutReferenceDate: 'unknown',
  panoramaUrl: 'https://www.720yun.com/t/a4vkne7m7qw?scene_id=29440437',
  screenshot: 'artifacts/campus-explore/east-campus-amap-satellite-2026-10-04.jpg',
  extentScreenshot: 'artifacts/campus-explore/east-campus-amap-extent-2026-10-04.jpg',
  southScreenshot: 'artifacts/campus-explore/east-campus-amap-south-2026-10-04.jpg',
  horizontalAccuracy: 'manual-image-trace', heightAccuracy: 'display-only',
  coverage: '东校区主要建筑；分布图核对名称，留存影像提供轮廓，未作实地测绘',
  northAxis: '-z', modelUnitsPerPixel: 0.35,
} as const
// The enlarged views are registered to the overview using four manually read
// POI anchors. Residuals describe image alignment only, not survey accuracy.
export const detailRegistration = {
  scale: 3.1687270107314855, offset: [-789.3455500360244, -296.3019510067878] as MapPoint,
  rmsDetailPixels: 2.5859797828993054,
  anchors: [
    { name: '图书馆', overview: [322, 213], detail: [231, 375] },
    { name: '书华楼', overview: [363, 110], detail: [363, 54] },
    { name: '文华楼', overview: [378, 281], detail: [407, 596] },
    { name: '艺术楼', overview: [255, 144], detail: [18, 160] },
  ],
} as const
export const detailViews = {
  central: { translation: [0, 0], standard: 'artifacts/campus-explore/east-campus-amap-central-standard-v2.jpg', satellite: 'artifacts/campus-explore/east-campus-amap-central-detail-v2.jpg' },
  north: { translation: [40, 243], standard: 'artifacts/campus-explore/east-campus-amap-north-standard-v2.jpg', satellite: 'artifacts/campus-explore/east-campus-amap-north-detail-v2.jpg' },
  south: { translation: [208, -193], standard: 'artifacts/campus-explore/east-campus-amap-central-south-standard-v2.jpg' },
  wenhua: { translation: [40, -193], standard: 'artifacts/campus-explore/east-campus-amap-wenhua-standard-v2.jpg', satellite: 'artifacts/campus-explore/east-campus-amap-wenhua-detail-v2.jpg' },
} as const
export type DetailView = keyof typeof detailViews
export function detailToMap(view: DetailView, [x, y]: MapPoint): MapPoint {
  const [dx, dy] = detailViews[view].translation
  const [ox, oy] = detailRegistration.offset
  return [(x - dx - ox) / detailRegistration.scale, (y - dy - oy) / detailRegistration.scale]
}
const detailOutline = (view: DetailView, points: MapPoint[]) => points.map(point => detailToMap(view, point))
export function mapToModel([px, py]: MapPoint): [number, number] {
  return [(px - 310) * campusEvidence.modelUnitsPerPixel, (py - 330) * campusEvidence.modelUnitsPerPixel]
}
// Presentation crop of the northern main-campus extent, not ownership or a legal boundary.
export const campusCoverage: MapPoint[] = [
  [113, 76], [486, 65], [495, 196], [508, 215], [603, 214], [606, 585],
  [289, 587], [273, 518], [249, 471], [207, 430], [121, 365], [53, 320],
  [-29, 280], [-47, 247], [-39, 216], [-7, 163], [49, 114],
]
export const mapFeatures: MapFeature[] = [
  { id: 'library-roof', kind: 'building', placeId: 'east-library', evidenceView: 'central', outline: detailOutline('central', [[179, 503], [159, 472], [143, 440], [149, 405], [166, 377], [189, 356], [220, 347], [249, 352], [276, 366], [293, 391], [300, 424], [293, 456], [264, 495], [249, 476], [264, 450], [268, 424], [261, 398], [241, 385], [219, 385], [198, 398], [187, 420], [191, 443], [209, 474]]) },
  { id: 'library-south-square', kind: 'plaza', evidenceView: 'central', outline: detailOutline('central', [[163, 503], [190, 519], [225, 526], [268, 514], [282, 497], [266, 477], [251, 493], [224, 505], [199, 495], [186, 475]]) },
  { id: 'shuhua-roof', kind: 'building', placeId: 'east-shuhua', evidenceView: 'north', outline: detailOutline('north', [[324, 228], [414, 222], [478, 239], [478, 359], [323, 359], [326, 310], [342, 310], [342, 272], [324, 272]]), holes: [detailOutline('north', [[385, 279], [434, 279], [434, 314], [383, 314]])] },
  { id: 'zhiyuan-roof', kind: 'building', placeId: 'east-zhiyuan', evidenceView: 'north', outline: detailOutline('north', [[515, 234], [625, 234], [691, 243], [691, 366], [539, 366], [539, 330], [558, 330], [558, 289], [515, 289]]), holes: [detailOutline('north', [[593, 286], [649, 283], [649, 318], [594, 319]])] },
  { id: 'h8-annex-roof', kind: 'building', placeId: 'east-h8', evidenceView: 'north', outline: detailOutline('north', [[111, 237], [205, 239], [230, 264], [230, 286], [111, 285]]) },
  // Raised symbol, not a measured clinic building or floor height.
  { id: 'clinic-map-point', kind: 'point', placeId: 'east-clinic', evidenceView: 'central', markerHeight: 3.6, outline: detailOutline('central', [[145, 445]]) },
  { id: 'art-north', kind: 'building', placeId: 'east-art', outline: [[228, 125], [242, 116], [292, 141], [283, 157]] },
  { id: 'art-west', kind: 'building', placeId: 'east-art', outline: [[228, 128], [241, 137], [253, 162], [242, 169], [229, 146]] },
  { id: 'art-east', kind: 'building', placeId: 'east-art', outline: [[277, 152], [291, 146], [284, 170], [269, 181], [257, 172]] },
  { id: 'swimming-roof', kind: 'building', placeId: 'east-swimming', outline: [[137, 129], [142, 116], [157, 109], [178, 109], [194, 119], [200, 133], [194, 145], [179, 151], [157, 150], [143, 142]] },
  { id: 'xinke-north', kind: 'building', placeId: 'east-xinke', outline: [[209, 269], [259, 244], [269, 257], [220, 281]] },
  { id: 'xinke-west', kind: 'building', placeId: 'east-xinke', outline: [[211, 278], [223, 273], [250, 311], [239, 320]] },
  { id: 'xinke-east', kind: 'building', placeId: 'east-xinke', evidenceView: 'south', outline: detailOutline('south', [[224, 389], [271, 364], [292, 395], [253, 418], [263, 435], [235, 450], [216, 421]]) },
  { id: 'jiangong-roof', kind: 'building', placeId: 'east-jiangong', evidenceView: 'south', outline: detailOutline('south', [[232, 449], [263, 431], [266, 439], [298, 419], [351, 453], [277, 555], [260, 547], [244, 534], [235, 515], [217, 513], [198, 494]]), holes: [detailOutline('south', [[248, 466], [278, 447], [308, 466], [267, 515], [245, 497]])] },
  { id: 'wenhua-roof', kind: 'building', placeId: 'east-wenhua', evidenceView: 'wenhua', outline: detailOutline('wenhua', [[489, 279], [621, 339], [615, 366], [554, 479], [493, 524], [422, 567], [347, 440], [385, 414], [412, 379], [446, 343]]), holes: [detailOutline('wenhua', [[480, 339], [557, 376], [535, 413], [461, 372]]), detailOutline('wenhua', [[448, 388], [490, 422], [455, 473], [414, 421]])] },
  { id: 'jinxiu-north', kind: 'building', placeId: 'east-jinxiu', outline: [[465, 286], [509, 285], [520, 309], [520, 327], [464, 327]], holes: [[[477, 297], [500, 297], [508, 311], [508, 316], [477, 316]]] },
  { id: 'jinxiu-south', kind: 'building', placeId: 'east-fenghua', outline: [[462, 337], [518, 337], [519, 375], [461, 376]], holes: [[[475, 347], [506, 347], [506, 365], [475, 365]]] },
  { id: 'construction-roof', kind: 'building', placeId: 'east-construction', outline: [[284, 482], [369, 482], [369, 544], [285, 544]], holes: [[[300, 497], [354, 497], [354, 530], [300, 530]]] },
  { id: 'medical-roof', kind: 'building', placeId: 'east-medical', outline: [[416, 482], [517, 482], [517, 529], [502, 544], [416, 544]], holes: [[[432, 497], [502, 497], [502, 523], [491, 530], [432, 530]]] },
  { id: 'yingcai-square', kind: 'plaza', placeId: 'east-square', outline: [[176, 190], [197, 184], [217, 192], [230, 211], [225, 230], [209, 242], [188, 242], [170, 231], [164, 211]] },
  { id: 'west-track', kind: 'track', placeId: 'east-sports', outline: [[39, 190], [64, 175], [99, 175], [124, 190], [142, 214], [143, 240], [130, 270], [106, 293], [78, 309], [49, 300], [25, 278], [15, 250], [20, 222]] },
  { id: 'west-field', kind: 'field', placeId: 'east-sports', outline: [[67, 196], [112, 222], [82, 275], [38, 249]] },
  { id: 'west-courts', kind: 'court', placeId: 'east-sports', outline: [[113, 298], [172, 320], [153, 375], [94, 349]] },
  { id: 'east-gate-point', kind: 'point', placeId: 'east-gate', outline: [[600, 220]] },
  { id: 'north-water', kind: 'water', placeId: 'east-water', outline: [[493, 70], [509, 82], [515, 113], [529, 145], [534, 180], [527, 204], [511, 211], [501, 194]] },
  { id: 'east-water', kind: 'water', placeId: 'east-water', outline: [[543, 228], [570, 225], [590, 236], [589, 276], [588, 313], [576, 347], [584, 388], [589, 432], [585, 485], [574, 531], [566, 555], [549, 568], [527, 568], [529, 548], [536, 523], [541, 484], [543, 442], [540, 397], [544, 363], [539, 328], [536, 289], [540, 265]] },
  { id: 'central-water', kind: 'water', outline: [[407, 324], [431, 329], [438, 350], [438, 377], [425, 384], [387, 383], [372, 367], [378, 345]] },
  // Roofs retain the observed outlines. Assignments below are supported by the supplied
  // distribution plan; remaining unknown roofs stay anonymous.
  { id: 'roof-nw-01', kind: 'building', outline: [[70, 120], [79, 106], [139, 142], [132, 157]] },
  { id: 'roof-nw-02', kind: 'building', outline: [[56, 143], [64, 129], [126, 164], [118, 178]] },
  { id: 'roof-nw-03', kind: 'building', outline: [[99, 167], [112, 160], [156, 186], [149, 196]] },
  { id: 'roof-nw-04', kind: 'building', outline: [[112, 185], [125, 178], [172, 204], [165, 218]] },
  { id: 'roof-west-01', kind: 'building', outline: [[175, 303], [194, 303], [194, 323], [175, 323]] },
  { id: 'roof-west-02', kind: 'building', outline: [[183, 337], [202, 337], [202, 355], [183, 355]] },
  { id: 'roof-southwest-01', kind: 'building', placeId: 'east-canteen-wenhua', outline: [[267, 383], [308, 383], [308, 422], [267, 422]], holes: [[[278, 394], [297, 394], [297, 411], [278, 411]]] },
  { id: 'roof-southwest-02', kind: 'building', placeId: 'east-construction-training', outline: [[264, 430], [305, 430], [305, 449], [264, 449]] },
  { id: 'roof-central-01', kind: 'building', placeId: 'east-admin', outline: [[365, 397], [428, 397], [428, 419], [365, 419]] },
  { id: 'roof-central-02', kind: 'building', outline: [[438, 417], [460, 417], [460, 449], [438, 449]] },
  { id: 'roof-east-01', kind: 'building', placeId: 'east-auditorium', outline: [[471, 410], [524, 410], [524, 446], [471, 446]], holes: [[[483, 420], [512, 420], [512, 435], [483, 435]]] },
  // 锦绣餐厅与静逸楼为独立建筑，中间由校内道路自然分隔，无物理连通
  { id: 'jinxiu-dining-roof', kind: 'building', placeId: 'east-jinxiu-dining', evidenceView: 'central', outline: detailOutline('central', [[326,181],[437,169],[445,169],[445,384],[368,384],[368,303],[311,303],[310,244],[327,244]]), holes: [detailOutline('central', [[344,218],[403,215],[403,268],[345,269]])] },
  { id: 'jingyi-roof', kind: 'building', placeId: 'east-jingyi', evidenceView: 'central', outline: detailOutline('central', [[508,208],[591,209],[649,223],[649,338],[508,338]]), holes: [detailOutline('central', [[562,254],[609,254],[609,289],[562,289]])] },
  // Near-circular northwest roof visible in the saved overview; its identity is
  // supplied by the school distribution plan, not guessed from its colour.
  { id: 'gym-northwest-roof', kind: 'building', placeId: 'east-gym', outline: [[102,91],[109,80],[122,77],[135,82],[142,93],[140,106],[130,115],[116,117],[105,108],[100,99]] },
  // Guide-only positions remain symbols. No footprint, floor or entrance is invented.
  { id: 'h1-guide-point', kind: 'point', placeId: 'east-h1', outline: [[180,285]] },
  { id: 'h2-guide-point', kind: 'point', placeId: 'east-h2', outline: [[208,388]] },
  { id: 'affairs-guide-point', kind: 'point', placeId: 'east-affairs', outline: [[476,152]] },
  { id: 'north-gate-point', kind: 'point', placeId: 'north-gate', outline: [[318,64]] },
  { id: 'south-gate-point', kind: 'point', placeId: 'south-gate', outline: [[394,587]] },
]
export interface MapRoad { id: string; points: MapPoint[]; width: number; name?: string; evidenceView?: DetailView }
// Display widths in source pixels; internal streets remain unnamed.
export const mapRoads: MapRoad[] = [
  { id: 'north-road', name: '少典路', width: 11, points: [[-20, 53], [625, 53]] },
  { id: 'east-road', name: '万福路', width: 11, points: [[618, 53], [618, 594]] },
  { id: 'west-road', name: '中华路', width: 9, points: [[114, 65], [45, 142], [-18, 224], [-17, 274], [48, 329], [134, 383], [219, 448], [266, 507], [281, 590]] },
  { id: 'south-road', name: '学院路', width: 12, points: [[264, 596], [619, 596]] },
  { id: 'jinxiu-jingyi-road', name: '静逸西路', width: 5, points: [[399.95, 120], [399.95, 225]] },
  { id: 'north-axis', name: '外环北路中轴', width: 6, points: [[318, 71], [318, 183]] },
  { id: 'library-ring', name: '中环路 (图书馆环路)', width: 6, points: [[320, 183], [346, 191], [362, 212], [362, 238], [346, 258], [321, 270], [296, 258], [281, 238], [281, 214], [295, 194], [320, 183]] },
  { id: 'east-entrance-axis', width: 7, points: [[364, 224], [466, 223], [601, 218]] },
  { id: 'south-axis', width: 6, points: [[320, 270], [320, 383], [317, 454]] },
  { id: 'east-internal', name: '外环东路', width: 6, points: [[465, 80], [464, 222], [459, 301], [452, 382], [457, 455], [532, 455], [532, 554]] },
  { id: 'east-bank', width: 5, points: [[532, 223], [530, 292], [533, 382], [531, 477], [520, 548], [506, 558]] },
  { id: 'central-east-curve', width: 5, points: [[353, 256], [379, 245], [410, 257], [440, 295], [449, 323], [444, 345], [427, 369], [398, 388], [354, 391]] },
  { id: 'central-west-curve', width: 5, points: [[287, 251], [262, 235], [237, 232], [222, 247], [215, 277], [226, 311], [255, 337], [289, 343], [318, 333]] },
  { id: 'square-link', width: 5, points: [[227, 221], [280, 224]] },
  { id: 'square-north-link', name: '艺馨路', width: 5, points: [[216, 189], [234, 177], [277, 193]] },
  { id: 'square-west-link', width: 5, points: [[169, 206], [129, 184], [116, 170], [66, 142]] },
  { id: 'west-sports-link', name: '英才大道', width: 5, points: [[132, 281], [163, 265], [169, 236], [190, 249], [213, 267]] },
  { id: 'south-cross-01', width: 6, points: [[250, 461], [532, 460]] },
  { id: 'south-cross-02', width: 5, points: [[277, 558], [506, 558]] },
  { id: 'south-approach', name: '人文大道', width: 12, points: [[394, 463], [394, 596]] },
  { id: 'southwest-road', width: 5, points: [[275, 474], [276, 548], [287, 558]] },
  { id: 'medical-front', width: 5, points: [[406, 477], [406, 548], [412, 558]] },
  { id: 'north-building-link', width: 3.5, evidenceView: 'north', points: detailOutline('north', [[104, 195], [270, 195], [501, 199], [685, 212], [727, 250]]) },
  { id: 'north-central-lane', name: '外环北路北段', width: 3.5, evidenceView: 'north', points: detailOutline('north', [[501, 199], [501, 409], [711, 423]]) },
  { id: 'h8-front-link', width: 3, evidenceView: 'north', points: detailOutline('north', [[265, 301], [169, 302], [166, 359]]) },
  { id: 'jingyi-west-link', name: '自强路段', width: 3.5, evidenceView: 'central', points: detailOutline('central', [[227, 222], [299, 216], [300, 295], [359, 315], [359, 384]]) },
  { id: 'wenhua-north-approach', width: 3.5, evidenceView: 'wenhua', points: detailOutline('wenhua', [[578, 230], [526, 326]]) },
  { id: 'wenhua-cross-link', width: 3.5, evidenceView: 'wenhua', points: detailOutline('wenhua', [[278, 405], [381, 399]]) },
  { id: 'jiangong-front-link', width: 3.5, evidenceView: 'south', points: detailOutline('south', [[439, 523], [361, 540], [300, 559]]) },
]
