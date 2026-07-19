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
      {children}
    </View>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
});
