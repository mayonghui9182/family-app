import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { useDecomposeStore } from './stores/decompose'
// Element Plus 全量样式：ElMessageBox/ElMessage/ElNotification 等服务型组件
// 由 JS 调用，unplugin-vue-components 不会自动导入其样式，需显式引入
import 'element-plus/dist/index.css'
import './assets/styles.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)

// Change 7 D4：启动时读取 AI 拆解开关（/api/agent/v1/config），
// 关闭时给 body 加 ai-disabled class，由全局 CSS 隐藏所有 AI 入口按钮
useDecomposeStore().init()

app.mount('#app')