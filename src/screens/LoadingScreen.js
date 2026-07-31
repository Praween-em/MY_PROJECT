import React, { useEffect, useRef } from 'react';
import { View, Image, StyleSheet, StatusBar } from 'react-native';

/**
 * Launch frame shown for at least 2 seconds on cold start.
 * Uses assets/splash-blank.png as the branded splash image.
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
    <View
      style={styles.root}
      onLayout={() => {
        if (sent.current) return;
        sent.current = true;
        onVisible?.();
        onReady?.();
      }}
    >
      <StatusBar barStyle="light-content" backgroundColor="#000000" translucent={false} />
      <Image
        source={require('../../assets/splash-blank.png')}
        style={styles.splash}
        resizeMode="contain"
        accessibilityLabel="SUPER RIDEX"
      />
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
  splash: {
    width: '100%',
    height: '100%',
  },
});
