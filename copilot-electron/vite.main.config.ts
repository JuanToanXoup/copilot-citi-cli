import { defineConfig } from 'vite'
import path from 'path'

export default defineConfig({
  build: {
    lib: {
      entry: path.resolve(__dirname, 'src/main/index.ts'),
      formats: ['es'],
      fileName: () => 'index.js',
    },
    outDir: 'dist/main',
    emptyOutDir: true,
    rollupOptions: {
      external: ['electron', 'path', 'fs', 'child_process', 'os', 'url', 'events', 'crypto'],
    },
  },
  resolve: {
    alias: {
      '@shared': path.resolve(__dirname, 'src/shared'),
    },
  },
})
