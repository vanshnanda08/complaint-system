import { chromium } from 'playwright';
const APP='http://localhost:3000', API='http://localhost:8080/api/v1';
const page1 = await (await fetch(`${API}/public/issues?limit=1`)).json();
const iss = page1.items[0];
const q = await (await fetch(`${API}/public/issues?status=PENDING_VERIFICATION&limit=1`)).json();
const pend = q.items[0];
const r = await (await fetch(`${API}/public/issues?status=RESOLVED&limit=1`)).json();
const res = r.items[0];

const routes = [
  '/', '/report', '/map', '/issues', '/issues?sort=priority&status=NEW', '/dashboard',
  '/login', '/register', '/me/reports', '/staff/queue', '/staff/queue?tab=unassigned',
  `/issues/${iss.id}`, `/issues/${iss.id}/cluster`, `/report/success/${iss.publicRef}`,
  `/staff/issues/${iss.id}`, '/does-not-exist', '/issues/00000000-0000-0000-0000-000000000000',
  '/report/success/CT-1999-000001',
];
if (pend) { routes.push(`/issues/${pend.id}`, `/issues/${pend.id}/cluster`); }
if (res)  { routes.push(`/issues/${res.id}`); }

const b = await chromium.launch({ channel:'chrome' });
for (const signedIn of [false, true]) {
  const ctx = await b.newContext({ viewport:{width:1280,height:900} });
  const p = await ctx.newPage();
  if (signedIn) {
    await p.goto(APP+'/login',{waitUntil:'networkidle'});
    await p.getByLabel('Email').fill('head.roads@civictrack.example');
    await p.getByLabel('Password').fill('demo1234');
    await p.getByRole('button',{name:'Sign in'}).click();
    await p.waitForURL('**/me/reports');
  }
  console.log(`\n--- ${signedIn ? 'signed in (SUPERVISOR)' : 'anonymous'} ---`);
  for (const route of routes) {
    const errs = [];
    const onErr = e => errs.push('pageerror: ' + String(e).slice(0,110));
    const onCon = m => { if (m.type()==='error') errs.push('console: ' + m.text().slice(0,110)); };
    p.on('pageerror', onErr); p.on('console', onCon);
    try {
      await p.goto(APP+route, { waitUntil:'domcontentloaded', timeout: 25000 });
      await p.waitForSelector('h1, h2', { timeout: 15000 }).catch(()=>{});
      await p.waitForTimeout(1600);
      const t = await p.locator('body').innerText();
      const crashed = /This page couldn.t load/.test(t);
      const nan = /\bNaN\b|undefined|\[object Object\]/.test(t);
      const flag = crashed ? 'CRASHED' : errs.length ? 'ERRORS ' : nan ? 'NaN/undef' : 'ok     ';
      console.log(`  ${flag}  ${route}`);
      if (crashed || errs.length) errs.slice(0,2).forEach(e=>console.log(`        ${e}`));
      if (nan && !crashed) console.log(`        ${t.split('\n').find(l=>/\bNaN\b|undefined|\[object Object\]/.test(l))?.slice(0,90)}`);
    } catch (e) {
      console.log(`  TIMEOUT  ${route}  ${String(e).slice(0,80)}`);
    }
    p.off('pageerror', onErr); p.off('console', onCon);
  }
  await ctx.close();
}
await b.close();
