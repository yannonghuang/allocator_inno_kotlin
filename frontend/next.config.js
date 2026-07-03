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
  // Deep soundness checks on large cases (e.g. case 173) can run 165-195s —
  // the backend completes and persists successfully, but a too-short proxy
  // timeout here makes the UI show a spurious "check_failed: Internal Server
  // Error" while the request is still in flight.
  experimental: {
    proxyTimeout: 300_000,
  },
  async rewrites() {
    return [
      { source: '/api/:path*', destination: `${apiBackend}/:path*` },
    ];
  },
};

module.exports = withNextIntl(nextConfig);
