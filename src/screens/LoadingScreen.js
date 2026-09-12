import React, { useEffect } from 'react';
import { View, Image, Text, StyleSheet, StatusBar } from 'react-native';
import { colors } from '../theme/colors';

/** Branded launch frame while session is read. Native splash is hidden immediately. */
export default function LoadingScreen({ onReady }) {
  useEffect(() => {
    onReady?.();
  }, [onReady]);

  return (
    <View style={styles.root} onLayout={() => onReady?.()}>
      <StatusBar barStyle="light-content" backgroundColor={colors.background} translucent={false} />
      <View pointerEvents="none" style={styles.topRing} />
      <View pointerEvents="none" style={styles.topHalo} />
      <View pointerEvents="none" style={styles.bottomRing} />
      <View pointerEvents="none" style={styles.bottomHalo} />
      <View style={styles.ring}>
        <Image
          source={require('../../assets/app_icon.png')}
          style={styles.mark}
          resizeMode="contain"
          accessibilityLabel="AG rider"
        />
      </View>
      <Text style={styles.brand}>AG rider</Text>
      <Text style={styles.tagline}>DRIVER ASSIST</Text>
      <View style={styles.bar} />
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: colors.background,
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden',
  },
  topRing: {
    position: 'absolute',
    width: 320,
    height: 320,
    borderRadius: 160,
    top: -198,
    right: -86,
    borderWidth: 1.5,
    borderColor: colors.borderPurple,
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
  bottomRing: {
    position: 'absolute',
    width: 280,
    height: 280,
    borderRadius: 140,
    right: -96,
    bottom: -132,
    borderWidth: 1.5,
    borderColor: colors.gold + '40',
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
  ring: {
    width: 148,
    height: 148,
    borderRadius: 74,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.surfaceCard,
    borderWidth: 2,
    borderColor: colors.borderPurple,
  },
  mark: {
    width: 108,
    height: 108,
    borderRadius: 32,
  },
  brand: {
    marginTop: 22,
    color: colors.icy,
    fontSize: 34,
    fontWeight: '800',
    letterSpacing: -0.8,
  },
  tagline: {
    marginTop: 6,
    color: colors.icyMuted,
    fontSize: 11,
    fontWeight: '800',
    letterSpacing: 2.4,
  },
  bar: {
    width: 48,
    height: 3,
    borderRadius: 2,
    backgroundColor: colors.purple,
    marginTop: 18,
  },
});
