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

// MSG91 invisible OTP / Silent Network Auth uses carrier HTTP endpoints (e.g. Jio).
const NETWORK_SECURITY_CONFIG_XML = `<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">localhost</domain>
        <domain includeSubdomains="true">127.0.0.1</domain>
        <domain includeSubdomains="true">10.0.2.2</domain>
        <domain includeSubdomains="true">partnerapi.jio.com</domain>
        <domain includeSubdomains="true">jio.com</domain>
        <domain includeSubdomains="true">control.msg91.com</domain>
        <domain includeSubdomains="true">msg91.com</domain>
        <domain includeSubdomains="true">80.in.safr.sekuramobile.com</domain>
        <domain includeSubdomains="true">safr.sekuramobile.com</domain>
    </domain-config>
</network-security-config>
`;

function writeNetworkSecurityConfig(platformRoot) {
  const xmlDir = path.join(platformRoot, 'app/src/main/res/xml');
  fs.mkdirSync(xmlDir, { recursive: true });
  fs.writeFileSync(
    path.join(xmlDir, 'network_security_config.xml'),
    NETWORK_SECURITY_CONFIG_XML
  );
  console.log('[withAutoClicker] wrote network_security_config.xml (MSG91/Jio OTP)');
}

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

  writeNetworkSecurityConfig(platformRoot);
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
          'android:label': 'SUPER RIDEX Alerts',
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

    // Allow MSG91 invisible OTP / Jio carrier HTTP (partnerapi.jio.com)
    app.$['android:networkSecurityConfig'] = '@xml/network_security_config';

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
        _: 'SUPER RIDEX monitors ride requests in Ola, Uber and other driver apps and automatically taps Accept when the fare meets your minimum price.',
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

function copyBrandIcons(projectRoot, platformRoot) {
  const srcRoot = path.join(projectRoot, 'assets', 'AppIcons', 'android');
  const resRoot = path.join(platformRoot, 'app', 'src', 'main', 'res');
  if (!fs.existsSync(srcRoot)) {
    console.warn('[withAutoClicker] missing assets/AppIcons/android — skip icon sync');
    return;
  }

  const densities = [
    'mipmap-mdpi',
    'mipmap-hdpi',
    'mipmap-xhdpi',
    'mipmap-xxhdpi',
    'mipmap-xxxhdpi',
  ];

  // Wipe old launchers
  for (const folder of [...densities, 'mipmap-anydpi-v26', 'drawable']) {
    const dir = path.join(resRoot, folder);
    if (!fs.existsSync(dir)) continue;
    for (const file of fs.readdirSync(dir)) {
      if (/^ic_launcher/i.test(file)) {
        fs.unlinkSync(path.join(dir, file));
      }
    }
  }

  let copied = 0;
  for (const density of densities) {
    const fromDir = path.join(srcRoot, density);
    const toDir = path.join(resRoot, density);
    if (!fs.existsSync(fromDir)) continue;
    fs.mkdirSync(toDir, { recursive: true });
    const launcher = path.join(fromDir, 'ic_launcher.png');
    if (fs.existsSync(launcher)) {
      fs.copyFileSync(launcher, path.join(toDir, 'ic_launcher.png'));
      fs.copyFileSync(launcher, path.join(toDir, 'ic_launcher_round.png'));
      copied += 2;
    }
  }

  // Black adaptive background (white looked like a blank circle on splash / Expo Go)
  const drawableDir = path.join(resRoot, 'drawable');
  fs.mkdirSync(drawableDir, { recursive: true });
  fs.writeFileSync(
    path.join(drawableDir, 'ic_launcher_background.xml'),
    `<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#000000" android:pathData="M0,0h108v108h-108z"/>
</vector>
`
  );

  // Keep full splash art available for any legacy drawable refs
  const fullSplashSrc = path.join(projectRoot, 'assets', 'splash_screen.png');
  if (fs.existsSync(fullSplashSrc)) {
    fs.copyFileSync(fullSplashSrc, path.join(drawableDir, 'splash_full.png'));
  }

  // Adaptive XML
  const anydpiTo = path.join(resRoot, 'mipmap-anydpi-v26');
  fs.mkdirSync(anydpiTo, { recursive: true });
  const adaptiveXml = `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
</adaptive-icon>
`;
  fs.writeFileSync(path.join(anydpiTo, 'ic_launcher.xml'), adaptiveXml);
  fs.writeFileSync(path.join(anydpiTo, 'ic_launcher_round.xml'), adaptiveXml);

  // Foreground from AppIcons adaptive-foreground.png into each density
  const fgSrc = path.join(srcRoot, 'adaptive-foreground.png');
  if (fs.existsSync(fgSrc)) {
    for (const density of densities) {
      const toDir = path.join(resRoot, density);
      fs.mkdirSync(toDir, { recursive: true });
      fs.copyFileSync(fgSrc, path.join(toDir, 'ic_launcher_foreground.png'));
      copied++;
    }
  }

  console.log(`[withAutoClicker] synced ${copied} icons from assets/AppIcons/android`);
}

/**
 * Android 12+ always shows a circular splash icon. We only want splash_screen.png
 * (JS LoadingScreen), so replace the native logo with a solid black drawable —
 * invisible on the black splash background.
 */
function neutralizeNativeSplashIcon(platformRoot) {
  const resRoot = path.join(platformRoot, 'app', 'src', 'main', 'res');
  const densityDirs = [
    'drawable-mdpi',
    'drawable-hdpi',
    'drawable-xhdpi',
    'drawable-xxhdpi',
    'drawable-xxxhdpi',
    'drawable-night-mdpi',
    'drawable-night-hdpi',
    'drawable-night-xhdpi',
    'drawable-night-xxhdpi',
    'drawable-night-xxxhdpi',
  ];

  for (const dir of densityDirs) {
    const png = path.join(resRoot, dir, 'splashscreen_logo.png');
    if (fs.existsSync(png)) fs.unlinkSync(png);
    const xml = path.join(resRoot, dir, 'splashscreen_logo.xml');
    if (fs.existsSync(xml)) fs.unlinkSync(xml);
  }

  const drawableDir = path.join(resRoot, 'drawable');
  fs.mkdirSync(drawableDir, { recursive: true });
  // Remove any PNG logo in drawable so XML wins
  const drawablePng = path.join(drawableDir, 'splashscreen_logo.png');
  if (fs.existsSync(drawablePng)) fs.unlinkSync(drawablePng);

  fs.writeFileSync(
    path.join(drawableDir, 'splashscreen_logo.xml'),
    `<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#000000"/>
    <size android:width="1dp" android:height="1dp"/>
</shape>
`
  );
  console.log('[withAutoClicker] native splash icon neutralized (black / invisible)');
}

function withAutoClickerSources(config) {
  return withDangerousMod(config, [
    'android',
    async (config) => {
      copyNativeSources(config.modRequest.projectRoot, config.modRequest.platformProjectRoot);
      copyBrandIcons(config.modRequest.projectRoot, config.modRequest.platformProjectRoot);
      neutralizeNativeSplashIcon(config.modRequest.platformProjectRoot);
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
