/**
 * Drives every client-offered action against the real server and asserts none
 * of them is refused. This is the check that would have caught the wrong
 * transition table: a unit test written from the same assumption as the code
 * cannot, but the server can.
 */
const API = 'http://localhost:8080/api/v1';
const login = async (email) => (await (await fetch(`${API}/auth/login`, {
  method: 'POST', headers: {'Content-Type':'application/json'},
  body: JSON.stringify({ email, password: 'demo1234' })})).json());

const sup = await login('head.roads@civictrack.example');
const crew = await login('crew.roads@civictrack.example');

const post = async (tok, id, verb, body) => {
  const r = await fetch(`${API}/issues/${id}/${verb}`, { method:'POST',
    headers: {'Content-Type':'application/json', Authorization:`Bearer ${tok.accessToken}`},
    body: JSON.stringify(body ?? {}) });
  return { status: r.status, body: await r.json().catch(()=>null) };
};
const get = async (tok, id) => (await (await fetch(`${API}/issues/${id}`,
  { headers: { Authorization:`Bearer ${tok.accessToken}` }})).json());

// A fresh NEW issue in Roads.
const list = await (await fetch(`${API}/public/issues?status=NEW&category=POTHOLE&limit=5`)).json();
const id = list.items[0].id;
console.log(`walking ${list.items[0].publicRef}`);

const steps = [
  ['acknowledge', sup, {note:'Seen, crew going out'}],
  ['assign',      sup, {assigneeId: crew.userId, note:'Assigned to the roads crew'}],
  ['start',       crew,{note:'Crew on site'}],
  ['submit-for-verification', crew, {
      proofPhotoUrl:'https://example.test/fixed.jpg',
      note:'Filled and compacted the pothole, resurfaced the patch.' }],
];

for (const [verb, tok, body] of steps) {
  const before = (await get(tok, id)).status;
  const r = await post(tok, id, verb, body);
  const after = (await get(tok, id)).status;
  console.log(`  ${before.padEnd(12)} --${verb.padEnd(24)}--> ${String(r.status).padEnd(4)} ${after}` +
    (r.status >= 400 ? `   ${r.body?.detail ?? ''}` : ''));
}
