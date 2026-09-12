/**
 * Railway must start the Express API.
 * Local `npm start` still opens Expo.
 */
const { spawn } = require('child_process');

const isRailway = Boolean(
  process.env.RAILWAY_ENVIRONMENT
  || process.env.RAILWAY_PROJECT_ID
  || process.env.RAILWAY_SERVICE_ID
);

if (isRailway) {
  require('./backend/src/index.js').startServer();
} else {
  const child = spawn('npx', ['expo', 'start', ...process.argv.slice(2)], {
    stdio: 'inherit',
    shell: true,
  });
  child.on('exit', (code) => process.exit(code == null ? 0 : code));
}
