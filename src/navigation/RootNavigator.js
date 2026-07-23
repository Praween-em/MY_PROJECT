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
    getStoredUser()
      .then(user => setInitialRoute(resolveInitialRoute(user)))
      .catch(() => setInitialRoute('Login'));
  }, []);

  const hideNativeSplash = useCallback(async () => {
    if (splashHidden) return;
    try {
      await SplashScreen.hideAsync();
    } catch {
      // ignore — splash may already be gone
    } finally {
      setSplashHidden(true);
    }
  }, [splashHidden]);

  // Once the branded loading UI has laid out, hide the native splash under it.
  const onLoadingReady = useCallback(() => {
    hideNativeSplash();
  }, [hideNativeSplash]);

  if (!initialRoute) {
    return (
      <SafeAreaProvider>
        <LoadingScreen onReady={onLoadingReady} />
      </SafeAreaProvider>
    );
  }

  return (
    <SafeAreaProvider onLayout={hideNativeSplash}>
      <NavigationContainer>
        <Stack.Navigator
          initialRouteName={initialRoute}
          screenOptions={{ headerShown: false, animation: 'slide_from_right' }}
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
