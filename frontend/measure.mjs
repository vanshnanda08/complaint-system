import { chromium } from 'playwright';
const b = await chromium.launch({ channel: 'chrome' });

for (const route of ['/', '/styleguide']) {
  const ctx = await b.newContext({ viewport: { width: 390, height: 844 } });
  const p = await ctx.newPage();
  let js = 0, leaflet = false;
  p.on('response', async (r) => {
    const u = r.url();
    if (!u.includes('/_next/static/') || !u.endsWith('.js')) return;
    try {
      const body = await r.body();
      js += body.length;
      if (body.includes('L.Icon') || /leaflet/i.test(body.toString('utf8').slice(0, 200000))) leaflet = true;
    } catch {}
  });
  await p.goto('http://localhost:3000' + route, { waitUntil: 'networkidle' });
  console.log(`${route.padEnd(14)} initial JS (uncompressed, as served): ${(js/1024).toFixed(1)} KB   leaflet downloaded: ${leaflet}`);
  await ctx.close();
}
await b.close();
