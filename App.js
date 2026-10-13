import React from 'react';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import * as SplashScreen from 'expo-splash-screen';
import RootNavigator from './src/navigation/RootNavigator';
import ErrorBoundary from './src/components/ErrorBoundary';
import { colors } from './src/theme/colors';

// Keep the native splash up until the first real screen has laid out.
SplashScreen.preventAutoHideAsync().catch(() => {});
SplashScreen.setOptions({ duration: 280, fade: true });

export default function App() {
  return (
    <GestureHandlerRootView style={{ flex: 1, backgroundColor: colors.background }}>
      <ErrorBoundary>
        <RootNavigator />
      </ErrorBoundary>
    </GestureHandlerRootView>
  );
}
