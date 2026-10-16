import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({
      resolvers: [ElementPlusResolver()],
      imports: ['vue']
    }),
    Components({
      resolvers: [ElementPlusResolver()]
    })
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    strictPort: true,
    // 微信内置浏览器 + Cloudflare Tunnel 环境下 HMR WebSocket 不稳定，
    // 断开会触发 Vite 客户端 location.reload() 形成整页刷新死循环。
    // 公网联调阶段关闭 HMR，避免空白刷新；本地开发可手动改回 true。
    hmr: false,
    proxy: {
      '/api': {
        target: process.env.VITE_PROXY_TARGET || 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  test: {
    environment: 'happy-dom',
    globals: true,
    include: ['src/tests/**/*.test.js'],
    reporters: ['default', 'verbose'],
    // happy-dom 不支持 .css 文件扩展名，跳过 CSS 加载，组件测试仅需要 DOM 结构
    css: false,
    server: {
      deps: {
        inline: ['element-plus']
      }
    },
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
      include: ['src/api/**/*.js'],
      lines: 80,
      functions: 75,
      branches: 70,
      statements: 80
    }
  }
})