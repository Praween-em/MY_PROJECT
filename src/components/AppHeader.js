import React from 'react';
import { View, Text, TouchableOpacity, StyleSheet } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../theme/colors';

export default function AppHeader({
  eyebrow = 'AG RIDER',
  title,
  subtitle,
  onBack,
  actionIcon,
  actionLabel,
  onAction,
}) {
  return (
    <View style={styles.wrap}>
      <View style={styles.topRow}>
        {onBack ? (
          <TouchableOpacity
            style={styles.circleBtn}
            onPress={onBack}
            activeOpacity={0.75}
            accessibilityRole="button"
            accessibilityLabel="Go back"
          >
            <Ionicons name="chevron-back" size={20} color={colors.icy} />
          </TouchableOpacity>
        ) : (
          <View style={styles.brandChip}>
            <View style={styles.brandMark}>
              <Text style={styles.brandMarkText}>AG</Text>
            </View>
            <Text style={styles.brandText}>{eyebrow}</Text>
          </View>
        )}

        {!!onAction && (
          <TouchableOpacity
            style={actionLabel ? styles.actionPill : styles.circleBtn}
            onPress={onAction}
            activeOpacity={0.75}
            accessibilityRole="button"
            accessibilityLabel={actionLabel}
          >
            <Ionicons name={actionIcon || 'ellipsis-horizontal'} size={17} color={colors.purpleBright} />
            {!!actionLabel && <Text style={styles.actionText}>{actionLabel}</Text>}
          </TouchableOpacity>
        )}
      </View>

      <Text style={styles.title} numberOfLines={2}>{title}</Text>
      {!!subtitle && <Text style={styles.subtitle} numberOfLines={2}>{subtitle}</Text>}
    </View>
  );
}

export function SectionTitle({ label, title, right }) {
  return (
    <View style={styles.sectionRow}>
      <View style={styles.sectionCopy}>
        {!!label && (
          <View style={styles.sectionLabelRow}>
            <View style={styles.sectionDash} />
            <Text style={styles.sectionLabel}>{label}</Text>
          </View>
        )}
        <Text style={styles.sectionTitle}>{title}</Text>
      </View>
      {right}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: {
    paddingHorizontal: 20,
    paddingTop: 10,
    paddingBottom: 8,
  },
  topRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    minHeight: 40,
    marginBottom: 12,
  },
  brandChip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    backgroundColor: colors.surfaceCard,
    borderRadius: 999,
    paddingRight: 12,
    paddingLeft: 4,
    paddingVertical: 4,
    borderWidth: 1,
    borderColor: colors.border,
  },
  brandMark: {
    width: 28,
    height: 28,
    borderRadius: 14,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.purple,
  },
  brandMarkText: {
    color: colors.background,
    fontSize: 9,
    fontWeight: '900',
    letterSpacing: 0.4,
  },
  brandText: {
    color: colors.purpleBright,
    fontSize: 11,
    fontWeight: '800',
    letterSpacing: 1.4,
  },
  circleBtn: {
    width: 40,
    height: 40,
    borderRadius: 20,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.surfaceCard,
    borderWidth: 1,
    borderColor: colors.border,
  },
  actionPill: {
    minHeight: 40,
    borderRadius: 20,
    paddingHorizontal: 12,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    backgroundColor: colors.surfaceCard,
    borderWidth: 1,
    borderColor: colors.border,
  },
  actionText: { color: colors.purpleBright, fontSize: 11, fontWeight: '800' },
  title: {
    color: colors.icy,
    fontSize: 28,
    fontWeight: '800',
    letterSpacing: -0.7,
    lineHeight: 32,
  },
  subtitle: {
    color: colors.icyMuted,
    fontSize: 13,
    lineHeight: 18,
    marginTop: 6,
    maxWidth: '92%',
  },
  sectionRow: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    justifyContent: 'space-between',
    marginTop: 8,
    marginBottom: 2,
  },
  sectionCopy: { flex: 1 },
  sectionLabelRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  sectionDash: {
    width: 12,
    height: 2,
    borderRadius: 1,
    backgroundColor: colors.purple,
  },
  sectionLabel: {
    color: colors.icyMuted,
    fontSize: 10,
    fontWeight: '800',
    letterSpacing: 1.4,
  },
  sectionTitle: {
    color: colors.icy,
    fontSize: 17,
    fontWeight: '800',
    marginTop: 4,
    letterSpacing: -0.2,
  },
});
