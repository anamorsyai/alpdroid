// A stand-in for a WebView: a real Chromium page served with a strict Content-Security-Policy + Trusted Types, driven over
// stdin/stdout lines. "send"/"raw" behave like evaluateJavascript (no eval involved: they use CDP Runtime.evaluate), and
// `__alpbridge.done` reports back like the JavascriptInterface does.
const http = require('http');
const readline = require('readline');
const { chromium } = require('playwright');

(async () => {
  const csp = process.argv[2] || "script-src 'self'; require-trusted-types-for 'script'";
  const server = http.createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'text/html', 'Content-Security-Policy': csp });
    res.end('<!doctype html><title>Strict page</title><input name=q><p id=a>one</p><p>two</p>');
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const browser = await chromium.launch();
  const page = await browser.newPage();
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Runtime.enable');
  await cdp.send('Runtime.addBinding', { name: '__alpbridgeRaw' });
  cdp.on('Runtime.bindingCalled', (e) => { if (e.name === '__alpbridgeRaw') { const [id, json] = JSON.parse(e.payload); out({ report: [id, json] }); } });
  await page.addInitScript(() => { window.__alpbridge = { done: (id, json) => window.__alpbridgeRaw(JSON.stringify([id, json])) }; });
  await page.goto('http://127.0.0.1:' + server.address().port + '/');
  const out = (o) => process.stdout.write(JSON.stringify(o) + '\n');
  out({ ready: true });
  const rl = readline.createInterface({ input: process.stdin });
  rl.on('line', async (line) => {
    const m = JSON.parse(line);
    if (m.op === 'quit') { await browser.close(); server.close(); process.exit(0); }
    const r = await cdp.send('Runtime.evaluate', { expression: m.script, returnByValue: true, awaitPromise: false, allowUnsafeEvalBlockedByCSP: false }).catch((e) => ({ exceptionDetails: { text: String(e) } }));
    let value = null;
    if (!r.exceptionDetails && r.result && r.result.value !== undefined) value = JSON.stringify(r.result.value);
    out({ reply: m.n, value, threw: !!r.exceptionDetails });
  });
})().catch((e) => { process.stderr.write(String(e && e.stack || e)); process.exit(1); });
