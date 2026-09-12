const {
  withAndroidManifest,
  withStringsXml,
  withDangerousMod,
  AndroidConfig,
} = require('@expo/config-plugins');
const fs = require('fs');
const path = require('path');

/** Must match app.config.js android.package */
const APP_PACKAGE = 'com.ridio.app';
const JAVA_DIR = `app/src/main/java/${APP_PACKAGE.replace(/\./g, '/')}`;
const JAVA_FILES = [
  'AutoClickerConfig.java',
  'AutoClickerService.java',
  'AutoClickerModule.java',
  'AutoClickerPackage.java',
    'BootReceiver.java',
    'EngineKeepAlive.java',
    'RecentsGuard.java',
    'RideAlertListener.java',
  'ServiceHealth.java',
  'ShizukuInput.java',
  'TapHighlightOverlay.java',
];

const SHIZUKU_PKG = 'moe.shizuku.privileged.api';
const TARGET_PACKAGES = [
  'com.olacabs.oladriver',
  'com.rapido.rider',
  'com.rapido.captain',
  'com.android.vending',
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

const UNIVERSAL_APK_BLOCK = `
    // One universal APK for all phones (armeabi-v7a + arm64-v8a). No per-ABI packages.
    splits {
        abi {
            enable false
        }
    }
`;

const NDK_ABI_FILTERS_BLOCK = `
        // Phone ABIs only inside the single APK (no x86 emulator libs)
        ndk {
            abiFilters "armeabi-v7a", "arm64-v8a"
        }
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

  // Drop stale packages from older branding
  const javaRoot = path.join(platformRoot, 'app/src/main/java');
  for (const stale of ['com/playnix', 'com/rapidotap', 'com/rapido']) {
    const stalePath = path.join(javaRoot, stale);
    if (fs.existsSync(stalePath)) {
      fs.rmSync(stalePath, { recursive: true, force: true });
      console.log(`[withAutoClicker] removed stale package tree ${stale}`);
    }
  }

  fs.mkdirSync(destDir, { recursive: true });
  const stalePointClicker = path.join(destDir, 'PointClicker.java');
  if (fs.existsSync(stalePointClicker)) {
    fs.unlinkSync(stalePointClicker);
    console.log('[withAutoClicker] removed stale PointClicker.java');
  }
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

    const a11ySvc = app.service.find((s) => s.$?.['android:name'] === '.AutoClickerService');
    if (a11ySvc?.$) {
      // Separate process so swipe-killing the UI task does not kill Accept
      a11ySvc.$['android:process'] = ':engine';
      a11ySvc.$['android:stopWithTask'] = 'false';
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

    const nlsSvc = app.service.find((s) => s.$?.['android:name'] === '.RideAlertListener');
    if (nlsSvc?.$) {
      nlsSvc.$['android:process'] = ':engine';
      nlsSvc.$['android:stopWithTask'] = 'false';
    }

    const keepAlive = app.service.find((s) => s.$?.['android:name'] === '.EngineKeepAlive');
    if (keepAlive?.$) {
      keepAlive.$['android:process'] = ':engine';
      keepAlive.$['android:stopWithTask'] = 'false';
      keepAlive.$['android:exported'] = 'false';
      keepAlive.$['android:foregroundServiceType'] = 'specialUse';
    } else {
      app.service.push({
        $: {
          'android:name': '.EngineKeepAlive',
          'android:exported': 'false',
          'android:process': ':engine',
          'android:stopWithTask': 'false',
          'android:foregroundServiceType': 'specialUse',
        },
      });
    }

    const recentsGuard = app.service.find((s) => s.$?.['android:name'] === '.RecentsGuard');
    if (recentsGuard?.$) {
      recentsGuard.$['android:stopWithTask'] = 'false';
      recentsGuard.$['android:exported'] = 'false';
    } else {
      app.service.push({
        $: {
          'android:name': '.RecentsGuard',
          'android:exported': 'false',
          'android:stopWithTask': 'false',
        },
      });
    }

    const hasA11y = app.service.some((s) => s.$?.['android:name'] === '.AutoClickerService');
    if (!hasA11y) {
      app.service.push({
        $: {
          'android:name': '.AutoClickerService',
          'android:exported': 'true',
          'android:label': '@string/app_name',
          'android:permission': 'android.permission.BIND_ACCESSIBILITY_SERVICE',
          'android:foregroundServiceType': 'specialUse',
          'android:process': ':engine',
          'android:stopWithTask': 'false',
        },
        'intent-filter': [
          {
            action: [
              { $: { 'android:name': 'android.accessibilityservice.AccessibilityService' } },
            ],
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
          'android:label': 'AG rider alerts',
          'android:permission': 'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE',
          'android:exported': 'true',
          'android:process': ':engine',
          'android:stopWithTask': 'false',
        },
        'intent-filter': [
          {
            action: [
              {
                $: {
                  'android:name': 'android.service.notification.NotificationListenerService',
                },
              },
            ],
          },
        ],
      });
    }

    app.receiver = app.receiver ?? [];
    const bootActions = [
      'android.intent.action.BOOT_COMPLETED',
      'android.intent.action.QUICKBOOT_POWERON',
      'android.intent.action.MY_PACKAGE_REPLACED',
    ];
    const bootReceiver = app.receiver.find((r) => r.$?.['android:name'] === '.BootReceiver');
    if (!bootReceiver) {
      app.receiver = [
        ...app.receiver,
        {
          $: {
            'android:name': '.BootReceiver',
            'android:exported': 'true',
            'android:enabled': 'true',
          },
          'intent-filter': [
            {
              action: bootActions.map((name) => ({ $: { 'android:name': name } })),
            },
          ],
        },
      ];
    } else {
      bootReceiver['intent-filter'] = [
        {
          action: bootActions.map((name) => ({ $: { 'android:name': name } })),
        },
      ];
    }

    app.provider = app.provider ?? [];
    const hasShizuku = app.provider.some(
      (p) => p.$?.['android:name'] === 'rikka.shizuku.ShizukuProvider'
    );
    if (!hasShizuku) {
      app.provider.push({
        $: {
          'android:name': 'rikka.shizuku.ShizukuProvider',
          'android:authorities': `${APP_PACKAGE}.shizuku`,
          'android:multiprocess': 'false',
          'android:enabled': 'true',
          'android:exported': 'true',
          'android:permission': 'android.permission.INTERACT_ACROSS_USERS_FULL',
        },
      });
    }

    const manifest = mod.modResults.manifest;
    if (manifest) {
      const uses = manifest['uses-permission'] ?? [];
      const overlayName = 'android.permission.SYSTEM_ALERT_WINDOW';
      const hasOverlay = uses.some((p) => p.$?.['android:name'] === overlayName);
      if (!hasOverlay) {
        uses.push({ $: { 'android:name': overlayName } });
        manifest['uses-permission'] = uses;
      }
      if (!manifest.queries) manifest.queries = [{}];
      if (!manifest.queries[0]) manifest.queries[0] = {};
      const q = manifest.queries[0];
      q.package = q.package ?? [];
      for (const pkg of [SHIZUKU_PKG, ...TARGET_PACKAGES]) {
        const exists = q.package.some((p) => p.$?.['android:name'] === pkg);
        if (!exists) {
          q.package.push({ $: { 'android:name': pkg } });
        }
      }
    }

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
        _: 'AG rider finds the Accept button in the driver app when a ride alert is live. Rapido is tapped with Accessibility. Ola taps use Shizuku from the Play Store.',
      },
    ];
    return mod;
  });
}

function findMainApplication(platformRoot) {
  const candidates = [
    path.join(platformRoot, JAVA_DIR, 'MainApplication.kt'),
    path.join(platformRoot, JAVA_DIR, 'MainApplication.java'),
    path.join(platformRoot, 'app/src/main/java/com/ridio/app/MainApplication.kt'),
    path.join(platformRoot, 'app/src/main/java/com/playnix/app/MainApplication.kt'),
    path.join(platformRoot, 'app/src/main/java/com/rapidotap/app/MainApplication.kt'),
  ];
  for (const p of candidates) {
    if (fs.existsSync(p)) return p;
  }
  // Fallback: search
  const javaRoot = path.join(platformRoot, 'app/src/main/java');
  if (!fs.existsSync(javaRoot)) return null;
  const stack = [javaRoot];
  while (stack.length) {
    const dir = stack.pop();
    for (const name of fs.readdirSync(dir)) {
      const full = path.join(dir, name);
      const st = fs.statSync(full);
      if (st.isDirectory()) stack.push(full);
      else if (name === 'MainApplication.kt' || name === 'MainApplication.java') return full;
    }
  }
  return null;
}

function patchMainApplication(platformRoot) {
  const mainAppPath = findMainApplication(platformRoot);
  if (!mainAppPath) return;

  let contents = fs.readFileSync(mainAppPath, 'utf8');
  let changed = false;

  if (!contents.includes('AutoClickerPackage()')) {
    contents = contents.replace(
      /PackageList\(this\)\.packages\.apply\s*\{[^}]*\}/,
      `PackageList(this).packages.apply {\n          add(AutoClickerPackage())\n        }`
    );
    changed = true;
    console.log(`[withAutoClicker] registered AutoClickerPackage in ${path.relative(platformRoot, mainAppPath)}`);
  }

  if (!contents.includes('ShizukuProvider.requestBinderForNonProviderProcess')) {
    if (mainAppPath.endsWith('.kt')) {
      if (!contents.includes('import rikka.shizuku.ShizukuProvider')) {
        contents = contents.replace(/(package [^\n]+\n)/, '$1\nimport rikka.shizuku.ShizukuProvider\n');
      }
      contents = contents.replace(
        'super.onCreate()',
        'super.onCreate()\n    ShizukuProvider.requestBinderForNonProviderProcess(this)'
      );
      changed = true;
    }
  }

  if (changed) fs.writeFileSync(mainAppPath, contents);
}

function copyBrandIcons(projectRoot, platformRoot) {
  const srcRoot = path.join(projectRoot, 'assets', 'AppIcons', 'android');
  const iconPng = path.join(projectRoot, 'assets', 'app_icon.png');
  const resRoot = path.join(platformRoot, 'app', 'src', 'main', 'res');
  if (!fs.existsSync(srcRoot) && !fs.existsSync(iconPng)) {
    console.warn('[withAutoClicker] missing app icon — skip icon sync');
    return;
  }

  const densities = [
    'mipmap-mdpi',
    'mipmap-hdpi',
    'mipmap-xhdpi',
    'mipmap-xxhdpi',
    'mipmap-xxxhdpi',
  ];

  for (const folder of [...densities, 'mipmap-anydpi-v26', 'drawable']) {
    const dir = path.join(resRoot, folder);
    if (!fs.existsSync(dir)) continue;
    for (const file of fs.readdirSync(dir)) {
      if (/^ic_launcher/i.test(file) || file === 'splash_full.png') {
        fs.unlinkSync(path.join(dir, file));
      }
    }
  }

  let copied = 0;
  const fallbackIcon = fs.existsSync(iconPng) ? iconPng : null;
  for (const density of densities) {
    const fromDir = path.join(srcRoot, density);
    const toDir = path.join(resRoot, density);
    fs.mkdirSync(toDir, { recursive: true });
    const launcher = path.join(fromDir, 'ic_launcher.png');
    const src = fs.existsSync(launcher) ? launcher : fallbackIcon;
    if (src) {
      fs.copyFileSync(src, path.join(toDir, 'ic_launcher.png'));
      fs.copyFileSync(src, path.join(toDir, 'ic_launcher_round.png'));
      copied += 2;
    }
  }

  const drawableDir = path.join(resRoot, 'drawable');
  fs.mkdirSync(drawableDir, { recursive: true });
  fs.writeFileSync(
    path.join(drawableDir, 'ag_rider_icon_background.xml'),
    `<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#041109" android:pathData="M0,0h108v108h-108z"/>
</vector>
`
  );

  const fgSrc = fs.existsSync(path.join(srcRoot, 'adaptive-foreground.png'))
    ? path.join(srcRoot, 'adaptive-foreground.png')
    : fallbackIcon;
  if (fgSrc) {
    const toDir = path.join(resRoot, 'mipmap-xxxhdpi');
    fs.mkdirSync(toDir, { recursive: true });
    fs.copyFileSync(fgSrc, path.join(toDir, 'ic_launcher_foreground.png'));
    copied++;
  }

  const anydpiTo = path.join(resRoot, 'mipmap-anydpi-v26');
  fs.mkdirSync(anydpiTo, { recursive: true });
  const adaptiveXml = `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ag_rider_icon_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
</adaptive-icon>
`;
  fs.writeFileSync(path.join(anydpiTo, 'ic_launcher.xml'), adaptiveXml);
  fs.writeFileSync(path.join(anydpiTo, 'ic_launcher_round.xml'), adaptiveXml);

  console.log(`[withAutoClicker] synced ${copied} icons from app_icon`);
}

/**
 * Keep the AG rider wordmark as the Android 12+ circular splash icon.
 * Expo writes splashscreen_logo first; we overwrite it with the branded PNG.
 */
function installBrandSplash(projectRoot, platformRoot) {
  const splashSrc = path.join(projectRoot, 'assets', 'splash-ag-rider.png');
  if (!fs.existsSync(splashSrc)) {
    console.warn('[withAutoClicker] missing splash-ag-rider.png — skip splash install');
    return;
  }

  const resRoot = path.join(platformRoot, 'app', 'src', 'main', 'res');
  const densityDirs = [
    'drawable',
    'drawable-mdpi',
    'drawable-hdpi',
    'drawable-xhdpi',
    'drawable-xxhdpi',
    'drawable-xxxhdpi',
    'drawable-night',
    'drawable-night-mdpi',
    'drawable-night-hdpi',
    'drawable-night-xhdpi',
    'drawable-night-xxhdpi',
    'drawable-night-xxxhdpi',
  ];

  for (const dir of densityDirs) {
    const destDir = path.join(resRoot, dir);
    if (!fs.existsSync(destDir) && dir !== 'drawable') continue;
    fs.mkdirSync(destDir, { recursive: true });
    const xml = path.join(destDir, 'splashscreen_logo.xml');
    if (fs.existsSync(xml)) fs.unlinkSync(xml);
    fs.copyFileSync(splashSrc, path.join(destDir, 'splashscreen_logo.png'));
  }

  const drawableDir = path.join(resRoot, 'drawable');
  const splashFull = path.join(drawableDir, 'splash_full.png');
  if (fs.existsSync(splashFull)) fs.unlinkSync(splashFull);
  console.log('[withAutoClicker] installed AG rider splashscreen_logo');
}

function patchReleaseSize(platformRoot) {
  const propsPath = path.join(platformRoot, 'gradle.properties');
  if (fs.existsSync(propsPath)) {
    let props = fs.readFileSync(propsPath, 'utf8');
    const setProp = (key, value) => {
      const re = new RegExp(`^${key}=.*$`, 'm');
      if (re.test(props)) props = props.replace(re, `${key}=${value}`);
      else props += `\n${key}=${value}\n`;
    };
    setProp('reactNativeArchitectures', 'armeabi-v7a,arm64-v8a');
    setProp('android.enableMinifyInReleaseBuilds', 'true');
    setProp('android.enableShrinkResourcesInReleaseBuilds', 'true');
    setProp('expo.gif.enabled', 'false');
    setProp('expo.webp.animated', 'false');
    setProp('android.enableBundleCompression', 'true');
    fs.writeFileSync(propsPath, props);
    console.log('[withAutoClicker] gradle.properties: phone ABIs + minify/shrink');
  }

  const gradlePath = path.join(platformRoot, 'app/build.gradle');
  if (!fs.existsSync(gradlePath)) return;
  let contents = fs.readFileSync(gradlePath, 'utf8');
  if (!contents.includes('dev.rikka.shizuku:api')) {
    contents = contents.replace(
      /dependencies\s*\{/,
      `dependencies {\n    implementation("dev.rikka.shizuku:api:13.1.5")\n    implementation("dev.rikka.shizuku:provider:13.1.5")\n    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")`
    );
    console.log('[withAutoClicker] app/build.gradle: Shizuku + HiddenApiBypass');
  }

  // One universal APK — strip any per-ABI split blocks, then ensure splits are off
  if (/splits\s*\{\s*abi\s*\{/.test(contents)) {
    contents = contents.replace(
      /(?:\/\/[^\n]*\n\s*)?splits\s*\{\s*abi\s*\{[\s\S]*?\}\s*\}/,
      UNIVERSAL_APK_BLOCK.trim()
    );
    console.log('[withAutoClicker] app/build.gradle: universal APK (ABI splits off)');
  } else {
    contents = contents.replace(
      /android\s*\{/,
      (m) => `${m}\n${UNIVERSAL_APK_BLOCK}`
    );
    console.log('[withAutoClicker] app/build.gradle: universal APK block added');
  }

  if (!contents.includes('abiFilters "armeabi-v7a", "arm64-v8a"')) {
    contents = contents.replace(
      /(defaultConfig\s*\{[\s\S]*?buildConfigField[^\n]+\n)/,
      (m) => `${m}${NDK_ABI_FILTERS_BLOCK}`
    );
    console.log('[withAutoClicker] app/build.gradle: ndk abiFilters for universal APK');
  }

  fs.writeFileSync(gradlePath, contents);
}

function withAutoClickerSources(config) {
  return withDangerousMod(config, [
    'android',
    async (config) => {
      const root = config.modRequest.projectRoot;
      const androidRoot = config.modRequest.platformProjectRoot;
      copyNativeSources(root, androidRoot);
      copyBrandIcons(root, androidRoot);
      installBrandSplash(root, androidRoot);
      patchMainApplication(androidRoot);
      patchReleaseSize(androidRoot);
      return config;
    },
  ]);
}

function withAutoClicker(config) {
  config = withAutoClickerManifest(config);
  config = withAutoClickerStrings(config);
  config = withAutoClickerSources(config);
  return config;
}

module.exports = withAutoClicker;
