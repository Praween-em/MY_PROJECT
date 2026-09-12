/**
 * usePermissions.js
 * Polls critical Android permissions every time the app comes
 * to the foreground (via AppState). Exposes a simple status object.
 */

import { useState, useEffect, useCallback } from 'react';
import { AppState } from 'react-native';
import { getAllPermissionsStatus } from '../services/permissions';

const INITIAL = {
  accessibility: false,
  notificationListener: false,
  battery: false,
  overlay: false,
  shizuku: false,
  shizukuInstalled: false,
  shizukuRunning: false,
  shizukuPermission: false,
  shizukuState: 'missing',
};

export function usePermissions() {
  const [status, setStatus] = useState(INITIAL);
  const [loading, setLoading] = useState(true);

  const check = useCallback(async () => {
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
    check();

    // Re-check when user returns from the Settings screen
    const sub = AppState.addEventListener('change', (state) => {
      if (state === 'active') check();
    });
    return () => sub.remove();
  }, [check]);

  const allGranted =
    status.accessibility && status.battery;

  return { status, loading, check, allGranted };
}
