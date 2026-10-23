#!/usr/bin/env node
// HTTP smoke against the pinned local identity provider and captured mailbox, with disposable identities only.
import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { randomBytes, randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';
import { request as httpRequest } from 'node:http';

const stackFile = resolve(process.argv[2] ?? '');
assert(process.argv[2], 'Pass the local preview stack.json path');
const stack = JSON.parse(await readFile(stackFile, 'utf8'));
const app = stack.origin, idp = new URL(stack.issuer).origin, mailbox = stack.mailbox;
for (const value of [app, idp, mailbox]) assert(new URL(value).hostname === 'localhost' || new URL(value).hostname.endsWith('.localhost'), 'Only a loopback preview is allowed');
const decode = value => value.replaceAll('&amp;', '&').replaceAll('&quot;', '"').replaceAll('&#39;', "'");
const attrs = value => Object.fromEntries([...value.matchAll(/([\w-]+)=["']([^"']*)["']/g)].map(m => [m[1], decode(m[2])]));
class Session {
  cookies = new Map();
  async request(url, options = {}) {
    const target = new URL(url, app);
    assert([new URL(app).origin, idp].includes(target.origin), 'Unexpected authentication destination');
    const cookie = [...this.cookies.values()].filter(c => c.host === target.hostname && target.pathname.startsWith(c.path)).map(c => `${c.name}=${c.value}`).join('; ');
    if (target.pathname === '/auth/callback') {
      assert(cookie.includes('usuals-login='), 'OIDC callback lost its browser-binding cookie');
      assert.equal(target.searchParams.get('iss'),stack.issuer,'OIDC callback issuer must remain exact');
    }
    // Connect explicitly to loopback while preserving the intended Host; fetch may discard a manual Host header.
    const response = await new Promise((done,reject) => {
      const req = httpRequest({ hostname:'127.0.0.1', port:target.port, path:target.pathname+target.search, method:options.method ?? 'GET',
        headers:{...options.headers,Host:target.host,...(cookie ? { Cookie:cookie } : {})}, timeout:15000 }, res => {
        const chunks = []; let size=0;
        res.on('data',chunk=>{ size+=chunk.length; if(size>2*1024*1024) req.destroy(new Error('Oversized local authentication response')); else chunks.push(chunk); });
        res.on('error',reject);
        res.on('end',()=>{
          const headers=new Headers();
          for(const [name,value] of Object.entries(res.headers)) for(const item of Array.isArray(value) ? value : [value]) if(item !== undefined) headers.append(name,item);
          done(new Response([204,304].includes(res.statusCode) ? null : Buffer.concat(chunks),{status:res.statusCode,headers}));
        });
      });
      req.on('error',reject); req.on('timeout',()=>req.destroy(new Error('Local authentication timeout')));
      req.end(options.body instanceof URLSearchParams ? options.body.toString() : options.body);
    });
    for (const raw of response.headers.getSetCookie()) {
      const [pair,...rest] = raw.split(';'), split = pair.indexOf('='), name = pair.slice(0,split), value = pair.slice(split+1);
      const path = rest.find(p => p.trim().toLowerCase().startsWith('path='))?.trim().slice(5) ?? '/';
      const key = `${target.hostname}:${path}:${name}`;
      if (!value || rest.some(p => p.trim().toLowerCase() === 'max-age=0')) this.cookies.delete(key);
      else this.cookies.set(key, { host: target.hostname, path, name, value });
    }
    return { response, url: target.href, html: await response.text() };
  }
  async follow(url, options = {}) {
    let result = await this.request(url, options);
    for (let i=0; i<20 && [301,302,303,307,308].includes(result.response.status); i++) {
      result = await this.request(new URL(result.response.headers.get('location'), result.url));
    }
    return result;
  }
  async form(page, values) {
    const forms = [...page.html.matchAll(/<form\b([^>]*)>([\s\S]*?)<\/form>/g)].map(m => ({ attributes: attrs(m[1]), content: m[2] }));
    const form = forms.find(f => f.attributes.action && /kc-.*form/.test(f.attributes.id ?? '')) ?? forms.find(f => f.attributes.action);
    assert(form, 'Expected a local identity-provider form');
    const fields = new URLSearchParams();
    for (const m of form.content.matchAll(/<input\b([^>]*)>/g)) {
      const field = attrs(m[1]);
      if (field.name && field.type === 'hidden') fields.set(field.name, field.value ?? '');
    }
    for (const [name,value] of Object.entries(values)) fields.set(name,value);
    const url = new URL(form.attributes.action, page.url);
    return this.follow(url.href, { method: 'POST', headers: { Origin: new URL(page.url).origin, 'Content-Type':'application/x-www-form-urlencoded' }, body: fields });
  }
  async api(path, { method = 'GET', body, csrf } = {}) {
    const result = await this.request(app+path, { method, headers: { ...(method === 'GET' ? {} : { Origin: app }), ...(csrf ? {'X-CSRF-Token':csrf} : {}), ...(body === undefined ? {} : {'Content-Type':'application/json'}) }, body: body === undefined ? undefined : JSON.stringify(body) });
    let data; try { data = JSON.parse(result.html); } catch {}
    return { status: result.response.status, data };
  }
}
async function mailLink(email, kind) {
  for (let attempt=0; attempt<60; attempt++) {
    const page = await (await fetch(`${mailbox}/api/v1/messages`)).json();
    for (const message of page.messages ?? []) {
      if (!message.To?.some(to => to.Address === email)) continue;
      const detail = await (await fetch(`${mailbox}/api/v1/message/${message.ID}`)).json();
      const links = [...(detail.HTML ?? '').matchAll(/href=["']([^"']+)["']/g)].map(m => decode(m[1]));
      const link = links.find(link => kind === 'verification' ? link.startsWith(idp) && link.includes('/login-actions/action-token') : link.startsWith(app+'/join#'));
      if (link) return link;
    }
    await new Promise(done => setTimeout(done,500));
  }
  throw new Error(`Local ${kind} email did not arrive`);
}
async function register(session, email, password) {
  let page = await session.follow(app+'/auth/register');
  assert(page.response.ok && page.html.includes('name="email"'), 'Email registration form was not reached');
  assert(!page.html.includes('name="password"'), 'The local registration should verify email before password setup');
  page = await session.form(page, { email });
  const link = await mailLink(email,'verification');
  page = await session.follow(link);
  if (page.html.includes('name="password-new"')) page = await session.form(page, { 'password-new':password, 'password-confirm':password });
  else if (page.html.includes('name="password"')) page = await session.form(page, { password, 'password-confirm':password });
  else assert(false, 'Expected credential setup after email verification');
  const me = await session.api('/api/me');
  if (me.status !== 200) {
    await writeFile(join(dirname(stackFile),'registration-result.html'),page.html,{mode:0o600});
    process.stdout.write(`Registration diagnostic: HTTP ${page.response.status}, ${new URL(page.url).pathname}, product ${me.data?.code ?? me.status}.\n`);
  }
  assert.equal(me.status,200,'Registration did not return to an authenticated product session');
  assert.equal(me.data.user.email,email);
  return me.data;
}
const suffix = randomUUID().slice(0,8), aliceEmail = `alice-${suffix}@example.test`, bobEmail = `bob-${suffix}@example.test`;
const alicePassword = randomBytes(24).toString('base64url'), bobPassword = randomBytes(24).toString('base64url');
const alice = new Session(), bob = new Session();
const a = await register(alice,aliceEmail,alicePassword);
process.stdout.write('PASS: email-only registration, captured verification, credential setup and product session.\n');
const sharedId = randomUUID(), privateId = randomUUID();
for (const [id,name] of [[sharedId,'Shared market'],[privateId,'Private market']]) assert.equal((await alice.api(`/api/lists/${id}`, { method:'PUT',body:{projection:{name}},csrf:a.csrfToken })).status,201);
assert.equal((await alice.api(`/api/lists/${sharedId}/invitations`, {method:'POST',body:{email:bobEmail},csrf:a.csrfToken})).status,201);
const invitationLink = await mailLink(bobEmail,'invitation');
assert.equal((await bob.request(invitationLink)).response.status,200,'Link preview must be harmless');
const attach = async session => {
  const challenge = await session.api('/api/invitations/challenge');
  assert.equal((await session.api('/api/invitations/context', {method:'POST',body:{token:new URL(invitationLink).hash.slice(1)},csrf:challenge.data.csrfToken})).status,204);
};
await attach(alice);
assert.equal((await alice.api('/api/invitations/current')).data.state,'wrong_account');
assert.equal((await alice.api('/api/invitations/accept',{method:'POST',body:{},csrf:a.csrfToken})).status,403);
await attach(bob);
const b = await register(bob,bobEmail,bobPassword);
assert.equal((await bob.api('/api/lists/changes')).data.lists.length,0,'Registration must not auto-accept membership');
assert.equal((await bob.api('/api/invitations/current')).data.state,'ready');
assert.equal((await bob.api('/api/invitations/current')).data.inviter,aliceEmail);
const accepted = await bob.api('/api/invitations/accept',{method:'POST',body:{},csrf:b.csrfToken});
assert.equal(accepted.status,200); assert.equal(accepted.data.list.id,sharedId);
assert.deepEqual(accepted.data.list.members.map(m=>m.name).sort(),[aliceEmail,bobEmail].sort(),'Email-only accounts must have recognizable member labels');
const replay = await bob.api('/api/invitations/accept',{method:'POST',body:{},csrf:b.csrfToken});
assert.equal(replay.data.list.seq,accepted.data.list.seq);
assert.deepEqual((await bob.api('/api/lists/changes')).data.lists.map(l=>l.id),[sharedId]);
// Switching the product account must not silently reuse the previous provider SSO identity.
async function switchAccount(session, csrf) {
  assert.equal((await session.api('/auth/switch')).status,405);
  assert.equal((await session.api('/auth/switch',{method:'POST',body:{}})).status,403);
  const target = await session.api('/auth/switch',{method:'POST',body:{},csrf});
  assert.equal(target.status,200);
  assert.equal((await session.api('/api/me')).status,401,'Switching must immediately revoke the old product session');
  let page = await session.follow(target.data.url);
  if (page.html.includes('name="confirmLogout"')) page = await session.form(page,{confirmLogout:'yes'});
  assert(page.html.includes('name="username"'),'Provider sign-out must lead to an account-selection login form');
  return page;
}
let loginPage = await switchAccount(alice,a.csrfToken);
await alice.form(loginPage,{username:bobEmail,password:bobPassword});
assert.equal((await alice.api('/api/me')).data.user.id,b.user.id);
assert.equal((await alice.api('/api/invitations/current')).data.state,'accepted','The invitation must survive switching accounts');
loginPage = await switchAccount(alice,(await alice.api('/api/me')).data.csrfToken);
await alice.form(loginPage,{username:aliceEmail,password:alicePassword});
assert.equal((await alice.api('/api/me')).data.user.id,a.user.id);
// The server's source of protocol versions is read below without a duplicated numeric pin.
const root = fileURLToPath(new URL('../..',import.meta.url));
const versions = await readFile(join(root,'core/model/src/main/java/io/theficos/usuals/model/Versions.kt'),'utf8');
const normalizer = Number(versions.match(/NORMALIZER_VERSION: Int = (\d+)/)[1]);
const intent = { id:randomUUID(),listId:sharedId,kind:'capture',payload:{ proposedId:randomUUID(),key:'milk',name:'Milk',position:0 },normalizer };
const captured = await bob.api(`/api/lists/${sharedId}/intents`,{method:'POST',body:{intents:[intent]},csrf:b.csrfToken});
assert.equal(captured.data.results[0].outcome,'applied');
const seen = (await alice.api('/api/lists/changes')).data.lists.find(l=>l.id===sharedId);
assert.equal(seen.seq,captured.data.list.seq);
process.stdout.write('PASS: captured invitation, wrong-account rejection, new-recipient registration, explicit acceptance, account switching, replay safety and shared edits.\n');
if (stack.cloud) {
  const first = await alice.api('/cloud/api/account'), second = await bob.api('/cloud/api/account');
  assert.equal(first.status,200); assert.equal(second.status,200);
  assert.equal(first.data.account.id,a.user.id); assert.equal(second.data.account.id,b.user.id);
  assert.notEqual(first.data.tenantId,second.data.tenantId);
  assert.equal(first.data.plan,'pilot'); assert.equal(second.data.canCheckout,false);
  process.stdout.write('PASS: new upstream accounts receive separate free cloud beta records automatically.\n');
}
await writeFile(join(dirname(stackFile),'e2e-accounts.json'),JSON.stringify({synthetic:true,alice:{email:aliceEmail,password:alicePassword},bob:{email:bobEmail,password:bobPassword},sharedId,privateId},null,2)+'\n',{mode:0o600});
process.stdout.write(`Synthetic browser sign-in credentials: ${join(dirname(stackFile),'e2e-accounts.json')}\n`);
