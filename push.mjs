// Web Push for the Mush Home Screen app, with no dependencies.
// Payload encryption is RFC 8291 (aes128gcm); server identification is VAPID, RFC 8292.

import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

const b64u = (buf) => Buffer.from(buf).toString('base64url');
const unb64u = (s) => Buffer.from(s, 'base64url');
const hmac = (key, data) => crypto.createHmac('sha256', key).update(data).digest();

// Push services browsers hand out. Mush only ever POSTs to these, so a stored
// subscription can't make the server send requests anywhere else.
const PUSH_HOSTS = [/^web\.push\.apple\.com$/, /^fcm\.googleapis\.com$/, /^updates\.push\.services\.mozilla\.com$/, /\.notify\.windows\.com$/];

export function validSubscription(sub) {
  try {
    const u = new URL(sub.endpoint);
    if (u.protocol !== 'https:' || !PUSH_HOSTS.some((re) => re.test(u.hostname))) return false;
    return unb64u(sub.keys.p256dh).length === 65 && unb64u(sub.keys.auth).length === 16;
  } catch {
    return false;
  }
}

/** Encrypts one payload for one subscription (RFC 8291 §3.4, single record). */
export function encrypt(payload, sub, { salt = crypto.randomBytes(16), ecdh = null } = {}) {
  const uaPublic = unb64u(sub.keys.p256dh);
  const authSecret = unb64u(sub.keys.auth);
  if (!ecdh) { ecdh = crypto.createECDH('prime256v1'); ecdh.generateKeys(); }
  const asPublic = ecdh.getPublicKey();
  const shared = ecdh.computeSecret(uaPublic);

  const prkKey = hmac(authSecret, shared);
  const keyInfo = Buffer.concat([Buffer.from('WebPush: info\0'), uaPublic, asPublic, Buffer.from([1])]);
  const ikm = hmac(prkKey, keyInfo);
  const prk = hmac(salt, ikm);
  const cek = hmac(prk, Buffer.from('Content-Encoding: aes128gcm\0\x01', 'binary')).subarray(0, 16);
  const nonce = hmac(prk, Buffer.from('Content-Encoding: nonce\0\x01', 'binary')).subarray(0, 12);

  const cipher = crypto.createCipheriv('aes-128-gcm', cek, nonce);
  const body = Buffer.concat([cipher.update(Buffer.concat([Buffer.from(payload), Buffer.from([2])])), cipher.final(), cipher.getAuthTag()]);
  const header = Buffer.alloc(21);
  salt.copy(header, 0);
  header.writeUInt32BE(4096, 16);
  header.writeUInt8(asPublic.length, 20);
  return Buffer.concat([header, asPublic, body]);
}

export class Push {
  constructor(file) {
    this.file = file;
    this.state = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : {};
    if (!this.state.vapid) {
      const { publicKey, privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
      const jwk = publicKey.export({ format: 'jwk' });
      this.state.vapid = {
        publicKey: b64u(Buffer.concat([Buffer.from([4]), unb64u(jwk.x), unb64u(jwk.y)])),
        privateJwk: privateKey.export({ format: 'jwk' }),
      };
    }
    this.state.subs ??= [];
    this.save();
  }

  save() {
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    const tmp = this.file + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(this.state, null, 1), { mode: 0o600 });
    fs.renameSync(tmp, this.file);
  }

  get publicKey() { return this.state.vapid.publicKey; }
  get count() { return this.state.subs.length; }

  subscribe(sub) {
    const clean = { endpoint: sub.endpoint, keys: { p256dh: sub.keys.p256dh, auth: sub.keys.auth } };
    this.state.subs = this.state.subs.filter((s) => s.endpoint !== clean.endpoint);
    this.state.subs.push(clean);
    this.save();
  }

  vapidHeader(endpoint) {
    const aud = new URL(endpoint).origin;
    const enc = (o) => b64u(JSON.stringify(o));
    // Apple requires a `sub`; a placeholder keeps personal details out of every push.
    const unsigned = `${enc({ typ: 'JWT', alg: 'ES256' })}.${enc({ aud, exp: Math.floor(Date.now() / 1000) + 12 * 3600, sub: 'mailto:mush@example.com' })}`;
    const key = crypto.createPrivateKey({ key: this.state.vapid.privateJwk, format: 'jwk' });
    const sig = crypto.sign('sha256', Buffer.from(unsigned), { key, dsaEncoding: 'ieee-p1363' });
    return `vapid t=${unsigned}.${b64u(sig)}, k=${this.publicKey}`;
  }

  /** Sends to every subscription; drops the ones the push service says are gone. */
  async send(message, { ttl = 120 } = {}) {
    const payload = JSON.stringify(message);
    const results = await Promise.all(this.state.subs.map(async (sub) => {
      try {
        const res = await fetch(sub.endpoint, {
          method: 'POST',
          headers: {
            'content-encoding': 'aes128gcm',
            'content-type': 'application/octet-stream',
            ttl: String(ttl),
            urgency: 'high',
            authorization: this.vapidHeader(sub.endpoint),
          },
          body: encrypt(payload, sub),
          signal: AbortSignal.timeout(10_000),
        });
        const detail = res.ok ? '' : (await res.text()).slice(0, 200);
        return { sub, status: res.status, detail };
      } catch (e) {
        return { sub, status: 0, detail: String(e.message ?? e) };
      }
    }));
    const gone = results.filter((r) => r.status === 404 || r.status === 410).map((r) => r.sub.endpoint);
    if (gone.length) {
      this.state.subs = this.state.subs.filter((s) => !gone.includes(s.endpoint));
      this.save();
    }
    return results.map(({ sub, status, detail }) => ({ host: new URL(sub.endpoint).hostname, status, detail }));
  }
}
