import React, { useEffect, useState } from 'react';
import { DarkTheme, NavigationContainer } from '@react-navigation/native';
import { navigationRef } from './rootNavigation';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import LoginScreen from '../screens/LoginScreen';
import LoadingScreen from '../screens/LoadingScreen';
import PlansScreen from '../screens/PlansScreen';
import PermissionsSetupScreen from '../screens/PermissionsSetupScreen';
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

const rootStyle = { flex: 1, backgroundColor: colors.background };

export default function RootNavigator() {
  const [initialRoute, setInitialRoute] = useState(null);

  useEffect(() => {
    let cancelled = false;
    const finish = (route) => {
      if (!cancelled) setInitialRoute((current) => current || route);
    };
    getStoredUser()
      .then((user) => finish(resolveInitialRoute(user)))
      .catch(() => finish('Login'));
    const fallback = setTimeout(() => finish('Login'), 700);
    return () => {
      cancelled = true;
      clearTimeout(fallback);
    };
  }, []);

  if (!initialRoute) {
    return (
      <SafeAreaProvider style={rootStyle}>
        <LoadingScreen />
      </SafeAreaProvider>
    );
  }

  return (
    <SafeAreaProvider style={rootStyle}>
      <NavigationContainer
        ref={navigationRef}
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
            animation: 'slide_from_right',
            animationDuration: 220,
            gestureEnabled: true,
            contentStyle: { backgroundColor: colors.background },
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
