import React, { useState, useCallback } from 'react';
import {
  View, Text, StyleSheet, StatusBar, ScrollView,
  TouchableOpacity,
} from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import Screen from '../components/Screen';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../theme/colors';
import { getRideHistory, clearRideHistory } from '../utils/rideHistory';

export default function HistoryScreen() {
  const [rides, setRides] = useState([]);
  const [selected, setSelected] = useState([]);

  useFocusEffect(
    useCallback(() => {
      getRideHistory().then(setRides).catch(() => setRides([]));
    }, [])
  );

  const total = rides.reduce((s, r) => s + (r.amount || 0), 0);
  const byApp = rides.reduce((acc, r) => {
    const key = r.app || 'App';
    acc[key] = (acc[key] || 0) + (r.amount || 0);
    return acc;
  }, {});
  const appEntries = Object.entries(byApp).slice(0, 2);
  const toggle = id => setSelected(p => p.includes(id) ? p.filter(i => i !== id) : [...p, id]);

  const handleClear = async () => {
    await clearRideHistory();
    setRides([]);
    setSelected([]);
  };

  const nowLabel = () => {
    const d = new Date();
    return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`;
  };

  return (
    <Screen edges={['top']}>
      <StatusBar barStyle="light-content" backgroundColor={colors.background} />

      <View style={styles.header}>
        <View style={styles.headerSpacer} />
        <Text style={styles.headerTitle}>Ride History</Text>
        <TouchableOpacity style={styles.deleteBtn} onPress={handleClear}>
          <Ionicons name="trash-outline" size={18} color={colors.offRed} />
        </TouchableOpacity>
      </View>

      <ScrollView contentContainerStyle={styles.scroll} showsVerticalScrollIndicator={false}>
        <View style={styles.earningsCard}>
          <Text style={styles.earningsLabel}>TOTAL EARNINGS</Text>
          <Text style={styles.earningsAmount}>₹{total}</Text>
          <View style={styles.earningsSplit}>
            {appEntries.length === 0 ? (
              <Text style={[styles.splitChip, { borderColor: colors.border, color: colors.icyMuted }]}>
                No rides yet
              </Text>
            ) : (
              appEntries.map(([app, sum]) => (
                <Text
                  key={app}
                  style={[styles.splitChip, { borderColor: colors.borderPurple, color: colors.purpleBright }]}
                >
                  {app} ₹{sum}
                </Text>
              ))
            )}
          </View>
        </View>

        <View style={styles.scanRow}>
          <View style={[styles.scanDot, { backgroundColor: colors.success }]} />
          <Text style={styles.scanText}>[{nowLabel()}]  Live latency from Nuclear/Standard accepts</Text>
        </View>

        <Text style={styles.sectionHead}>Accepted Rides</Text>
        {rides.length === 0 ? (
          <Text style={styles.empty}>Accepts will show here with real ms timing.</Text>
        ) : (
          rides.map(ride => (
            <TouchableOpacity
              key={ride.id}
              style={[styles.rideCard, selected.includes(ride.id) && styles.rideCardSelected]}
              onPress={() => toggle(ride.id)}
              activeOpacity={0.8}
            >
              <View style={[styles.checkbox, selected.includes(ride.id) && styles.checkboxOn]}>
                {selected.includes(ride.id) && <Ionicons name="checkmark-sharp" size={14} color={colors.white} />}
              </View>

              <View style={styles.rideInfo}>
                <Text style={styles.rideAmount}>{ride.amount > 0 ? `₹${ride.amount}` : ride.label || 'Accepted'}</Text>
                <Text style={styles.rideDetail}>
                  [{ride.app}-{ride.tag}]{ride.price > 0 ? `  Price: ₹${ride.price}` : ''}
                </Text>
              </View>

              <View style={styles.rideRight}>
                <View style={{ flexDirection: 'row', alignItems: 'center', gap: 2 }}>
                  <Ionicons name="flash" size={12} color={colors.purpleBright} />
                  <Text style={styles.rideMs}>{ride.ms != null ? `${ride.ms}ms` : '—'}</Text>
                </View>
                <Text style={styles.rideTime}>{ride.time}</Text>
              </View>
            </TouchableOpacity>
          ))
        )}
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  header: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingHorizontal: 20, paddingVertical: 14,
    borderBottomWidth: 1, borderBottomColor: colors.border,
  },
  headerSpacer: { width: 38, height: 38 },
  headerTitle: { flex: 1, fontSize: 18, fontWeight: '800', color: colors.icy, textAlign: 'center' },
  deleteBtn: {
    width: 38, height: 38, borderRadius: 10,
    backgroundColor: colors.dangerDim,
    borderWidth: 1, borderColor: colors.danger + '44',
    alignItems: 'center', justifyContent: 'center',
  },

  scroll: { padding: 20, gap: 14, paddingBottom: 32 },

  earningsCard: {
    backgroundColor: colors.surface,
    borderRadius: 20, padding: 22,
    borderWidth: 1.5, borderColor: colors.borderPurple,
    alignItems: 'center', gap: 8,
    shadowColor: colors.purple, shadowOpacity: 0.2, shadowRadius: 16, elevation: 6,
  },
  earningsLabel: { fontSize: 11, color: colors.icyMuted, letterSpacing: 2 },
  earningsAmount:{ fontSize: 52, fontWeight: '900', color: colors.purpleBright },
  earningsSplit: { flexDirection: 'row', gap: 10, flexWrap: 'wrap', justifyContent: 'center' },
  splitChip: {
    borderWidth: 1, borderRadius: 20,
    paddingHorizontal: 14, paddingVertical: 5,
    fontSize: 12, fontWeight: '700',
  },

  scanRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  scanDot:  { width: 8, height: 8, borderRadius: 4 },
  scanText: { fontSize: 12, color: colors.success, fontFamily: 'monospace', flex: 1 },

  sectionHead: {
    fontSize: 12, fontWeight: '700', color: colors.icyMuted,
    letterSpacing: 1.5, textTransform: 'uppercase',
  },
  empty: { fontSize: 13, color: colors.icyMuted, textAlign: 'center', marginTop: 12 },

  rideCard: {
    flexDirection: 'row', alignItems: 'center',
    backgroundColor: colors.surface,
    borderRadius: 14, padding: 14,
    borderLeftWidth: 3, borderLeftColor: colors.blue,
    borderTopWidth: 1, borderTopColor: colors.border,
    borderRightWidth: 1, borderRightColor: colors.border,
    borderBottomWidth: 1, borderBottomColor: colors.border,
    gap: 12,
  },
  rideCardSelected: {
    borderLeftColor: colors.purple,
    backgroundColor: '#180030',
  },
  checkbox: {
    width: 22, height: 22, borderRadius: 6,
    borderWidth: 2, borderColor: colors.blue,
    alignItems: 'center', justifyContent: 'center',
  },
  checkboxOn: { backgroundColor: colors.purple, borderColor: colors.purple },

  rideInfo:   { flex: 1 },
  rideAmount: { fontSize: 18, fontWeight: '900', color: colors.icy, marginBottom: 3 },
  rideDetail: { fontSize: 11, color: colors.icyDim, lineHeight: 16 },

  rideRight:  { alignItems: 'flex-end', gap: 4 },
  rideMs:     { fontSize: 12, fontWeight: '700', color: colors.purpleBright },
  rideTime:   { fontSize: 11, color: colors.icyMuted },
});
