import overview from '../../../assets/campus/official/admissions-banner02.jpg'
import learning from '../../../assets/campus/official/admissions-banner01.jpg'
import spring from '../../../assets/campus/official/XMT_9742.jpg'
import architecture from '../../../assets/campus/official/XMT_9744.jpg'

export interface SchoolPhoto {
  url: string
  label: string
  alt: string
  width: number
  height: number
  position: string
  sourceUrl: string
}

export const schoolMedia = {
  overview: { url: overview, label: '全景校园', alt: '学校招生信息网发布的郑州工业应用技术学院校园全景', width: 1920, height: 810, position: '50% 50%', sourceUrl: 'https://zsb.zzuit.edu.cn/' },
  learning: { url: learning, label: '师生共学', alt: '学校招生信息网发布的师生学习场景', width: 1920, height: 810, position: '55% 45%', sourceUrl: 'https://zsb.zzuit.edu.cn/' },
  spring: { url: spring, label: '春日校园', alt: '学校官网校园风光中的春日花枝与校园建筑', width: 800, height: 533, position: '50% 50%', sourceUrl: 'https://www.zzuit.edu.cn/gyxy/xyfg.htm' },
  architecture: { url: architecture, label: '校园建筑', alt: '学校官网校园风光中的艺术楼与花枝', width: 800, height: 518, position: '50% 50%', sourceUrl: 'https://www.zzuit.edu.cn/gyxy/xyfg.htm' },
} satisfies Record<string, SchoolPhoto>
