import type { Metadata } from "next";
import { IBM_Plex_Sans, IBM_Plex_Mono, Newsreader } from "next/font/google";
import "./globals.css";
import { Providers } from "./providers";
import { THEME_INIT_SCRIPT } from "@/lib/theme";

/**
 * Three faces, three jobs.
 *
 * NEWSREADER for headings and figures. A reading serif with genuine optical
 * sizing, which is why the dashboard's numerals look considered at 30px rather
 * than merely large. It also does the thing this application needs a display
 * face to do: it makes a page of municipal records look like a record rather
 * than like an admin panel.
 *
 * IBM PLEX SANS for everything a person operates -- labels, rows, buttons. It
 * has real tabular figures, which the queues and the dashboard depend on for
 * column alignment.
 *
 * IBM PLEX MONO for ticket references and coordinates only, never for labels.
 * A reference is read aloud over a phone, compared digit by digit and pasted
 * into WhatsApp; fixed width serves all three.
 *
 * `display: "swap"` on all three: on the connection this project assumes, a
 * blocking font load is a blank page.
 */
const plexSans = IBM_Plex_Sans({
  subsets: ["latin"],
  weight: ["400", "500", "600"],
  variable: "--font-plex-sans",
  display: "swap",
});

const plexMono = IBM_Plex_Mono({
  subsets: ["latin"],
  weight: ["400", "500"],
  variable: "--font-plex-mono",
  display: "swap",
});

const newsreader = Newsreader({
  subsets: ["latin"],
  weight: ["400", "500", "600"],
  style: ["normal", "italic"],
  variable: "--font-newsreader",
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
      className={`${plexSans.variable} ${plexMono.variable} ${newsreader.variable}`}
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
