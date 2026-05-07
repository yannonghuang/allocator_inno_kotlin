const createNextIntlPlugin = require('next-intl/plugin');
const withNextIntl = createNextIntlPlugin('./i18n/request.ts');

/** @type {import('next').NextConfig} */
const apiBackend = process.env.API_BACKEND_URL || 'http://localhost:8000';
const nextConfig = {
  reactStrictMode: true,
  output: 'standalone',
  basePath: '/allocator',
  trailingSlash: true,
  // The planning-agent loop can take 30-90s (LLM tool round-trips). Default
  // ~30s proxy timeout was causing socket hang-ups that made the chat fall
  // back to a misleading "我不太理解" copilot reply.
  experimental: {
    proxyTimeout: 120_000,
  },
  async rewrites() {
    return [
      { source: '/api/:path*', destination: `${apiBackend}/:path*` },
    ];
  },
};

module.exports = withNextIntl(nextConfig);
