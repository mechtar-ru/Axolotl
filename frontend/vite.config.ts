import { fileURLToPath, URL } from 'node:url'
import path from 'node:path'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'
import electron from 'vite-plugin-electron'
import renderer from 'vite-plugin-electron-renderer'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

const isElectron = process.env.ELECTRON === 'true'
const API_PORT = process.env.VITE_API_URL?.match(/:(\d+)/)?.[1] || '8082';

export default defineConfig({
  server: {
    allowedHosts: ['slam-setback-swampland.ngrok-free.dev'],
    proxy: {
      '/api': {
        target: `http://localhost:${API_PORT}`,
        changeOrigin: true,
      },
      '/ws': {
        target: `ws://localhost:${API_PORT}`,
        ws: true,
      },
    },
  },
  plugins: [
    vue(),
    vueDevTools(),
    isElectron && electron([
      {
        entry: path.join(__dirname, '../electron/main.ts'),
        onstart(options) {
          options.startup()
        },
        vite: {
          build: {
            outDir: path.join(__dirname, 'dist-electron'),
            rollupOptions: {
              external: ['electron']
            }
          }
        }
      },
      {
        entry: path.join(__dirname, '../electron/preload.ts'),
        onstart(options) {
          options.reload()
        },
        vite: {
          build: {
            outDir: path.join(__dirname, 'dist-electron')
          }
        }
      }
    ]),
    isElectron && renderer()
  ].filter(Boolean),
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    },
  },
  base: './',
  build: {
    outDir: path.join(__dirname, 'dist'),
    emptyOutDir: true
  }
})
