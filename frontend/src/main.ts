import { createApp } from 'vue'
import App from './App.vue'
import { router } from './router'
import './styles/tokens.css'
import './styles.css'
import './styles/display-font.css'
import './cultural.css'

createApp(App).use(router).mount('#app')
