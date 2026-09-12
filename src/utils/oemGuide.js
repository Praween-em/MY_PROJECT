/**
 * OEM reliability instructions — menu names vary by OS version.
 * Status for Auto-start is usually not readable → "verify manually".
 */

export const OEM_GUIDES = {
  xiaomi: {
    title: 'Xiaomi / Redmi / POCO',
    autostartNote: 'Please verify Auto-start manually.',
    steps: [
      'Open Settings → Apps → Permissions → Autostart (or App launch).',
      'Enable Auto-start / allow background launch for AG rider.',
      'Open AG rider App info → Battery → No restrictions / Allow background.',
      'On HyperOS/MIUI, also check Battery saver / App battery saver → No restrictions.',
      'Keep Accessibility Service enabled for AG rider.',
      'Optional: lock AG rider in Recents so swipe-away is less aggressive.',
    ],
  },
  oppo: {
    title: 'OPPO (ColorOS)',
    autostartNote: 'Please verify Auto-start / Auto-launch manually.',
    steps: [
      'Open Settings → Apps → App management → AG rider.',
      'Enable Auto-launch / Auto-start if shown.',
      'Open Battery → Allow background activity (avoid aggressive restriction).',
      'Keep Accessibility Service enabled.',
      'Menu names differ by ColorOS version — look for Auto-launch / Battery.',
    ],
  },
  vivo: {
    title: 'Vivo (Funtouch / OriginOS)',
    autostartNote: 'Please verify Auto-start / background startup manually.',
    steps: [
      'Open Settings → Apps → AG rider.',
      'Open Battery → Allow background activity where available.',
      'Enable Auto-start / High background power consumption if shown.',
      'Keep Accessibility Service enabled.',
      'Funtouch/OriginOS names change by version — check App battery + Autostart.',
    ],
  },
  realme: {
    title: 'realme (realme UI)',
    autostartNote: 'Please verify Auto-launch / Startup manually.',
    steps: [
      'Open Settings → Apps → App management → AG rider.',
      'Battery usage → Allow background activity.',
      'Enable Auto-launch / Startup manager if available.',
      'Keep Accessibility Service enabled.',
    ],
  },
  oneplus: {
    title: 'OnePlus',
    autostartNote: 'Please verify Auto-launch manually.',
    steps: [
      'Open Settings → Apps → App management → AG rider.',
      'Battery → Allow background activity / Unrestricted where available.',
      'Enable Auto-launch if shown.',
      'Keep Accessibility Service enabled.',
    ],
  },
  samsung: {
    title: 'Samsung (One UI)',
    autostartNote: 'Check “Never sleeping apps” manually.',
    steps: [
      'Open Settings → Battery → Background usage limits.',
      'Add AG rider to Never sleeping apps.',
      'Remove it from Sleeping / Deep sleeping apps if listed.',
      'Avoid aggressive Power saving while online for rides.',
      'Keep Accessibility Service enabled.',
    ],
  },
  motorola: {
    title: 'Motorola',
    autostartNote: 'Please verify battery background setting manually.',
    steps: [
      'Open Settings → Apps → AG rider.',
      'App battery usage → Allow background / Unrestricted if needed.',
      'Check Battery optimization is not restricting the app.',
      'Keep Accessibility Service enabled.',
    ],
  },
  pixel: {
    title: 'Google Pixel / Stock Android',
    autostartNote: 'No OEM Auto-start menu — use App battery usage.',
    steps: [
      'Open Settings → Apps → AG rider → App battery usage.',
      'Choose Unrestricted if you need maximum background reliability.',
      'Note: Unrestricted can use more battery.',
      'Keep Accessibility Service enabled.',
    ],
  },
  generic: {
    title: 'Android (other OEM)',
    autostartNote: 'Please verify Auto-start / background manually.',
    steps: [
      'Open Settings → Apps → AG rider.',
      'Allow background activity / disable battery restriction where shown.',
      'Look for Autostart / Auto-launch if your phone has it.',
      'Keep Accessibility Service enabled.',
    ],
  },
};

export function getOemGuide(oemId) {
  return OEM_GUIDES[oemId] || OEM_GUIDES.generic;
}
