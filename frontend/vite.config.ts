import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// 기본: 개발 서버(5173)가 /api 요청을 백엔드(8080)로 프록시한다.
// VITE_API_BASE_URL 을 지정하면 프록시 대신 해당 주소로 직접 호출한다(백엔드 CORS 허용 필요).
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '');
  return {
    plugins: [react()],
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        '/api': { target: env.VITE_PROXY_TARGET || 'http://localhost:8080', changeOrigin: true },
      },
    },
  };
});
