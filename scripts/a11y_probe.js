/**
 * Dump Rapido accessibility-ish UI via uiautomator while you trigger a ride.
 * Run: node scripts/a11y_probe.js
 */
const { execSync } = require('child_process');
const fs = require('fs');

let n = 1;
console.log('Probing every 800ms. Open a ride alert, then Ctrl+C.');
setInterval(() => {
  try {
    execSync('adb shell uiautomator dump /sdcard/window_dump.xml', { stdio: 'ignore' });
    const file = `probe_${n}.xml`;
    execSync(`adb pull /sdcard/window_dump.xml ${file}`, { stdio: 'ignore' });
    const data = fs.readFileSync(file, 'utf8');
    if (!data.includes('com.rapido.rider')) {
      fs.unlinkSync(file);
      return;
    }
    const texts = [...data.matchAll(/text="([^"]+)"/g)].map(m => m[1]).filter(Boolean);
    const descs = [...data.matchAll(/content-desc="([^"]+)"/g)].map(m => m[1]).filter(Boolean);
    console.log(`\n=== ${file} ===`);
    console.log('texts:', texts.slice(0, 40));
    console.log('descs:', descs.slice(0, 20));
    n++;
  } catch (_) {}
}, 800);
