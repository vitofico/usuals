import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { markdownDocument, ingredient } from '../src/import.mjs';
import { ImageModel, modelItems } from '../src/image-model.mjs';
import { preparePhoto, PhotoCamera } from '../src/photo.mjs';
const folder = new URL('../../core/parse/src/main/resources/input-language/', import.meta.url);
const packs = readdirSync(folder).filter(f => f.endsWith('.properties')).map(f => Object.fromEntries(readFileSync(new URL(f, folder), 'utf8').split('\n').filter(l => l.includes('=') && !l.startsWith('#')).map(l => [l.slice(0,l.indexOf('=')), l.slice(l.indexOf('=')+1)])));
const challenge = { image: 'data:image/png;base64,cHJvYmU=', colours: ['red','green','blue','yellow','white','black'] };
const image = 'data:image/jpeg;base64,cGhvdG8=';
const response = content => new Response(JSON.stringify({ choices: [{ message: { content: JSON.stringify(content) } }] }));
function fixture(limit = 3) {
  const sent = [], model = new ImageModel({ origins: ['https://model.invalid'], challenge: async () => challenge,
    fetcher: async (url, options) => { sent.push({url, ...options}); return response(sent.length === 1 ? {colours: challenge.colours} : {items:[{name:'milk',amount:2,unit:'LITRE'}]}); } });
  model.connect({endpoint:'https://model.invalid/v1',model:'synthetic',apiKey:'synthetic-key',limit});
  return {model,sent};
}
test('Markdown reads explicit unchecked entries, sections and language packs without rendering', () => {
  const doc = markdownDocument('---\ntitle: private\n---\n# Soup\n## Ingredients\n- [ ] **2 kg tomatoes**\n- [x] oil\n### Dressing\n- [salt](https://invalid)\n## Method\n1. Cook it\n```\n- not food\n```', packs);
  assert.deepEqual(doc, {title:'Soup',entries:['2 kg tomatoes','salt']});
  for (const heading of ['Ingredientes','Ingredienti','Ingrédients','Zutaten']) assert.deepEqual(markdownDocument(`${heading}\n---\n- onions`, packs).entries,['onions']);
  assert.deepEqual(markdownDocument('```\n# Ingredients\n```\n- bread', packs).entries,['bread']);
  assert.throws(() => markdownDocument('- milk\n'.repeat(151), packs));
  assert.throws(() => markdownDocument('x'.repeat(30001), packs));
  assert.equal(ingredient('2 kg tomatoes, chopped',packs).quantity,2);
  assert.equal(ingredient('1/2 kg tomatoes',packs).quantity,.5);
  assert.equal(ingredient('½ kg tomatoes',packs).quantity,.5);
  assert.equal(ingredient('2 cups flour',packs).quantity,null);
  assert.equal(ingredient('fünf Stück Äpfel',packs).quantity,5);
  assert.equal(ingredient('2 cuillères à soupe de farine',packs).name,'farine');
  assert.equal(ingredient('2 cuillères à soupe de farine',packs).quantity,null);
  assert.equal(ingredient('2 dozen eggs',packs).quantity,24);
});
test('photo connection verifies synthetic pixels before a distinct explicit upload, uses no ambient credentials', async () => {
  const {model,sent}=fixture(); assert.equal(sent.length,0);
  await model.check(); assert.equal(sent.length,1); assert.equal(model.status.verified,true);
  const items=await model.read(image); assert.equal(items[0].name,'milk'); assert.equal(sent.length,2);
  assert.equal(sent[0].body.includes('cGhvdG8='),false);
  assert.equal(sent[1].body.includes('cGhvdG8='),true);
  assert.equal(sent[1].redirect,'error'); assert.equal(sent[1].credentials,'omit');
  assert.equal(sent[1].referrerPolicy,'no-referrer'); assert.equal(sent[1].headers.Authorization,'Bearer synthetic-key');
  assert.equal(JSON.stringify(model).includes('synthetic-key'),false);
  await model.read(image); await assert.rejects(model.read(image),e=>e.code==='importModelBudget');
  assert.equal(sent.length,3); model.disconnect(); await assert.rejects(model.read(image),e=>e.code==='importModelMissing');
});
test('failed replacement invalidates the previously verified provider', async () => {
  const {model,sent}=fixture(); await model.check();
  assert.throws(()=>model.connect({endpoint:'https://disallowed.invalid/v1',model:'other'}));
  assert.equal(model.status.verified,false); await assert.rejects(model.read(image),e=>e.code==='importModelMissing'); assert.equal(sent.length,1);
});
test('text-only or oversized replies cannot enable photo upload', async () => {
  let calls=0;
  const model=new ImageModel({origins:['https://model.invalid'],challenge:async()=>challenge,fetcher:async()=>{calls++;return response({ok:1});}});
  model.connect({endpoint:'https://model.invalid/v1',model:'text'});
  await assert.rejects(model.read(image),e=>e.code==='importModelImages'); assert.equal(calls,1);
  model.fetcher=async()=>new Response('x'.repeat(512001));
  await assert.rejects(model.read(image),e=>e.code==='importModelOutput');
  assert.equal(modelItems('{"items":[{"name":"salt","unit":"GRAM"}]}')[0].quantity,null);
});
test('disconnect cancels an in-flight probe and cannot verify a stale connection', async () => {
  let resolve;
  const model=new ImageModel({origins:['https://model.invalid'],challenge:async()=>challenge,fetcher:async(_url,options)=>new Promise((yes,no)=>{resolve=yes;options.signal.addEventListener('abort',()=>no(new Error('aborted')));})});
  model.connect({endpoint:'https://model.invalid/v1',model:'test'}); const check=model.check();
  await new Promise(r=>setImmediate(r)); model.disconnect(); await assert.rejects(check,e=>e.code==='cancelled');
  assert.equal(model.status.configured,false); assert.equal(model.status.verified,false);
});
test('compressed image bombs are rejected before invoking a decoder', async () => {
  const bytes=new Uint8Array(33); bytes.set([137,80,78,71,13,10,26,10]);const view=new DataView(bytes.buffer);
  view.setUint32(16,12000);view.setUint32(20,12000);
  let decoded=false; const original=globalThis.createImageBitmap;globalThis.createImageBitmap=async()=>{decoded=true;};
  try { await assert.rejects(preparePhoto(new Blob([bytes])),e=>e.code==='importTooLarge'); assert.equal(decoded,false); }
  finally { globalThis.createImageBitmap=original; }
});
test('camera stop handles late permission and capture export failures', async () => {
  let permission,stopped=0;const stream={getTracks:()=>[{stop:()=>stopped++}]};
  const camera=new PhotoCamera({getUserMedia:()=>new Promise(r=>permission=r)});
  const video={play:async()=>{},videoWidth:640,videoHeight:480};const pending=camera.start(video);camera.stop();permission(stream);
  assert.equal(await pending,false);assert.equal(stopped,1);
  const denied=new PhotoCamera({getUserMedia:async()=>{throw new Error('denied');}});await assert.rejects(denied.start(video));denied.stop();
  const retry=new PhotoCamera({getUserMedia:async()=>stream});await retry.start(video);
  const original=globalThis.document;globalThis.document={createElement:()=>({getContext:()=>({drawImage:()=>{}}),toDataURL:()=>{throw new Error('export');}})};
  try { assert.throws(()=>retry.capture(),e=>e.code==='importCameraFailed');assert.equal(stopped,2);assert.equal(video.srcObject,null); }
  finally {globalThis.document=original;}
});

test('default provider transport preserves the browser fetch receiver', async () => {
  const previous = globalThis.fetch;
  globalThis.fetch = function () { assert.equal(this,globalThis); return Promise.resolve(response({colours:challenge.colours})); };
  try {
    const model=new ImageModel({origins:['https://model.invalid'],challenge:async()=>challenge});
    model.connect({endpoint:'https://model.invalid/v1',model:'test'});await model.check();assert.equal(model.status.verified,true);model.disconnect();
  } finally {globalThis.fetch=previous;}
});
