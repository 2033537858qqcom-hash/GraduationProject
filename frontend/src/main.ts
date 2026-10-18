import { createApp } from 'vue'
import App from './App.vue'
import { router } from './router'
import './styles.css'
import './cultural.css'
import './styles/repair-settings.css'
import './styles/repair-workflow.css'
import './styles/portal-refinement.css'

createApp(App).use(router).mount('#app')
