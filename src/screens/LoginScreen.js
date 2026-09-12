import React, { useState, useEffect } from 'react';
import {
  View, Text, TextInput, TouchableOpacity, Image,
  StyleSheet, StatusBar, KeyboardAvoidingView,
  Platform, Alert, ActivityIndicator, ScrollView,
} from 'react-native';
import Screen from '../components/Screen';
import { colors } from '../theme/colors';
import { saveUser } from '../utils/storage';
import { replaceRoot } from '../navigation/rootNavigation';
import { verifyOtpWithServer } from '../services/api';
import { initOtpWidget, isOtpConfigured, sendOtp, retryOtp, verifyOtp } from '../services/otp';

const INPUT_TEXT = colors.icy;
const INPUT_PLACEHOLDER = colors.icyMuted;

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
    if (!accessToken) {
      throw new Error('OTP token missing. Please request a new OTP.');
    }

    const reg = await verifyOtpWithServer({
      phone,
      accessToken,
      referralCode: referralCode.trim() || undefined,
    });

    await saveUser({
      phone,
      active: !!reg.active,
      referralCode: reg.referralCode || null,
      appliedReferralCode: referralCode.trim() || null,
      subscriptionEnd: reg.subscriptionEnd || null,
      planType: reg.planType || null,
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
      <StatusBar barStyle="light-content" backgroundColor={colors.background} />
      <KeyboardAvoidingView
        style={styles.flex}
        behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
      >
        <ScrollView
          contentContainerStyle={styles.scroll}
          keyboardShouldPersistTaps="handled"
          showsVerticalScrollIndicator={false}
        >
          <View style={styles.brandBlock}>
            <View style={styles.brandRing}>
              <Image
                source={require('../../assets/app_icon.png')}
                style={styles.brandIcon}
                resizeMode="contain"
                accessibilityLabel="AG rider"
              />
            </View>
            <Text style={styles.brandEyebrow}>DRIVER ASSIST</Text>
            <Text style={styles.brandTitle}>AG rider</Text>
            <Text style={styles.brandTagline}>Fast, focused and ready for every ride.</Text>
          </View>

          <View style={styles.card}>
            <View style={styles.steps}>
              <View style={[styles.stepChip, styles.stepChipActive]}>
                <Text style={styles.stepChipTextActive}>1  Number</Text>
              </View>
              <View style={styles.stepJoin} />
              <View style={[styles.stepChip, step === 'otp' && styles.stepChipActive]}>
                <Text style={step === 'otp' ? styles.stepChipTextActive : styles.stepChipText}>2  Code</Text>
              </View>
            </View>

            {step === 'phone' ? (
              <>
                <Text style={styles.cardTitle}>Sign in</Text>
                <Text style={styles.cardSub}>Enter your mobile number to get a one-time SMS code.</Text>

                <Text style={styles.fieldLabel}>Mobile number</Text>
                <View style={styles.phoneRow}>
                  <View style={styles.flagBox}>
                    <Text style={styles.flagText}>🇮🇳 +91</Text>
                  </View>
                  <TextInput
                    style={styles.input}
                    placeholder="10-digit number"
                    placeholderTextColor={INPUT_PLACEHOLDER}
                    keyboardType="phone-pad"
                    keyboardAppearance="dark"
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

                <Text style={styles.fieldLabel}>Referral code</Text>
                <TextInput
                  style={styles.inputStandalone}
                  placeholder="Optional — e.g. SR1234ABC"
                  placeholderTextColor={INPUT_PLACEHOLDER}
                  autoCapitalize="characters"
                  keyboardAppearance="dark"
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
                    <ActivityIndicator color={colors.background} />
                  ) : (
                    <Text style={styles.btnText}>Send OTP</Text>
                  )}
                </TouchableOpacity>
              </>
            ) : (
              <>
                <Text style={styles.cardTitle}>Enter the code</Text>
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
                  placeholder="• • • • • •"
                  placeholderTextColor={INPUT_PLACEHOLDER}
                  keyboardType="number-pad"
                  keyboardAppearance="dark"
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
                    <ActivityIndicator color={colors.background} />
                  ) : (
                    <Text style={styles.btnText}>Verify and continue</Text>
                  )}
                </TouchableOpacity>

                <TouchableOpacity
                  style={styles.resendRow}
                  onPress={handleResendOtp}
                  disabled={busy}
                >
                  <Text style={styles.cardSub}>Didn&apos;t receive?  </Text>
                  <Text style={styles.link}>{resending ? 'Sending…' : 'Resend OTP'}</Text>
                </TouchableOpacity>
              </>
            )}
          </View>

          <Text style={styles.terms}>
            By continuing you agree to our{' '}
            <Text style={styles.termsLink} onPress={() => navigation.navigate('Terms')}>
              Terms & Conditions
            </Text>
            {' '}and{' '}
            <Text style={styles.termsLink} onPress={() => navigation.navigate('PrivacyPolicy')}>
              Privacy Policy
            </Text>
            . AG rider auto-accepts Rapido with Accessibility and Ola with Shizuku.
          </Text>
        </ScrollView>
      </KeyboardAvoidingView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  scroll: {
    flexGrow: 1,
    paddingHorizontal: 20,
    paddingTop: 18,
    paddingBottom: 28,
    justifyContent: 'flex-start',
    gap: 20,
  },

  brandBlock: {
    alignItems: 'center',
    paddingTop: 12,
    paddingBottom: 4,
    gap: 8,
  },
  brandRing: {
    width: 108,
    height: 108,
    borderRadius: 54,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.surfaceCard,
    borderWidth: 2,
    borderColor: colors.borderPurple,
    marginBottom: 8,
  },
  brandIcon: {
    width: 78,
    height: 78,
    borderRadius: 24,
  },
  brandEyebrow: { color: colors.gold, fontSize: 10, fontWeight: '800', letterSpacing: 2 },
  brandTitle: {
    fontSize: 36,
    fontWeight: '800',
    letterSpacing: -1,
    color: colors.icy,
  },
  brandTagline: { color: colors.icyDim, fontSize: 14, lineHeight: 20, textAlign: 'center' },

  card: {
    alignSelf: 'center', width: '100%', maxWidth: 480,
    backgroundColor: colors.surfaceCard,
    borderRadius: 32,
    padding: 22,
    gap: 14,
  },

  steps: { flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: 4 },
  stepChip: {
    borderRadius: 999, paddingHorizontal: 12, paddingVertical: 6,
    backgroundColor: colors.surfaceLight,
  },
  stepChipActive: { backgroundColor: colors.purple },
  stepChipText: { color: colors.icyMuted, fontSize: 12, fontWeight: '800' },
  stepChipTextActive: { color: colors.background, fontSize: 12, fontWeight: '800' },
  stepJoin: { flex: 1, height: 2, backgroundColor: colors.border, borderRadius: 1 },

  cardTitle: { fontSize: 24, fontWeight: '800', color: colors.icy, letterSpacing: -0.4 },
  cardSub: { fontSize: 13, color: colors.icyDim, lineHeight: 19 },
  fieldLabel: { fontSize: 12, color: colors.icyMuted, fontWeight: '700', marginTop: 2 },

  phoneRow: { flexDirection: 'row', gap: 10 },
  flagBox: {
    backgroundColor: colors.surfaceLight,
    borderRadius: 16,
    paddingHorizontal: 14, justifyContent: 'center',
  },
  flagText: { color: colors.icy, fontSize: 14, fontWeight: '700' },

  input: {
    flex: 1,
    backgroundColor: colors.surfaceLight,
    borderRadius: 16,
    paddingHorizontal: 16, paddingVertical: 14,
    color: INPUT_TEXT,
    fontSize: 16,
    fontWeight: '600',
  },
  inputStandalone: {
    width: '100%',
    backgroundColor: colors.surfaceLight,
    borderRadius: 16,
    paddingHorizontal: 16, paddingVertical: 14,
    color: INPUT_TEXT,
    fontSize: 16,
    fontWeight: '600',
  },
  otpInput: {
    textAlign: 'center', letterSpacing: 12,
    fontSize: 26, fontWeight: '800',
    color: INPUT_TEXT,
  },

  btn: {
    backgroundColor: colors.purple,
    borderRadius: 999, paddingVertical: 16,
    alignItems: 'center',
    marginTop: 4,
  },
  btnDisabled: {
    backgroundColor: colors.surfaceLight,
  },
  btnText: { color: colors.background, fontSize: 16, fontWeight: '800' },

  resendRow: { flexDirection: 'row', justifyContent: 'center' },
  link: { color: colors.purpleBright, fontSize: 13, fontWeight: '700' },
  terms: {
    alignSelf: 'center', maxWidth: 480, textAlign: 'center',
    color: colors.icyMuted, fontSize: 12, marginBottom: 8, lineHeight: 18,
  },
  termsLink: { color: colors.purpleBright, fontWeight: '700' },
});
