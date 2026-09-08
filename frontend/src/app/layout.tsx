import type { Metadata } from "next";
import { IBM_Plex_Sans, IBM_Plex_Mono } from "next/font/google";
import "./globals.css";
import { Providers } from "./providers";
import { THEME_INIT_SCRIPT } from "@/lib/theme";

/**
 * Fonts for TOKEN SET A ("Cool paper"), the active set in globals.css.
 *
 * To swap palettes: uncomment set B or C in globals.css and change the two
 * imports here to that set's named families, keeping the same CSS variable
 * names (--font-plex-sans / --font-plex-mono) so nothing else has to move.
 *
 * The monospace is scoped tightly by convention, not by loading: it is applied
 * only to ticket references and coordinates (blueprint 1.4), never to labels or
 * metadata.
 */
const plexSans = IBM_Plex_Sans({
  subsets: ["latin"],
  weight: ["400", "500", "600"],
  variable: "--font-plex-sans",
  display: "swap",
});

const plexMono = IBM_Plex_Mono({
  subsets: ["latin"],
  weight: ["500"],
  variable: "--font-plex-mono",
  display: "swap",
});

export const metadata: Metadata = {
  title: "CivicTrack",
  description:
    "Report a civic problem in Ludhiana and follow what the municipality does about it.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html
      lang="en"
      className={`${plexSans.variable} ${plexMono.variable}`}
      suppressHydrationWarning
    >
      <head>
        {/*
          Applies the saved theme to <html> BEFORE first paint.

          This has to be a blocking inline script and it has to be here. The
          alternative -- setting the class in an effect -- runs after paint, so
          a dark-mode user sees the light palette render and then flip, on
          every navigation. The script is a few dozen bytes and runs once.

          `suppressHydrationWarning` on <html> because this script mutates
          className before React hydrates, which React would otherwise report
          as a server/client mismatch. It is a deliberate mutation, not a bug.
        */}
        <script
          dangerouslySetInnerHTML={{ __html: THEME_INIT_SCRIPT }}
        />
      </head>
      <body>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
