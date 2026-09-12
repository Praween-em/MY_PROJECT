import React from 'react';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import * as SplashScreen from 'expo-splash-screen';
import RootNavigator from './src/navigation/RootNavigator';
import ErrorBoundary from './src/components/ErrorBoundary';
import { colors } from './src/theme/colors';

// Keep the native splash visible until the JS loading UI is ready.
SplashScreen.preventAutoHideAsync().catch(() => {});

export default function App() {
  return (
    <GestureHandlerRootView style={{ flex: 1, backgroundColor: colors.background }}>
      <ErrorBoundary>
        <RootNavigator />
      </ErrorBoundary>
    </GestureHandlerRootView>
  );
}
