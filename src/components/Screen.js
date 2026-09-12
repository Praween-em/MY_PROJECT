import React from 'react';
import { Platform, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { colors } from '../theme/colors';

/** Fallback when Android reports 0 bottom inset (3-button nav bar). */
const ANDROID_NAV_FALLBACK = 28;

/**
 * Wraps screen content with safe-area padding.
 * Use edges={['top']} on tab screens — bottom inset is handled by the tab bar.
 */
export default function Screen({ children, style, edges = ['top', 'bottom'] }) {
  const insets = useSafeAreaInsets();

  const paddingTop = edges.includes('top') ? insets.top : 0;
  const wantsBottom = edges.includes('bottom');
  const rawBottom = wantsBottom ? insets.bottom : 0;
  const paddingBottom = wantsBottom && Platform.OS === 'android'
    ? Math.max(rawBottom, ANDROID_NAV_FALLBACK)
    : rawBottom;

  return (
    <View style={[styles.safe, style, { paddingTop, paddingBottom }]}>
      <View pointerEvents="none" style={styles.decor}>
        <View style={styles.topRing} />
        <View style={styles.topHalo} />
        <View style={styles.topCore} />

        <View style={styles.bottomRing} />
        <View style={styles.bottomHalo} />
        <View style={styles.bottomCore} />
      </View>
      {children}
    </View>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background, overflow: 'hidden' },
  decor: {
    ...StyleSheet.absoluteFillObject,
  },

  // Clean semi-circle sitting on the top-right edge.
  topRing: {
    position: 'absolute',
    width: 320,
    height: 320,
    borderRadius: 160,
    top: -198,
    right: -86,
    borderWidth: 1.5,
    borderColor: colors.borderPurple,
    backgroundColor: 'transparent',
  },
  topHalo: {
    position: 'absolute',
    width: 236,
    height: 236,
    borderRadius: 118,
    top: -156,
    right: -44,
    backgroundColor: colors.purpleGlow,
  },
  topCore: {
    position: 'absolute',
    width: 112,
    height: 112,
    borderRadius: 56,
    top: -58,
    right: 18,
    backgroundColor: colors.purple + '18',
    borderWidth: 1,
    borderColor: colors.purple + '33',
  },

  // Clean semi-circle sitting on the bottom-right edge.
  bottomRing: {
    position: 'absolute',
    width: 280,
    height: 280,
    borderRadius: 140,
    right: -96,
    bottom: -132,
    borderWidth: 1.5,
    borderColor: colors.gold + '40',
    backgroundColor: 'transparent',
  },
  bottomHalo: {
    position: 'absolute',
    width: 196,
    height: 196,
    borderRadius: 98,
    right: -54,
    bottom: -90,
    backgroundColor: colors.goldGlow,
  },
  bottomCore: {
    position: 'absolute',
    width: 88,
    height: 88,
    borderRadius: 44,
    right: 0,
    bottom: -36,
    backgroundColor: colors.gold + '14',
    borderWidth: 1,
    borderColor: colors.gold + '2E',
  },
});
