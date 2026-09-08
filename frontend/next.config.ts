import type { NextConfig } from "next";
import path from "node:path";

const nextConfig: NextConfig = {
  // Pin the workspace root. Without it Turbopack walks up past the repository
  // and infers a root from a stray package-lock.json in the home directory,
  // which changes how modules resolve depending on whose machine it is.
  turbopack: { root: path.join(__dirname) },
};

export default nextConfig;
