import { defineConfig, devices } from '@playwright/test'

// Contract UI tests intercept API requests; they never write into the development database.
export default defineConfig({
  testDir: './e2e',
  outputDir: '../results/frontend-e2e/artifacts',
  reporter: [['list'], ['html', { outputFolder: '../results/frontend-e2e/report', open: 'never' }]],
  use: { baseURL: 'http://localhost:5173', headless: true, trace: 'retain-on-failure' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
  },
})
