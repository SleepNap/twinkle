/**
 * Web 控制台无头验收：登录 → 逐页截图 → 管理 API 断言。
 *
 * 前置：
 *   1. 后端已启动（single 档，HTTP 默认 8686，内置管理员 admin/admin）
 *   2. 前端已启动（`npm run dev`，Vite 默认 5173）或直接用后端内置的 dist
 * 运行：
 *   npm run smoke:console
 *   BASE_URL=http://127.0.0.1:8686 npm run smoke:console   # 直接验收打包产物
 *   ADMIN_NAME=admin ADMIN_PASSWORD=admin CHROME_PATH=... npm run smoke:console
 *
 * 截图默认写入 `docs/screenshots/console/`；退出码 0 = 全部页面渲染且 API 正常。
 * 只驱动本机已安装的 Chromium（playwright-core 不下载浏览器）。
 */
import { chromium } from 'playwright-core'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const base = process.env.BASE_URL || 'http://127.0.0.1:5173'
const outDir = process.env.SHOT_DIR || path.resolve(here, '../../docs/screenshots/console')
const adminName = process.env.ADMIN_NAME || 'admin'
const adminPassword = process.env.ADMIN_PASSWORD || 'admin'
const chromePath = process.env.CHROME_PATH
  || 'C:/Users/CN/AppData/Local/ms-playwright/chromium-1234/chrome-win64/chrome.exe'

const routes = [
  ['', '03-overview'],
  ['channels', '04-channels'],
  ['players', '05-players'],
  ['accounts', '06-accounts'],
  ['config', '07-config'],
  ['operations', '08-operations'],
  ['roles', '09-roles'],
  ['audits', '10-audits'],
  ['api-keys', '11-api-keys'],
  ['capabilities', '12-capabilities'],
]

if (!fs.existsSync(chromePath)) {
  console.error(`[smoke] 未找到 Chromium：${chromePath}`)
  console.error('[smoke] 请设置 CHROME_PATH，或先执行 npx playwright install chromium')
  process.exit(1)
}
fs.mkdirSync(outDir, { recursive: true })

const browser = await chromium.launch({ executablePath: chromePath, headless: true })
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 }, deviceScaleFactor: 1 })
const pageErrors = []
const httpErrors = []
page.on('pageerror', (error) => pageErrors.push(error.stack || String(error)))
page.on('response', (response) => {
  if (response.status() >= 400 && !response.url().endsWith('/favicon.ico')) {
    httpErrors.push(`${response.status()} ${response.url()}`)
  }
})
page.on('console', (message) => {
  // 资源 404 由 httpErrors 单独统计；这里只留应用层控制台错误。
  if (message.type() === 'error' && !message.text().startsWith('Failed to load resource')) {
    pageErrors.push(`console: ${message.text()}`)
  }
})

const summary = { base, outDir, screenshots: [], pages: [], api: {}, pageErrors, httpErrors }

try {
  await page.goto(`${base}/#/login`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('#login-name', { timeout: 20000 })
  await page.screenshot({ path: path.join(outDir, '01-login.png'), fullPage: true })
  summary.screenshots.push('01-login.png')

  await page.fill('#login-name', adminName)
  await page.fill('#login-password', adminPassword)
  await page.click('button[type="submit"]')
  await page.waitForFunction(() => window.location.hash === '#/', null, { timeout: 20000 })
  await page.waitForSelector('main', { timeout: 20000 })
  await page.waitForTimeout(1200)

  const token = await page.evaluate(() => window.sessionStorage.getItem('twinkle.console.admin-session') || '')
  if (!token) throw new Error('登录后未拿到管理员 session token')

  const api = (requestPath) => page.evaluate(async ({ requestPath, token }) => {
    const response = await fetch(requestPath, { headers: { Authorization: `Bearer ${token}` } })
    const text = await response.text()
    let body = null
    try { body = JSON.parse(text) } catch { body = text }
    return { status: response.status, body }
  }, { requestPath, token })

  summary.api.health = await api('/admin/v1/health')
  summary.api.channels = await api('/admin/v1/channels')

  for (const [route, name] of routes) {
    const before = pageErrors.length
    await page.goto(`${base}/#/${route}`, { waitUntil: 'domcontentloaded' })
    await page.waitForTimeout(1100)
    const text = await page.locator('body').innerText().catch(() => '')
    const heading = await page.locator('main h1:visible, main h2:visible').first().innerText().catch(() => '')
    summary.pages.push({
      route: route || '/',
      heading: heading.trim(),
      textLength: text.length,
      errorsDuringPage: pageErrors.length - before,
    })
    const file = `${name}.png`
    await page.screenshot({ path: path.join(outDir, file), fullPage: true })
    summary.screenshots.push(file)
  }

  summary.ok = summary.api.health.status === 200
    && summary.api.channels.status === 200
    && summary.pages.every((item) => item.textLength > 80)
    && pageErrors.length === 0
    && httpErrors.length === 0

  console.log(JSON.stringify(summary, null, 2))
} finally {
  await browser.close()
}

process.exit(summary.ok ? 0 : 2)
