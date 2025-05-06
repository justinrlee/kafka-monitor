import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
    plugins: [react()],
    server: {
        port: 3000,
        host: '0.0.0.0',
        // This is moderately insecure
        allowedHosts: true,
        // allowedHosts: [
        //     '*',
        //     'localhost',
        //     '127.0.0.1'
        // ],
        proxy: {
            '/topics': {
                target: 'http://localhost:9401',
                changeOrigin: true,
                secure: false
            },
            '/brokers': {
                target: 'http://localhost:9401',
                changeOrigin: true,
                secure: false
            }
        }
    },
});