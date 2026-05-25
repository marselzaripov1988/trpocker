import { defineConfig } from 'cypress';

export default defineConfig({
  e2e: {
    baseUrl: process.env['CYPRESS_BASE_URL'] || 'http://localhost:4200',
    env: {
      API_URL: process.env['CYPRESS_API_URL'] || 'http://localhost:8080/api',
      LIVE_TOURNAMENT: process.env['CYPRESS_LIVE_TOURNAMENT'] ?? false
    },
    viewportWidth: 1280,
    viewportHeight: 720,
    video: false,
    screenshotOnRunFailure: true,
    defaultCommandTimeout: 10000,
    requestTimeout: 10000,
    responseTimeout: 30000,
    retries: {
      runMode: 2,
      openMode: 0
    },
    setupNodeEvents(on, config) {
      
    },
    specPattern: 'cypress/e2e/**/*.cy.{js,jsx,ts,tsx}',
    supportFile: 'cypress/support/e2e.ts'
  },
  component: {
    devServer: {
      framework: 'angular',
      bundler: 'webpack'
    },
    specPattern: '**/*.cy.ts'
  }
});
