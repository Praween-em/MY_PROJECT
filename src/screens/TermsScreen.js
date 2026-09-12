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

export default function TermsScreen({ navigation }) {
  return (
    <Screen edges={['top', 'bottom']}>
      <StatusBar barStyle="light-content" backgroundColor={colors.background} />
      <AppHeader
        eyebrow="LEGAL"
        title="Terms & conditions"
        subtitle="Responsible use of AG rider services."
        onBack={() => navigation.goBack()}
      />

      <ScrollView contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>
        <View style={styles.dateChip}>
          <Text style={styles.updated}>Updated 28 July 2026</Text>
        </View>
        <Text style={styles.lead}>
          These Terms & Conditions (“Terms”) govern your use of AG rider
          (the “App”). By creating an account or using the App, you agree to
          these Terms.
        </Text>

        <Section title="1. Accessibility Assistive Purpose">
          <P>
            AG rider is an accessibility assistive application. It is
            intended for drivers who have a disability, impairment, or other
            condition that makes it difficult to locate and press the Accept
            control on ride-request screens accurately, consistently, or within
            the available time.
          </P>
          <P>
            The App uses Android Accessibility Service and related permissions
            only to provide that assistive function: helping eligible users
            activate the Accept control when they cannot do so reliably on their
            own. You agree to use the App solely for this legitimate
            accessibility purpose.
          </P>
        </Section>

        <Section title="2. Eligibility and Responsible Use">
          <P>
            You represent that you are legally permitted to drive and to use
            partner ride-hailing applications in your jurisdiction. You agree
            not to use AG rider to circumvent platform rules, harass others,
            commit fraud, or operate the App in any manner unrelated to
            accessibility assistance.
          </P>
          <P>
            You remain fully responsible for your driving, your interactions
            with passengers, and your compliance with all applicable laws and
            third-party platform terms.
          </P>
        </Section>

        <Section title="3. Permissions">
          <P>
            Certain features require Accessibility Service, notification access,
            display-over-other-apps, and battery exemptions. These permissions
            are requested so the assistive Accept function can operate when a
            ride request appears. You may withdraw permissions at any time;
            doing so may limit or disable App functionality.
          </P>
        </Section>

        <Section title="4. Subscriptions and Payments">
          <P>
            Paid plans, renewals, and refunds (if any) are described in the App
            and processed by our payment partner. Access to premium assistive
            features may depend on an active subscription and device limits
            associated with your account.
          </P>
        </Section>

        <Section title="5. Third-Party Services">
          <P>
            AG rider may interact with third-party driver applications for
            accessibility assistance only. Those applications are owned and
            operated by their respective providers. We are not affiliated with,
            endorsed by, or responsible for third-party platforms, their
            availability, or their policies.
          </P>
        </Section>

        <Section title="6. No Guarantee of Outcomes">
          <P>
            Ride availability, Accept timing, network conditions, and device
            performance vary. We do not guarantee that every ride request will
            be accepted, that assistive activation will always succeed, or that
            earnings will increase. The App is provided to improve access for
            eligible users, not to promise commercial results.
          </P>
        </Section>

        <Section title="7. Disclaimer of Warranties">
          <P>
            To the maximum extent permitted by law, the App is provided “as is”
            and “as available,” without warranties of any kind, whether express
            or implied, including merchantability, fitness for a particular
            purpose, and non-infringement.
          </P>
        </Section>

        <Section title="8. Limitation of Liability">
          <P>
            To the maximum extent permitted by law, AG rider and its
            operators shall not be liable for any indirect, incidental, special,
            consequential, or punitive damages, or any loss of profits, data, or
            goodwill, arising from your use of the App.
          </P>
        </Section>

        <Section title="9. Suspension and Termination">
          <P>
            We may suspend or terminate access if we reasonably believe these
            Terms have been violated, if required by law, or to protect the
            service and its users. You may stop using the App and log out at any
            time.
          </P>
        </Section>

        <Section title="10. Changes to These Terms">
          <P>
            We may update these Terms periodically. Continued use of the App
            after changes take effect constitutes acceptance of the updated
            Terms.
          </P>
        </Section>

        <Section title="11. Contact">
          <P>
            Questions about these Terms may be directed through the support
            channels listed in the App.
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
