import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { Platform } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../theme/colors';

import HomeScreen from '../screens/HomeScreen';
import ProfileScreen from '../screens/ProfileScreen';
import SettingsScreen from '../screens/SettingsScreen';

const Tab = createBottomTabNavigator();

export default function MainTabs() {
  const insets = useSafeAreaInsets();
  const tabBarPaddingBottom = Platform.OS === 'android'
    ? Math.max(insets.bottom, 16)
    : Math.max(insets.bottom, 8);

  return (
    <Tab.Navigator
      lazy
      detachInactiveScreens
      screenOptions={{
        headerShown: false,
        lazy: true,
        freezeOnBlur: true,
        tabBarStyle: {
          backgroundColor: colors.surface,
          borderTopWidth: 0,
          marginHorizontal: 14,
          marginBottom: 8,
          borderRadius: 26,
          height: 62 + tabBarPaddingBottom,
          paddingBottom: tabBarPaddingBottom,
          paddingTop: 8,
          paddingHorizontal: 8,
          borderWidth: 1,
          borderColor: colors.border,
          elevation: 12,
          shadowColor: colors.black,
          shadowOpacity: 0.28,
          shadowRadius: 16,
          shadowOffset: { width: 0, height: 8 },
        },
        tabBarActiveTintColor: colors.background,
        tabBarInactiveTintColor: colors.icyMuted,
        tabBarActiveBackgroundColor: colors.purple,
        tabBarItemStyle: {
          borderRadius: 18,
          marginHorizontal: 3,
          overflow: 'hidden',
        },
        tabBarLabelStyle: { fontSize: 10, fontWeight: '800', letterSpacing: 0.3 },
      }}
    >
      <Tab.Screen
        name="HomeTab"
        component={HomeScreen}
        options={{
          tabBarLabel: 'Home',
          tabBarIcon: ({ color, focused }) => (
            <Ionicons name={focused ? 'home' : 'home-outline'} size={20} color={color} />
          ),
        }}
      />
      <Tab.Screen
        name="ProfileTab"
        component={ProfileScreen}
        options={{
          tabBarLabel: 'Profile',
          tabBarIcon: ({ color, focused }) => (
            <Ionicons name={focused ? 'person' : 'person-outline'} size={20} color={color} />
          ),
        }}
      />
      <Tab.Screen
        name="SettingsTab"
        component={SettingsScreen}
        options={{
          tabBarLabel: 'Settings',
          tabBarIcon: ({ color, focused }) => (
            <Ionicons name={focused ? 'settings' : 'settings-outline'} size={20} color={color} />
          ),
        }}
      />
    </Tab.Navigator>
  );
}
