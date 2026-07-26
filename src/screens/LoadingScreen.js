import React, { useEffect, useRef } from 'react';
import { View, Text, StyleSheet, StatusBar } from 'react-native';

/**
 * Lightweight launch frame — solid color + text (no multi‑MB bitmap decode).
 * Native splash is already black; this only bridges until the first route mounts.
 */
export default function LoadingScreen({ onVisible, onReady }) {
  const sent = useRef(false);

  useEffect(() => {
    if (sent.current) return;
    sent.current = true;
    onVisible?.();
    onReady?.();
  }, [onVisible, onReady]);

  return (
    <View style={styles.root} onLayout={() => {
      if (sent.current) return;
      sent.current = true;
      onVisible?.();
      onReady?.();
    }}
    >
      <StatusBar barStyle="light-content" backgroundColor="#000000" translucent={false} />
      <Text style={styles.brand}>SUPER RIDEX</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: '#000000',
    alignItems: 'center',
    justifyContent: 'center',
  },
  brand: {
    color: '#FFFFFF',
    fontSize: 22,
    fontWeight: '800',
    letterSpacing: 1.2,
  },
});
