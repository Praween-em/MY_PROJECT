import React, { useState } from 'react';
import {
  View, Text, TextInput, TouchableOpacity,
  StyleSheet, StatusBar, KeyboardAvoidingView,
  Platform,
} from 'react-native';
import Screen from '../components/Screen';
import { colors } from '../theme/colors';
import { saveUser } from '../utils/storage';
import { replaceRoot } from '../navigation/rootNavigation';
import { registerUser } from '../services/api';

export default function LoginScreen({ navigation }) {
  const [phone, setPhone] = useState('');
  const [otp, setOtp] = useState('');
  const [referralCode, setReferralCode] = useState('');
  const [step, setStep] = useState('phone');

  return (
    <Screen>
      <StatusBar barStyle="light-content" backgroundColor={colors.background} />
      <KeyboardAvoidingView
        style={styles.flex}
        behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
      >
        <View style={styles.container}>

          {/* Logo */}
          <View style={styles.logoWrap}>
            <View style={styles.logoOrb}>
              <Text style={styles.logoGlyph}>⚡</Text>
            </View>
            <Text style={styles.logoName}>PLAYNIX</Text>
            <Text style={styles.logoTag}>Auto-accept rides in milliseconds</Text>
          </View>

          {/* Card */}
          <View style={styles.card}>
            {/* Step indicator */}
            <View style={styles.steps}>
              <View style={[styles.stepDot, styles.stepDotActive]} />
              <View style={[styles.stepLine, step === 'otp' && styles.stepLineActive]} />
              <View style={[styles.stepDot, step === 'otp' && styles.stepDotActive]} />
            </View>

            {step === 'phone' ? (
              <>
                <Text style={styles.cardTitle}>Enter Mobile Number</Text>
                <Text style={styles.cardSub}>We'll send you a one-time verification code</Text>

                <View style={styles.phoneRow}>
                  <View style={styles.flagBox}>
                    <Text style={styles.flagText}>🇮🇳 +91</Text>
                  </View>
                  <TextInput
                    style={styles.input}
                    placeholder="10-digit number"
                    placeholderTextColor={colors.icyMuted}
                    keyboardType="phone-pad"
                    maxLength={10}
                    value={phone}
                    onChangeText={setPhone}
                  />
                </View>

                <Text style={styles.refLabel}>Referral Code (optional)</Text>
                <TextInput
                  style={styles.input}
                  placeholder="e.g. PC1234ABC"
                  placeholderTextColor={colors.icyMuted}
                  autoCapitalize="characters"
                  value={referralCode}
                  onChangeText={setReferralCode}
                />

                <TouchableOpacity
                  style={[styles.btn, phone.length !== 10 && styles.btnDisabled]}
                  onPress={() => phone.length === 10 && setStep('otp')}
                  activeOpacity={0.8}
                >
                  <Text style={styles.btnText}>Send OTP →</Text>
                </TouchableOpacity>
              </>
            ) : (
              <>
                <Text style={styles.cardTitle}>Verify OTP</Text>
                <Text style={styles.cardSub}>
                  Sent to +91 {phone}{'  '}
                  <Text style={styles.link} onPress={() => setStep('phone')}>Change</Text>
                </Text>

                <TextInput
                  style={[styles.input, styles.otpInput]}
                  placeholder="· · · · · ·"
                  placeholderTextColor={colors.icyMuted}
                  keyboardType="number-pad"
                  maxLength={6}
                  value={otp}
                  onChangeText={setOtp}
                  autoFocus
                />

                <TouchableOpacity
                  style={[styles.btn, otp.length !== 6 && styles.btnDisabled]}
                  onPress={async () => {
                    if (otp.length !== 6) return;
                    let serverReferralCode = null;
                    try {
                      const reg = await registerUser(phone, referralCode.trim() || undefined);
                      serverReferralCode = reg.referralCode;
                    } catch {
                      // Continue offline if backend unavailable
                    }
                    await saveUser({
                      phone,
                      active: false,
                      referralCode: serverReferralCode,
                      appliedReferralCode: referralCode.trim() || null,
                    });
                    replaceRoot(navigation, 'Main');
                  }}
                  activeOpacity={0.8}
                >
                  <Text style={styles.btnText}>Verify & Continue →</Text>
                </TouchableOpacity>

                <TouchableOpacity style={styles.resendRow}>
                  <Text style={styles.cardSub}>Didn't receive?  </Text>
                  <Text style={styles.link}>Resend OTP</Text>
                </TouchableOpacity>
              </>
            )}
          </View>

          <Text style={styles.terms}>By continuing you agree to our Terms of Service</Text>
        </View>
      </KeyboardAvoidingView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  flex:    { flex: 1 },
  container: {
    flex: 1,
    paddingHorizontal: 24,
    justifyContent: 'center',
    gap: 28,
  },

  // Logo
  logoWrap:  { alignItems: 'center', gap: 10 },
  logoOrb: {
    width: 80, height: 80, borderRadius: 40,
    backgroundColor: colors.surface,
    borderWidth: 2, borderColor: colors.borderPurple,
    alignItems: 'center', justifyContent: 'center',
    shadowColor: colors.purple, shadowOpacity: 0.6,
    shadowRadius: 20, elevation: 12,
  },
  logoGlyph:  { fontSize: 36 },
  logoName: {
    fontSize: 22, fontWeight: '900', color: colors.icy,
    letterSpacing: 3,
  },
  logoTag:    { fontSize: 13, color: colors.icyDim },

  // Card
  card: {
    backgroundColor: colors.surface,
    borderRadius: 24,
    padding: 24,
    borderWidth: 1,
    borderColor: colors.borderPurple,
    gap: 16,
    shadowColor: colors.purple,
    shadowOpacity: 0.25,
    shadowRadius: 24,
    elevation: 10,
  },

  // Step indicator
  steps:       { flexDirection: 'row', alignItems: 'center', gap: 6, marginBottom: 4 },
  stepDot: {
    width: 10, height: 10, borderRadius: 5,
    backgroundColor: colors.border,
  },
  stepDotActive: { backgroundColor: colors.purple },
  stepLine:    { flex: 1, height: 2, backgroundColor: colors.border, borderRadius: 2 },
  stepLineActive: { backgroundColor: colors.purple },

  cardTitle: { fontSize: 20, fontWeight: '800', color: colors.icy },
  cardSub:   { fontSize: 13, color: colors.icyDim },

  // Phone row
  phoneRow:  { flexDirection: 'row', gap: 10 },
  flagBox: {
    backgroundColor: colors.surfaceLight,
    borderRadius: 12, borderWidth: 1, borderColor: colors.border,
    paddingHorizontal: 14, justifyContent: 'center',
  },
  flagText:  { color: colors.icy, fontSize: 14 },
  refLabel:  { fontSize: 12, color: colors.icyDim, fontWeight: '600' },

  // Input
  input: {
    flex: 1,
    backgroundColor: colors.surfaceLight,
    borderRadius: 12, borderWidth: 1, borderColor: colors.border,
    paddingHorizontal: 16, paddingVertical: 14,
    color: colors.icy, fontSize: 16,
  },
  otpInput: {
    flex: 0, textAlign: 'center', letterSpacing: 10,
    fontSize: 24, fontWeight: '700',
  },

  // Button
  btn: {
    backgroundColor: colors.purple,
    borderRadius: 14, paddingVertical: 16,
    alignItems: 'center',
    shadowColor: colors.purple,
    shadowOpacity: 0.5, shadowRadius: 12, elevation: 8,
  },
  btnDisabled: {
    backgroundColor: colors.surfaceLight,
    borderWidth: 1, borderColor: colors.border,
    shadowOpacity: 0,
  },
  btnText: { color: colors.white, fontSize: 16, fontWeight: '800' },

  resendRow:   { flexDirection: 'row', justifyContent: 'center' },
  link:        { color: colors.blue, fontSize: 13, fontWeight: '700' },
  terms:       { textAlign: 'center', color: colors.icyMuted, fontSize: 12 },
});
