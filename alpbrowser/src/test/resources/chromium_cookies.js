// A real Chromium with DevTools on a TCP port and two cookies set, for CdpClient tests.
const { chromium } = require('playwright');
(async () => {
  const port = process.argv[2];
  // A persistent context is the browser's default one, like a WebView's single cookie jar.
  const dir = require('fs').mkdtempSync(require('path').join(require('os').tmpdir(), 'cdpcookies-'));
  const context = await chromium.launchPersistentContext(dir, { args: ['--remote-debugging-port=' + port] });
  await context.addCookies([
    { name: 'sid', value: 'abc', domain: 'example.test', path: '/app', httpOnly: true, secure: true, sameSite: 'Lax', expires: 1900000000 },
    { name: 'pref', value: 'dark', domain: 'other.test', path: '/', httpOnly: false, secure: false, sameSite: 'Strict' },
  ]);
  await context.newPage();
  process.stdout.write('ready\n');
  process.stdin.resume();
  process.stdin.on('end', async () => { await context.close(); process.exit(0); });
})().catch((e) => { process.stderr.write(String(e && e.stack || e)); process.exit(1); });
