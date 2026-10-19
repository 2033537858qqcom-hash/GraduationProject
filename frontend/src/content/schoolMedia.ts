import overview from '../../../assets/campus/official/admissions-banner02.jpg'
import learning from '../../../assets/campus/official/admissions-banner01.jpg'
import spring from '../../../assets/campus/official/XMT_9742.jpg'
import architecture from '../../../assets/campus/official/XMT_9744.jpg'
import lakeside from '../../../assets/campus/official/campus-lakeside.webp'
import practice from '../../../assets/campus/official/engineering-practice.webp'
import seminar from '../../../assets/campus/official/discipline-seminar.webp'
import freshmen from '../../../assets/campus/official/freshmen-ceremony.webp'
import international from '../../../assets/campus/official/international-agreement.webp'
import performance from '../../../assets/campus/official/welcome-performance.webp'

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
  lakeside: { url: lakeside, label: '湖畔校园', alt: '学校官网发布的校园湖面、草坪与建筑风光', width: 800, height: 500, position: '50% 50%', sourceUrl: 'https://www.zzuit.edu.cn/gyxy/xyfg.htm' },
  practice: { url: practice, label: '工程实践', alt: '学校校园人文图集中的工程设备操作与讲解场景', width: 800, height: 533, position: '55% 45%', sourceUrl: 'https://www.zzuit.edu.cn/gyxy/xyrw.htm' },
  seminar: { url: seminar, label: '学科建设交流', alt: '学校2026年重点培育学科阶段性检查工作会现场', width: 1024, height: 722, position: '50% 60%', sourceUrl: 'https://www.zzuit.edu.cn/info/1016/8705.htm' },
  freshmen: { url: freshmen, label: '新生开学典礼', alt: '学校2026级新生开学典礼暨军训总结表彰大会上的国旗护卫队', width: 1024, height: 575, position: '56% 50%', sourceUrl: 'https://www.zzuit.edu.cn/info/1016/8686.htm' },
  international: { url: international, label: '国际合作签约', alt: '学校与俄罗斯南乌拉尔国立人文师范大学签署合作备忘录现场', width: 1024, height: 722, position: '50% 48%', sourceUrl: 'https://www.zzuit.edu.cn/info/1016/8716.htm' },
  performance: { url: performance, label: '迎新文艺演出', alt: '学校2026年迎新文艺晚会上的舞蹈演出', width: 1024, height: 683, position: '58% 44%', sourceUrl: 'https://www.zzuit.edu.cn/info/1016/8687.htm' },
} satisfies Record<string, SchoolPhoto>
