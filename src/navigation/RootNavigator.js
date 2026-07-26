import React, { useEffect, useState, useCallback } from 'react';
import { NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import * as SplashScreen from 'expo-splash-screen';

import LoginScreen from '../screens/LoginScreen';
import PlansScreen from '../screens/PlansScreen';
import PermissionsSetupScreen from '../screens/PermissionsSetupScreen';
import LoadingScreen from '../screens/LoadingScreen';
import MainTabs from './MainTabs';
import { getStoredUser } from '../utils/storage';

const Stack = createNativeStackNavigator();

function resolveInitialRoute(user) {
  if (!user?.phone) return 'Login';
  return 'Main';
}

export default function RootNavigator() {
  const [initialRoute, setInitialRoute] = useState(null);
  const [splashHidden, setSplashHidden] = useState(false);

  useEffect(() => {
    let cancelled = false;
    getStoredUser()
      .then((user) => {
        if (!cancelled) setInitialRoute(resolveInitialRoute(user));
      })
      .catch(() => {
        if (!cancelled) setInitialRoute('Login');
      });
    // Never block launch on AsyncStorage / slow flash storage
    const fallback = setTimeout(() => {
      if (!cancelled) setInitialRoute((r) => r || 'Login');
    }, 800);
    return () => {
      cancelled = true;
      clearTimeout(fallback);
    };
  }, []);

  const hideNativeSplash = useCallback(async () => {
    if (splashHidden) return;
    try {
      await SplashScreen.hideAsync();
    } catch {
      // ignore
    } finally {
      setSplashHidden(true);
    }
  }, [splashHidden]);

  // Hide native splash ASAP — long SplashScreenManager loops starve a11y on ColorOS
  useEffect(() => {
    hideNativeSplash();
    const t = setTimeout(hideNativeSplash, 200);
    return () => clearTimeout(t);
  }, [hideNativeSplash]);

  // Brief branded frame only until route is known (no 2.5s image gate)
  if (!initialRoute) {
    return (
      <SafeAreaProvider style={{ flex: 1, backgroundColor: '#000000' }}>
        <LoadingScreen onVisible={hideNativeSplash} onReady={hideNativeSplash} />
      </SafeAreaProvider>
    );
  }

  return (
    <SafeAreaProvider onLayout={hideNativeSplash}>
      <NavigationContainer>
        <Stack.Navigator
          initialRouteName={initialRoute}
          screenOptions={{
            headerShown: false,
            animation: 'fade',
            animationDuration: 150,
          }}
        >
          <Stack.Screen name="Login" component={LoginScreen} />
          <Stack.Screen name="Plans" component={PlansScreen} />
          <Stack.Screen name="PermissionsSetup" component={PermissionsSetupScreen} />
          <Stack.Screen name="Main" component={MainTabs} />
        </Stack.Navigator>
      </NavigationContainer>
    </SafeAreaProvider>
  );
}
