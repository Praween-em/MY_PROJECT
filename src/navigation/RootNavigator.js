import React, { useEffect, useState } from 'react';
import { ActivityIndicator, StyleSheet } from 'react-native';
import { NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import Screen from '../components/Screen';

import LoginScreen from '../screens/LoginScreen';
import PlansScreen from '../screens/PlansScreen';
import PermissionsSetupScreen from '../screens/PermissionsSetupScreen';
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

  useEffect(() => {
    getStoredUser()
      .then(user => setInitialRoute(resolveInitialRoute(user)))
      .catch(() => setInitialRoute('Login'));
  }, []);

  if (!initialRoute) {
    return (
      <SafeAreaProvider>
        <Screen style={styles.boot}>
          <ActivityIndicator size="large" color={colors.purple} />
        </Screen>
      </SafeAreaProvider>
    );
  }

  return (
    <SafeAreaProvider>
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

const styles = StyleSheet.create({
  boot: {
    alignItems: 'center',
    justifyContent: 'center',
  },
});
