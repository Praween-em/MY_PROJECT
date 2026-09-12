import React from 'react';
import { View, Text, TouchableOpacity, StyleSheet } from 'react-native';
import { colors } from '../theme/colors';

/**
 * Catches render errors so a single screen failure does not take down the app.
 */
export default class ErrorBoundary extends React.Component {
  state = { hasError: false };

  static getDerivedStateFromError() {
    return { hasError: true };
  }

  componentDidCatch(error) {
    console.warn('ErrorBoundary:', error?.message || error);
  }

  render() {
    if (!this.state.hasError) return this.props.children;
    return (
      <View style={styles.root}>
        <Text style={styles.title}>Something went wrong</Text>
        <Text style={styles.sub}>The last screen hit an unexpected error. You can continue using the app.</Text>
        <TouchableOpacity
          style={styles.btn}
          onPress={() => this.setState({ hasError: false })}
          activeOpacity={0.85}
        >
          <Text style={styles.btnText}>Continue</Text>
        </TouchableOpacity>
      </View>
    );
  }
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    backgroundColor: colors.background,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 28,
    gap: 12,
  },
  title: { fontSize: 24, fontWeight: '800', color: colors.icy, textAlign: 'center', letterSpacing: -0.4 },
  sub: { fontSize: 14, color: colors.icyDim, textAlign: 'center', lineHeight: 20 },
  btn: {
    marginTop: 8,
    backgroundColor: colors.purple,
    borderRadius: 999,
    paddingVertical: 14,
    paddingHorizontal: 32,
  },
  btnText: { fontSize: 15, fontWeight: '800', color: colors.background },
});
