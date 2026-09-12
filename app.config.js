const fs = require('fs');
const path = require('path');

try {
  require('dotenv').config({ path: path.join(__dirname, '.env') });
} catch {
  // dotenv is optional — extra.apiUrl falls back to process.env / defaults
}

const msg91WidgetId = String(process.env.EXPO_PUBLIC_MSG91_WIDGET_ID || '').trim();
const msg91AuthToken = String(process.env.EXPO_PUBLIC_MSG91_AUTH_TOKEN || '').trim();

// Metro inlines process.env at bundler start. Keep a JS module in sync with .env
// so OTP works after filling keys without a native rebuild.
try {
  fs.mkdirSync(path.join(__dirname, 'src', 'config'), { recursive: true });
  fs.writeFileSync(
    path.join(__dirname, 'src', 'config', 'generatedEnv.js'),
    [
      '// Generated from .env by app.config.js — do not edit.',
      `export const MSG91_WIDGET_ID = ${JSON.stringify(msg91WidgetId)};`,
      `export const MSG91_AUTH_TOKEN = ${JSON.stringify(msg91AuthToken)};`,
      '',
    ].join('\n')
  );
} catch {
  // ignore — otp.js still reads extra / EXPO_PUBLIC_*
}

/** @type {import('expo/config').ExpoConfig} */
export default {
  expo: {
    name: 'AG rider',
    slug: 'ridio',
    version: '1.0.0',
    orientation: 'portrait',
    icon: './assets/app_icon.png',
    userInterfaceStyle: 'dark',
    splash: {
      image: './assets/splash-ag-rider.png',
      resizeMode: 'contain',
      backgroundColor: '#041109',
    },
    plugins: [
      [
        'expo-splash-screen',
        {
          backgroundColor: '#041109',
          image: './assets/splash-ag-rider.png',
          imageWidth: 200,
          resizeMode: 'contain',
        },
      ],
      // Runs after expo-splash-screen so we can install the AG rider splash logo
      './plugins/withAutoClicker.js',
    ],
    ios: {
      supportsTablet: false,
      bundleIdentifier: 'com.ridio.app',
      icon: './assets/app_icon.png',
    },
    android: {
      package: 'com.ridio.app',
      icon: './assets/app_icon.png',
      adaptiveIcon: {
        backgroundColor: '#041109',
        foregroundImage: './assets/AppIcons/android/adaptive-foreground.png',
      },
      permissions: [
        'android.permission.RECEIVE_BOOT_COMPLETED',
        'android.permission.VIBRATE',
        'android.permission.FOREGROUND_SERVICE',
        'android.permission.FOREGROUND_SERVICE_SPECIAL_USE',
        'android.permission.POST_NOTIFICATIONS',
        'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
        'android.permission.WAKE_LOCK',
        'android.permission.INTERNET',
        'android.permission.ACCESS_NETWORK_STATE',
        'android.permission.SYSTEM_ALERT_WINDOW',
      ],
    },
    web: {
      favicon: './assets/app_icon.png',
    },
    extra: {
      apiUrl:
        process.env.EXPO_PUBLIC_API_URL ||
        'https://superridexversion2-production.up.railway.app',
      msg91WidgetId,
      msg91AuthToken,
    },
  },
};
