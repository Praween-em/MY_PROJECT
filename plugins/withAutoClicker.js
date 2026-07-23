const {
  withAndroidManifest,
  withStringsXml,
  withDangerousMod,
  AndroidConfig,
} = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

const JAVA_DIR = 'app/src/main/java/com/playnix/app';
const JAVA_FILES = [
  'AutoClickerConfig.java',
  'AutoClickerService.java',
  'AutoClickerModule.java',
  'AutoClickerPackage.java',
  'BootReceiver.java',
  'RideAlertListener.java',
];

const ACCESSIBILITY_XML = `<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagReportViewIds|flagRetrieveInteractiveWindows|flagIncludeNotImportantViews"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:notificationTimeout="0"
    android:description="@string/accessibility_service_description" />
`;

function copyNativeSources(projectRoot, platformRoot) {
  const srcDir = path.join(projectRoot, 'native-android', 'src');
  const destDir = path.join(platformRoot, JAVA_DIR);
  if (!fs.existsSync(srcDir)) {
    console.warn('[withAutoClicker] missing native-android/src — skip Java sync');
    return;
  }

  fs.mkdirSync(destDir, { recursive: true });
  let copied = 0;
  for (const file of JAVA_FILES) {
    const from = path.join(srcDir, file);
    const to = path.join(destDir, file);
    if (!fs.existsSync(from)) {
      console.warn(`[withAutoClicker] missing source ${file}`);
      continue;
    }
    fs.copyFileSync(from, to);
    copied++;
  }
  console.log(`[withAutoClicker] synced ${copied}/${JAVA_FILES.length} Java files → ${JAVA_DIR}`);

  const xmlDir = path.join(platformRoot, 'app/src/main/res/xml');
  fs.mkdirSync(xmlDir, { recursive: true });
  const xmlPath = path.join(xmlDir, 'accessibility_service_config.xml');
  fs.writeFileSync(xmlPath, ACCESSIBILITY_XML);
  // Guard: prebuild must keep gesture capability (Smart Auto Clicker style)
  const xml = fs.readFileSync(xmlPath, 'utf8');
  if (!xml.includes('canPerformGestures="true"') || !xml.includes('notificationTimeout="0"')) {
    throw new Error('[withAutoClicker] accessibility_service_config.xml missing required flags');
  }
}

function withAutoClickerManifest(config) {
  return withAndroidManifest(config, (mod) => {
    const app = AndroidConfig.Manifest.getMainApplicationOrThrow(mod.modResults);

    app.service = app.service ?? [];

    const hasA11y = app.service.some((s) => s.$?.['android:name'] === '.AutoClickerService');
    // Ensure FGS special-use type on existing or new a11y service (low-RAM survival)
    const a11ySvc = app.service.find((s) => s.$?.['android:name'] === '.AutoClickerService');
    if (a11ySvc?.$) {
      a11ySvc.$['android:foregroundServiceType'] = 'specialUse';
      a11ySvc.property = a11ySvc.property ?? [];
      const hasProp = a11ySvc.property.some(
        (p) => p.$?.['android:name'] === 'android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE'
      );
      if (!hasProp) {
        a11ySvc.property.push({
          $: {
            'android:name': 'android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE',
            'android:value': 'accessibility_race_engine',
          },
        });
      }
    }
    if (!hasA11y) {
      app.service.push({
        $: {
          'android:name': '.AutoClickerService',
          'android:exported': 'true',
          'android:label': '@string/app_name',
          'android:permission': 'android.permission.BIND_ACCESSIBILITY_SERVICE',
          'android:foregroundServiceType': 'specialUse',
        },
        'intent-filter': [
          {
            action: [{ $: { 'android:name': 'android.accessibilityservice.AccessibilityService' } }],
          },
        ],
        'meta-data': [
          {
            $: {
              'android:name': 'android.accessibilityservice',
              'android:resource': '@xml/accessibility_service_config',
            },
          },
        ],
        property: [
          {
            $: {
              'android:name': 'android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE',
              'android:value': 'accessibility_race_engine',
            },
          },
        ],
      });
    }

    const hasNls = app.service.some((s) => s.$?.['android:name'] === '.RideAlertListener');
    if (!hasNls) {
      app.service.push({
        $: {
          'android:name': '.RideAlertListener',
          'android:exported': 'true',
          'android:label': 'Super Rides Alerts',
          'android:permission': 'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE',
        },
        'intent-filter': [
          {
            action: [
              { $: { 'android:name': 'android.service.notification.NotificationListenerService' } },
            ],
          },
        ],
      });
    }

    const hasReceiver = app.receiver?.some((r) => r.$?.['android:name'] === '.BootReceiver');
    if (!hasReceiver) {
      app.receiver = [
        ...(app.receiver ?? []),
        {
          $: { 'android:name': '.BootReceiver', 'android:exported': 'true' },
          'intent-filter': [
            {
              action: [{ $: { 'android:name': 'android.intent.action.BOOT_COMPLETED' } }],
            },
          ],
        },
      ];
    }

    // Drop leftover Shizuku provider if present from older builds
    if (app['provider']) {
      app['provider'] = app['provider'].filter(
        (p) => p.$?.['android:name'] !== 'rikka.shizuku.ShizukuProvider'
      );
    }

    return mod;
  });
}

