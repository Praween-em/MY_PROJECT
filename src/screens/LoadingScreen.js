import React, { useEffect, useRef } from 'react';
import { View, Text, StyleSheet, StatusBar, Animated, ActivityIndicator } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../theme/colors';

/** Initial branded loading UI shown while the app resolves the first route. */
export default function LoadingScreen({ onReady }) {
  const fadeAnim = useRef(new Animated.Value(0)).current;
  const scaleAnim = useRef(new Animated.Value(0.92)).current;

  useEffect(() => {
    Animated.parallel([
      Animated.timing(fadeAnim, {
        toValue: 1,
        duration: 500,
        useNativeDriver: true,
      }),
      Animated.spring(scaleAnim, {
        toValue: 1,
        friction: 7,
        tension: 60,
        useNativeDriver: true,
      }),
    ]).start();
  }, [fadeAnim, scaleAnim]);

  return (
    <View style={styles.root} onLayout={onReady}>
      <StatusBar barStyle="dark-content" backgroundColor={colors.background} />

      <Animated.View
        style={[
          styles.content,
          { opacity: fadeAnim, transform: [{ scale: scaleAnim }] },
        ]}
      >
        <View style={styles.logoOrb}>
          <Ionicons name="car-sport" size={40} color={colors.white} />
        </View>

        <Text style={styles.appName}>SUPER RIDEX</Text>
        <Text style={styles.tagline}>Auto-accept rides in milliseconds</Text>

        <ActivityIndicator
          style={styles.spinner}
          size="small"
          color={colors.purple}
        />
      </Animated.View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: colors.background,
    alignItems: 'center',
    justifyContent: 'center',
  },
  content: {
    alignItems: 'center',
    paddingHorizontal: 32,
  },
  logoOrb: {
    width: 88,
    height: 88,
    borderRadius: 44,
    backgroundColor: colors.purple,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 20,
    borderWidth: 3,
    borderColor: colors.purpleBright,
  },
  appName: {
    fontSize: 28,
    fontWeight: '900',
    color: colors.purple,
    letterSpacing: 1.5,
    textAlign: 'center',
  },
  tagline: {
    marginTop: 8,
    fontSize: 13,
    fontWeight: '500',
    color: colors.icyDim,
    textAlign: 'center',
  },
  spinner: {
    marginTop: 28,
  },
});
