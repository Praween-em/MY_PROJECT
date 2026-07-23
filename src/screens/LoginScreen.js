import React, { useState, useEffect } from 'react';
import {
  View, Text, TextInput, TouchableOpacity,
  StyleSheet, StatusBar, KeyboardAvoidingView,
  Platform, Alert, ActivityIndicator,
} from 'react-native';
import Screen from '../components/Screen';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../theme/colors';
import { saveUser } from '../utils/storage';
import { replaceRoot } from '../navigation/rootNavigation';
import { registerUser, verifyOtpWithServer } from '../services/api';
import { initOtpWidget, isOtpConfigured, sendOtp, retryOtp, verifyOtp } from '../services/otp';

/** Pure black — readable on light fills even if the OS is in dark mode. */
const INPUT_TEXT = '#000000';
const INPUT_PLACEHOLDER = '#6B7280';

export default function LoginScreen({ navigation }) {
  const [phone, setPhone] = useState('');
  const [otp, setOtp] = useState('');
  const [referralCode, setReferralCode] = useState('');
  const [step, setStep] = useState('phone');
  const [reqId, setReqId] = useState(null);
  const [sending, setSending] = useState(false);
  const [verifying, setVerifying] = useState(false);
  const [resending, setResending] = useState(false);

  useEffect(() => {
    initOtpWidget();
  }, []);

  const handleSendOtp = async () => {
    if (phone.length !== 10) return;
    if (!isOtpConfigured()) {
      Alert.alert(
        'OTP Not Configured',
        'Add EXPO_PUBLIC_MSG91_WIDGET_ID and EXPO_PUBLIC_MSG91_AUTH_TOKEN to your .env, then restart Expo.'
      );
      return;
    }
    setSending(true);
    try {
      const result = await sendOtp(phone);
      // Invisible / already-verified path returns token immediately
      if (result.alreadyVerified && result.accessToken) {
        await finishLogin(result.accessToken);
        return;
      }
      setReqId(result.reqId);
      setOtp('');
      setStep('otp');
    } catch (err) {
      Alert.alert('Could not send OTP', err.message || 'Try again');
    } finally {
      setSending(false);
    }
  };

  const handleResendOtp = async () => {
    if (!reqId) {
      await handleSendOtp();
      return;
    }
    setResending(true);
    try {
      // Default widget channel — omit retryChannel when widget uses default SMS
      const { reqId: id } = await retryOtp(reqId);
      setReqId(id);
      Alert.alert('OTP resent', 'Check your SMS for a new code.');
    } catch (err) {
      Alert.alert('Resend failed', err.message || 'Try again');
    } finally {
      setResending(false);
    }
  };

  const finishLogin = async (accessToken) => {
    let serverReferralCode = null;
    try {
      if (accessToken) {
        const reg = await verifyOtpWithServer({
          phone,
          accessToken,
          referralCode: referralCode.trim() || undefined,
        });
        serverReferralCode = reg.referralCode;
      } else {
        const reg = await registerUser(phone, referralCode.trim() || undefined);
        serverReferralCode = reg.referralCode;
      }
    } catch (err) {
      // Device limit / blocked must stop login
      if (err?.code === 'DEVICE_LIMIT' || err?.code === 'BLOCKED') {
        throw err;
      }
      // If server OTP verify fails, do not continue
      if (accessToken) throw err;
      // Offline fallback only when no accessToken path
    }
    await saveUser({
      phone,
      active: false,
      referralCode: serverReferralCode,
      appliedReferralCode: referralCode.trim() || null,
    });
    replaceRoot(navigation, 'Main');
  };

  const handleVerifyOtp = async () => {
    if (otp.length < 4) return;
    setVerifying(true);
    try {
      const { accessToken } = await verifyOtp(reqId, otp);
      await finishLogin(accessToken);
    } catch (err) {
      Alert.alert('Verification failed', err.message || 'Invalid OTP');
    } finally {
      setVerifying(false);
    }
  };

  const busy = sending || verifying || resending;

  return (
    <Screen>
      <StatusBar barStyle="dark-content" backgroundColor={colors.background} />
      <KeyboardAvoidingView
        style={styles.flex}
        behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
      >
        <View style={styles.container}>

          <View style={styles.logoWrap}>
            <View style={styles.logoOrb}>
              <Ionicons name="car-sport-outline" size={36} color={colors.purpleBright} />
            </View>
            <Text style={styles.logoName}>SUPER RIDEX</Text>
            <Text style={styles.logoTag}>Auto-accept rides in milliseconds</Text>
          </View>

          <View style={styles.card}>
            <View style={styles.steps}>
              <View style={[styles.stepDot, styles.stepDotActive]} />
              <View style={[styles.stepLine, step === 'otp' && styles.stepLineActive]} />
              <View style={[styles.stepDot, step === 'otp' && styles.stepDotActive]} />
            </View>

            {step === 'phone' ? (
              <>
                <Text style={styles.cardTitle}>Enter Mobile Number</Text>
                <Text style={styles.cardSub}>We'll send you a one-time verification code via SMS</Text>

                <View style={styles.phoneRow}>
                  <View style={styles.flagBox}>
                    <Text style={styles.flagText}>🇮🇳 +91</Text>
                  </View>
                  <TextInput
                    style={styles.input}
                    placeholder="10-digit number"
                    placeholderTextColor={INPUT_PLACEHOLDER}
                    keyboardType="phone-pad"
                    keyboardAppearance="light"
                    maxLength={10}
                    value={phone}
                    onChangeText={setPhone}
                    editable={!busy}
                    cursorColor={INPUT_TEXT}
                    selectionColor={colors.purpleBright}
                    underlineColorAndroid="transparent"
                    textAlignVertical="center"
                  />
                </View>

                <Text style={styles.refLabel}>Referral Code (optional)</Text>
                <TextInput
                  style={styles.inputStandalone}
                  placeholder="e.g. PC1234ABC"
                  placeholderTextColor={INPUT_PLACEHOLDER}
                  autoCapitalize="characters"
                  keyboardAppearance="light"
                  value={referralCode}
                  onChangeText={setReferralCode}
                  editable={!busy}
                  cursorColor={INPUT_TEXT}
                  selectionColor={colors.purpleBright}
                  underlineColorAndroid="transparent"
                  textAlignVertical="center"
                />

                <TouchableOpacity
                  style={[styles.btn, (phone.length !== 10 || busy) && styles.btnDisabled]}
                  onPress={handleSendOtp}
                  disabled={phone.length !== 10 || busy}
                  activeOpacity={0.8}
                >
                  {sending ? (
                    <ActivityIndicator color={colors.white} />
                  ) : (
                    <Text style={styles.btnText}>Send OTP →</Text>
                  )}
                </TouchableOpacity>
              </>
            ) : (
              <>
                <Text style={styles.cardTitle}>Verify OTP</Text>
                <Text style={styles.cardSub}>
                  Sent to +91 {phone}{'  '}
                  <Text
                    style={styles.link}
                    onPress={() => {
                      if (busy) return;
                      setStep('phone');
                      setOtp('');
                    }}
                  >
                    Change
                  </Text>
                </Text>

                <TextInput
                  style={[styles.inputStandalone, styles.otpInput]}
                  placeholder="· · · · · ·"
                  placeholderTextColor={INPUT_PLACEHOLDER}
                  keyboardType="number-pad"
                  keyboardAppearance="light"
                  maxLength={6}
                  value={otp}
                  onChangeText={setOtp}
                  autoFocus
                  editable={!busy}
                  cursorColor={INPUT_TEXT}
                  selectionColor={colors.purpleBright}
                  underlineColorAndroid="transparent"
                  textAlignVertical="center"
                />

                <TouchableOpacity
                  style={[styles.btn, (otp.length < 4 || busy) && styles.btnDisabled]}
                  onPress={handleVerifyOtp}
                  disabled={otp.length < 4 || busy}
                  activeOpacity={0.8}
                >
                  {verifying ? (
                    <ActivityIndicator color={colors.white} />
                  ) : (
                    <Text style={styles.btnText}>Verify & Continue →</Text>
                  )}
                </TouchableOpacity>

                <TouchableOpacity
                  style={styles.resendRow}
                  onPress={handleResendOtp}
                  disabled={busy}
                >
                  <Text style={styles.cardSub}>Didn't receive?  </Text>
                  <Text style={styles.link}>{resending ? 'Sending…' : 'Resend OTP'}</Text>
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

  logoWrap:  { alignItems: 'center', gap: 10 },
  logoOrb: {
    width: 72, height: 72, borderRadius: 36,
    backgroundColor: colors.surface,
    borderWidth: 1, borderColor: colors.border,
    alignItems: 'center', justifyContent: 'center',
  },
  logoName: {
    fontSize: 24, fontWeight: '700', color: colors.icy,
    letterSpacing: 0.3,
  },
  logoTag:    { fontSize: 13, color: colors.icyDim },

  card: {
    backgroundColor: colors.surface,
    borderRadius: 16,
    padding: 24,
    borderWidth: 1,
    borderColor: colors.border,
    gap: 16,
  },

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

  phoneRow:  { flexDirection: 'row', gap: 10 },
  flagBox: {
    backgroundColor: '#EEF3F9',
    borderRadius: 12, borderWidth: 1, borderColor: colors.border,
    paddingHorizontal: 14, justifyContent: 'center',
  },
  flagText:  { color: colors.icy, fontSize: 14 },
  refLabel:  { fontSize: 12, color: colors.icyDim, fontWeight: '600' },

  input: {
    flex: 1,
    backgroundColor: '#FFFFFF',
    borderRadius: 12, borderWidth: 1, borderColor: colors.border,
    paddingHorizontal: 16, paddingVertical: 14,
    color: INPUT_TEXT,
    fontSize: 16,
    fontWeight: '600',
  },
  inputStandalone: {
    width: '100%',
    backgroundColor: '#FFFFFF',
    borderRadius: 12, borderWidth: 1, borderColor: colors.border,
    paddingHorizontal: 16, paddingVertical: 14,
    color: INPUT_TEXT,
    fontSize: 16,
    fontWeight: '600',
  },
  otpInput: {
    textAlign: 'center', letterSpacing: 10,
    fontSize: 24, fontWeight: '700',
    color: INPUT_TEXT,
  },

  btn: {
    backgroundColor: colors.purple,
    borderRadius: 12, paddingVertical: 16,
    alignItems: 'center',
  },
  btnDisabled: {
    backgroundColor: colors.surfaceLight,
    borderWidth: 1, borderColor: colors.border,
  },
  btnText: { color: colors.white, fontSize: 16, fontWeight: '700' },

  resendRow:   { flexDirection: 'row', justifyContent: 'center' },
  link:        { color: colors.purpleBright, fontSize: 13, fontWeight: '600' },
  terms:       { textAlign: 'center', color: colors.icyMuted, fontSize: 12 },
});
