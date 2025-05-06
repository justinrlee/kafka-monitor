import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
    plugins: [react()],
    server: {
        port: 3000,
        host: '0.0.0.0',
        allowedHosts: [
            'ec2-3-0-94-206.ap-southeast-1.compute.amazonaws.com',
            'localhost',
            '127.0.0.1'
        ],
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