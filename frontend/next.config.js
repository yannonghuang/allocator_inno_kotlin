const createNextIntlPlugin = require('next-intl/plugin');
const withNextIntl = createNextIntlPlugin('./i18n/request.ts');

/** @type {import('next').NextConfig} */
const apiBackend = process.env.API_BACKEND_URL || 'http://localhost:8000';
const nextConfig = {
  reactStrictMode: true,
  output: 'standalone',
  basePath: '/allocator',
  async rewrites() {
    return [
      { source: '/api/:path*', destination: `${apiBackend}/:path*` },
    ];
  },
};

module.exports = withNextIntl(nextConfig);
