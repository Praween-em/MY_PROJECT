/**
 * usePermissions.js
 * Polls critical Android permissions every time the app comes
 * to the foreground (via AppState). Exposes a simple status object.
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { AppState } from 'react-native';
import { getAllPermissionsStatus } from '../services/permissions';

const INITIAL = {
  accessibility: false,
  accessibilityRunning: false,
  notificationListener: false,
  battery: false,
  overlay: false,
  shizuku: false,
  shizukuInstalled: false,
  shizukuRunning: false,
  shizukuPermission: false,
  shizukuState: 'missing',
};

const PERM_POLL_MIN_MS = 3000;

export function usePermissions() {
  const [status, setStatus] = useState(INITIAL);
  const [loading, setLoading] = useState(true);
  const lastCheckMs = useRef(0);

  const check = useCallback(async (force = false) => {
    const now = Date.now();
    if (!force && lastCheckMs.current > 0 && now - lastCheckMs.current < PERM_POLL_MIN_MS) {
      return;
    }
    lastCheckMs.current = now;
    try {
      const result = await getAllPermissionsStatus();
      setStatus(result);
    } catch {
      setStatus(INITIAL);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    check(true);

    // Re-check when user returns from the Settings screen
    const sub = AppState.addEventListener('change', (state) => {
      if (state === 'active') check(false);
    });
    return () => sub.remove();
  }, [check]);

  const allGranted =
    status.accessibility && status.battery;

  return { status, loading, check, allGranted };
}
