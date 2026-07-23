import 'dotenv/config';

/** @type {import('expo/config').ExpoConfig} */
export default {
  expo: {
    name: 'Super Rides',
    slug: 'playnix',
    version: '1.0.0',
    orientation: 'portrait',
    icon: './assets/icon.png',
    userInterfaceStyle: 'light',
    splash: {
      image: './assets/splash-icon.png',
      resizeMode: 'contain',
      backgroundColor: '#F4F7FB',
    },
    plugins: [
      './plugins/withAutoClicker.js',
      [
        'expo-splash-screen',
        {
          backgroundColor: '#F4F7FB',
          image: './assets/splash-icon.png',
          imageWidth: 220,
        },
      ],
    ],
    ios: {
      supportsTablet: false,
      bundleIdentifier: 'com.playnix.app',
    },
    android: {
      package: 'com.playnix.app',
      adaptiveIcon: {
        backgroundColor: '#F4F7FB',
        foregroundImage: './assets/icon.png',
      },
      permissions: [
        'android.permission.RECEIVE_BOOT_COMPLETED',
        'android.permission.VIBRATE',
        'android.permission.FOREGROUND_SERVICE',
        'android.permission.FOREGROUND_SERVICE_SPECIAL_USE',
        'android.permission.POST_NOTIFICATIONS',
        'android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
        'android.permission.INTERNET',
        'android.permission.ACCESS_NETWORK_STATE',
      ],
    },
    web: {
      favicon: './assets/favicon.png',
    },
    extra: {
      apiUrl: process.env.EXPO_PUBLIC_API_URL,
      razorpayKeyId: process.env.EXPO_PUBLIC_RAZORPAY_KEY_ID,
      razorpayMode: process.env.EXPO_PUBLIC_RAZORPAY_MODE || 'test',
      msg91WidgetId: process.env.EXPO_PUBLIC_MSG91_WIDGET_ID,
      msg91AuthToken: process.env.EXPO_PUBLIC_MSG91_AUTH_TOKEN,
    },
  },
};
