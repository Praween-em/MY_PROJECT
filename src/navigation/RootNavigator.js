import React, { useEffect, useState, useCallback } from 'react';
import { DarkTheme, NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import * as SplashScreen from 'expo-splash-screen';

import LoginScreen from '../screens/LoginScreen';
import PlansScreen from '../screens/PlansScreen';
import PermissionsSetupScreen from '../screens/PermissionsSetupScreen';
import LoadingScreen from '../screens/LoadingScreen';
import PrivacyPolicyScreen from '../screens/PrivacyPolicyScreen';
import TermsScreen from '../screens/TermsScreen';
import MainTabs from './MainTabs';
import { getStoredUser } from '../utils/storage';
import { colors } from '../theme/colors';

const Stack = createNativeStackNavigator();

function resolveInitialRoute(user) {
  if (!user?.phone) return 'Login';
  return 'Main';
}

export default function RootNavigator() {
  const [initialRoute, setInitialRoute] = useState(null);

  const hideNativeSplash = useCallback(() => {
    SplashScreen.hideAsync().catch(() => {});
  }, []);

  useEffect(() => {
    let cancelled = false;
    getStoredUser()
      .then((user) => {
        if (!cancelled) setInitialRoute(resolveInitialRoute(user));
      })
      .catch(() => {
        if (!cancelled) setInitialRoute('Login');
      });
    const fallback = setTimeout(() => {
      if (!cancelled) setInitialRoute((r) => r || 'Login');
    }, 800);
    return () => {
      cancelled = true;
      clearTimeout(fallback);
    };
  }, []);

  useEffect(() => {
    hideNativeSplash();
  }, [hideNativeSplash]);

  if (!initialRoute) {
    return (
      <SafeAreaProvider style={{ flex: 1, backgroundColor: colors.background }}>
        <LoadingScreen onReady={hideNativeSplash} />
      </SafeAreaProvider>
    );
  }

  return (
    <SafeAreaProvider onLayout={hideNativeSplash}>
      <NavigationContainer
        theme={{
          ...DarkTheme,
          colors: {
            ...DarkTheme.colors,
            background: colors.background,
            card: colors.surface,
            text: colors.icy,
            border: colors.border,
            primary: colors.purple,
          },
        }}
      >
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
          <Stack.Screen name="PrivacyPolicy" component={PrivacyPolicyScreen} />
          <Stack.Screen name="Terms" component={TermsScreen} />
        </Stack.Navigator>
      </NavigationContainer>
    </SafeAreaProvider>
  );
}
