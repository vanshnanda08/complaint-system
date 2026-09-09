import { chromium } from 'playwright';
import { pickWalkableIssue } from './pick.mjs';
const API='http://localhost:8080/api/v1', APP='http://localhost:3000';
const login = async (e) => (await (await fetch(`${API}/auth/login`,{method:'POST',
  headers:{'Content-Type':'application/json'},body:JSON.stringify({email:e,password:'demo1234'})})).json());
const sup = await login('head.roads@civictrack.example');
const crew = await login('crew.roads@civictrack.example');
const post = (tok,id,verb,body)=>fetch(`${API}/issues/${id}/${verb}`,{method:'POST',
  headers:{'Content-Type':'application/json',Authorization:`Bearer ${tok.accessToken}`},body:JSON.stringify(body??{})});

const id = await pickWalkableIssue(API);

const b = await chromium.launch({ channel:'chrome' });
const ctx = await b.newContext({viewport:{width:1280,height:900}});
const p = await ctx.newPage();
p.on('pageerror', e => console.log('  PAGEERROR:', String(e).slice(0,200)));
await p.goto(APP+'/login',{waitUntil:'networkidle'});
await p.getByLabel('Email').fill('crew.roads@civictrack.example');
await p.getByLabel('Password').fill('demo1234');
await p.getByRole('button',{name:'Sign in'}).click();
await p.waitForURL('**/me/reports');

async function look(expectStatus) {
  await p.goto(APP+'/staff/issues/'+id,{waitUntil:'domcontentloaded'});
  await p.waitForSelector('h1', { timeout: 20000 });
  await p.waitForTimeout(2200);
  const txt = await p.locator('body').innerText();
  const btns = (await p.getByRole('button').allInnerTexts())
    .filter(t=>/Acknowledge|Start work|Submit for citizen|Resolve/.test(t));
  const waiting = txt.split('\n').find(l=>/waiting|Waiting|assign|somebody else|under way|confirmed/i.test(l)) ?? '';
  console.log(`  ${expectStatus.padEnd(21)} action=${JSON.stringify(btns).padEnd(38)} ${waiting.slice(0,72)}`);
}

const picked = await (await fetch(`${API}/public/issues/${id}`)).json();
console.log(`crew.roads viewing ${picked.publicRef}:`);
await look('NEW');
await post(sup,id,'acknowledge',{note:'Seen'});                     await look('ACKNOWLEDGED');
await post(sup,id,'assign',{assigneeId:crew.userId,note:'To crew'}); await look('ASSIGNED');
await post(crew,id,'start',{note:'On site'});                        await look('IN_PROGRESS');
await post(crew,id,'submit-for-verification',{proofPhotoUrl:'https://example.test/f.jpg',
  note:'Filled and compacted the pothole, resurfaced the patch.'});  await look('PENDING_VERIFICATION');
await b.close();
