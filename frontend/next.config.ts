import type { NextConfig } from "next";

const ragBackendUrl = process.env.RAG_BACKEND_URL ?? "http://localhost:8080";

const nextConfig: NextConfig = {
  async rewrites() {
    return [
      {
        source: "/api/chat/:path*",
        destination: `${ragBackendUrl}/api/chat/:path*`,
      },
    ];
  },
};

export default nextConfig;
