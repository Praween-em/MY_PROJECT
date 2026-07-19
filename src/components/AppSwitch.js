import React from 'react';
import { Switch } from 'react-native';
import { colors } from '../theme/colors';

/** ON = green, OFF = red — used app-wide for toggles */
export default function AppSwitch({ value, onValueChange, disabled }) {
  return (
    <Switch
      value={value}
      onValueChange={onValueChange}
      disabled={disabled}
      trackColor={{ false: colors.offRed, true: colors.onGreen }}
      thumbColor={colors.white}
      ios_backgroundColor={colors.offRed}
    />
  );
}
