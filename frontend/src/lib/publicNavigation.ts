import { ref } from 'vue'
export const chapters = [
  { id: 'home', name: '首页' }, { id: 'about', name: '学校概况' },
  { id: 'departments', name: '院系专业' }, { id: 'education', name: '教育教学' },
  { id: 'research', name: '科学研究' }, { id: 'admissions', name: '招生就业' },
  { id: 'exchange', name: '合作交流' }, { id: 'life', name: '校园生活' },
  { id: 'disclosure', name: '信息公开' },
]
export const activeChapter = ref('home')
export const chapterReadingProgress = ref(0)
export const requestedChapter = ref({ id: 'home', sequence: 0 })
