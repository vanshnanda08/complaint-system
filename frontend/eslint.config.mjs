import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,

  /**
   * The Leaflet boundary, enforced rather than documented.
   *
   * Blueprint §4 makes MapCanvas the only module that may import Leaflet.
   * A comment saying so is a comment somebody will not read at the moment
   * they most need to -- when they are three files deep adding a marker and
   * reach for `import L from "leaflet"`. The failure it prevents is also one
   * of the least obvious in Next.js: Leaflet touches `window` at module
   * scope, so the import breaks the SERVER render of whatever page
   * transitively reached it, and the error names a route that has nothing
   * visibly to do with maps.
   *
   * It also protects the bundle budget. Blueprint §8 allows the report
   * composer 120 KB gzipped and Leaflet alone exceeds that; the rule is what
   * keeps it reachable only through the dynamic, ssr:false import.
   */
  {
    files: ["src/**/*.{ts,tsx}"],
    ignores: ["src/components/map/MapCanvasInner.tsx"],
    rules: {
      "no-restricted-imports": [
        "error",
        {
          patterns: [
            {
              group: ["leaflet", "leaflet/*", "react-leaflet", "react-leaflet/*"],
              message:
                "Only src/components/map/MapCanvasInner.tsx may import Leaflet. Leaflet touches `window` at module scope and will break the server render from anywhere else, and it blows the report composer's 120 KB budget. Use <MapCanvas> from @/components/MapCanvas instead.",
            },
          ],
        },
      ],
    },
  },

  globalIgnores([".next/**", "out/**", "build/**", "next-env.d.ts"]),
]);

export default eslintConfig;
