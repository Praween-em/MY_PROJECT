import React from 'react';
import {
  View, Text, StyleSheet, StatusBar, ScrollView,
} from 'react-native';
import Screen from '../components/Screen';
import { colors } from '../theme/colors';
import AppHeader from '../components/AppHeader';

function Section({ title, children }) {
  const [num, ...rest] = title.split('. ');
  return (
    <View style={styles.section}>
      <View style={styles.sectionHead}>
        <View style={styles.numBadge}>
          <Text style={styles.numText}>{num}</Text>
        </View>
        <Text style={styles.sectionTitle}>{rest.join('. ')}</Text>
      </View>
      {children}
    </View>
  );
}

function P({ children }) {
  return <Text style={styles.para}>{children}</Text>;
}

export default function PrivacyPolicyScreen({ navigation }) {
  return (
    <Screen edges={['top', 'bottom']}>
      <StatusBar barStyle="light-content" backgroundColor={colors.background} />
      <AppHeader
        eyebrow="LEGAL"
        title="Privacy policy"
        subtitle="How AG rider handles your information."
        onBack={() => navigation.goBack()}
      />

      <ScrollView contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>
        <View style={styles.dateChip}>
          <Text style={styles.updated}>Updated 28 July 2026</Text>
        </View>
        <Text style={styles.lead}>
          AG rider (“we”, “our”, or “the App”) is designed as an accessibility
          assistive tool. This Privacy Policy explains what information we
          process, why we process it, and the choices available to you.
        </Text>

        <Section title="1. Purpose of the App">
          <P>
            AG rider helps drivers who experience motor, coordination, vision,
            or related difficulties interact with ride-request screens more
            reliably. The App uses Android Accessibility Service capabilities so
            that eligible users can activate an on-screen Accept control when they
            are otherwise unable to press it accurately or in time.
          </P>
          <P>
            The App is intended solely for lawful accessibility assistance. It is
            not provided to interfere with third-party services, automate activity
            for users without a genuine accessibility need, or collect
            unnecessary personal data.
          </P>
        </Section>

        <Section title="2. Information We Process">
          <P>
            Depending on how you use AG rider, we may process:
          </P>
          <P>
            • Account details you provide (such as your mobile number) for
            sign-in and subscription management.{"\n"}
            • Device identifiers required to enforce plan limits and protect
            accounts.{"\n"}
            • Subscription and payment status handled through our payment
            provider.{"\n"}
            • App preferences you set (for example, minimum fare or response
            delay).{"\n"}
            • Local ride-acceptance history stored on your device for your own
            reference.
          </P>
          <P>
            Accessibility Service access is used only to detect and activate the
            relevant Accept control for assistive purposes. We do not use that
            access to read your personal messages, contacts, photos, or unrelated
            app content for marketing or profiling.
          </P>
        </Section>

        <Section title="3. How We Use Information">
          <P>
            We use information to authenticate your account, deliver subscription
            features, operate the accessibility assistive functions you enable,
            improve reliability and support, and protect against misuse or fraud.
          </P>
        </Section>

        <Section title="4. Storage and Security">
          <P>
            Account and subscription data may be stored on secure cloud services
            operated by us or our processors. Local preferences and history may
            remain on your device. We apply reasonable technical and
            organisational measures to protect information under our control.
            No method of transmission or storage is completely secure, and we
            cannot guarantee absolute security.
          </P>
        </Section>

        <Section title="5. Sharing">
          <P>
            We do not sell your personal information. We may share limited data
            with service providers who help us operate authentication, payments,
            hosting, or support — only as needed for those services — or when
            required by law.
          </P>
        </Section>

        <Section title="6. Your Choices">
          <P>
            You may revoke Accessibility Service, notification, and
            related permissions at any time in Android Settings. You may also
            log out, clear local history in the App, or contact us to request
            account-related assistance where applicable.
          </P>
        </Section>

        <Section title="7. Children’s Privacy">
          <P>
            AG rider is intended for adult professional drivers. It is not
            directed to children.
          </P>
        </Section>

        <Section title="8. Changes">
          <P>
            We may update this Privacy Policy from time to time. Continued use of
            the App after an update means you acknowledge the revised policy.
          </P>
        </Section>

        <Section title="9. Contact">
          <P>
            For privacy questions about AG rider, contact us through the
            support channels listed in the App.
          </P>
        </Section>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  scroll: { paddingHorizontal: 20, paddingTop: 8, paddingBottom: 40, gap: 12 },
  dateChip: {
    alignSelf: 'flex-start',
    backgroundColor: colors.surfaceCard,
    borderRadius: 999,
    paddingHorizontal: 12,
    paddingVertical: 6,
  },
  updated: { fontSize: 12, color: colors.icyMuted, fontWeight: '700' },
  lead: {
    fontSize: 15,
    lineHeight: 23,
    color: colors.icyDim,
    marginBottom: 4,
  },
  section: {
    gap: 8, padding: 16,
    backgroundColor: colors.surfaceCard, borderRadius: 22,
  },
  sectionHead: { flexDirection: 'row', alignItems: 'center', gap: 10, marginBottom: 2 },
  numBadge: {
    width: 28, height: 28, borderRadius: 14,
    backgroundColor: colors.purple, alignItems: 'center', justifyContent: 'center',
  },
  numText: { color: colors.background, fontSize: 12, fontWeight: '800' },
  sectionTitle: {
    flex: 1,
    fontSize: 16,
    fontWeight: '800',
    color: colors.icy,
  },
  para: {
    fontSize: 14,
    lineHeight: 22,
    color: colors.icyDim,
  },
});