function withAutoClickerStrings(config) {
  return withStringsXml(config, (mod) => {
    mod.modResults.resources.string = [
      ...(mod.modResults.resources.string ?? []).filter(
        (s) => s.$.name !== 'accessibility_service_description'
      ),
      {
        $: { name: 'accessibility_service_description' },
        _: 'Super Rides monitors ride requests in Ola, Uber and other driver apps and automatically taps Accept when the fare meets your minimum price.',
      },
    ];
    return mod;
  });
}

function patchMainApplication(platformRoot) {
  const mainAppPath = path.join(
    platformRoot,
    'app/src/main/java/com/playnix/app/MainApplication.kt'
  );
  if (!fs.existsSync(mainAppPath)) return;

  let contents = fs.readFileSync(mainAppPath, 'utf8');
  if (contents.includes('AutoClickerPackage()')) return;

  contents = contents.replace(
    /PackageList\(this\)\.packages\.apply\s*\{[^}]*\}/,
    `PackageList(this).packages.apply {\n          add(AutoClickerPackage())\n        }`
  );
  fs.writeFileSync(mainAppPath, contents);
}

function withAutoClickerSources(config) {
  return withDangerousMod(config, [
    'android',
    async (config) => {
      copyNativeSources(config.modRequest.projectRoot, config.modRequest.platformProjectRoot);
      patchMainApplication(config.modRequest.platformProjectRoot);
      return config;
    },
  ]);
}

function withAutoClickerGradle(config) {
  return withDangerousMod(config, [
    'android',
    async (config) => {
      const gradlePath = path.join(
        config.modRequest.platformProjectRoot,
        'app/build.gradle'
      );
      if (!fs.existsSync(gradlePath)) return config;
      let contents = fs.readFileSync(gradlePath, 'utf8');
      // Strip legacy Shizuku deps if present
      const cleaned = contents
        .replace(/\n\s*implementation\("dev\.rikka\.shizuku:api:[^"]+"\)/g, '')
        .replace(/\n\s*implementation\("dev\.rikka\.shizuku:provider:[^"]+"\)/g, '');
      if (cleaned !== contents) {
        fs.writeFileSync(gradlePath, cleaned);
      }
      return config;
    },
  ]);
}

function withAutoClicker(config) {
  config = withAutoClickerManifest(config);
  config = withAutoClickerStrings(config);
  config = withAutoClickerGradle(config);
  config = withAutoClickerSources(config);
  return config;
}

module.exports = withAutoClicker;
