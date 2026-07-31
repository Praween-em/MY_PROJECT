import React, { useState, useCallback } from 'react';
import {
  View, Text, StyleSheet, StatusBar, ScrollView,
  TouchableOpacity, RefreshControl, ActivityIndicator, Alert,
} from 'react-native';
import { useFocusEffect } from '@react-navigation/native';
import Screen from '../components/Screen';
import { Ionicons } from '@expo/vector-icons';
import { colors } from '../theme/colors';
import { getRideHistory, clearRideHistory } from '../utils/rideHistory';

export default function HistoryScreen() {
  const [rides, setRides] = useState([]);
  const [selected, setSelected] = useState([]);
  const [refreshing, setRefreshing] = useState(false);
  const [loading, setLoading] = useState(true);

  const loadHistory = useCallback(async ({ silent } = {}) => {
    if (!silent) setRefreshing(true);
    try {
      const list = await getRideHistory();
      setRides(list);
    } catch {
      setRides([]);
    } finally {
      setRefreshing(false);
      setLoading(false);
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      loadHistory({ silent: true });
    }, [loadHistory])
  );

  const byApp = rides.reduce((acc, r) => {
    const key = r.app || 'App';
    acc[key] = (acc[key] || 0) + 1;
    return acc;
  }, {});
  const appEntries = Object.entries(byApp);
  const toggle = (id) =>
    setSelected((p) => (p.includes(id) ? p.filter((i) => i !== id) : [...p, id]));

  const handleClear = () => {
    Alert.alert(
      'Clear history?',
      'This removes all accepted ride records on this device.',
      [
        { text: 'Cancel', style: 'cancel' },
        {
          text: 'Clear',
          style: 'destructive',
          onPress: async () => {
            await clearRideHistory();
            setRides([]);
            setSelected([]);
          },
        },
      ]
    );
  };

  return (
    <Screen edges={['top']}>
      <StatusBar barStyle="dark-content" backgroundColor={colors.background} />

      <View style={styles.header}>
        <TouchableOpacity
          style={styles.headerBtn}
          onPress={() => loadHistory()}
          disabled={refreshing}
          accessibilityLabel="Refresh ride history"
        >
          {refreshing ? (
            <ActivityIndicator size="small" color={colors.blue} />
          ) : (
            <Ionicons name="refresh-outline" size={18} color={colors.blue} />
          )}
        </TouchableOpacity>
        <Text style={styles.headerTitle}>Ride History</Text>
        <TouchableOpacity style={styles.deleteBtn} onPress={handleClear}>
          <Ionicons name="trash-outline" size={18} color={colors.offRed} />
        </TouchableOpacity>
      </View>

      <ScrollView
        contentContainerStyle={styles.scroll}
        showsVerticalScrollIndicator={false}
        refreshControl={
          <RefreshControl
            refreshing={refreshing}
            onRefresh={() => loadHistory()}
            tintColor={colors.blue}
            colors={[colors.blue]}
          />
        }
      >
        <View style={styles.summaryCard}>
          <Text style={styles.summaryLabel}>ACCEPTED RIDES</Text>
          <Text style={styles.summaryCount}>{rides.length}</Text>
          <View style={styles.summarySplit}>
            {appEntries.length === 0 ? (
              <Text style={[styles.splitChip, { borderColor: colors.border, color: colors.icyMuted }]}>
                No rides yet
              </Text>
            ) : (
              appEntries.map(([app, count]) => (
                <Text
                  key={app}
                  style={[styles.splitChip, { borderColor: colors.border, color: colors.icyDim }]}
                >
                  {app} · {count}
                </Text>
              ))
            )}
          </View>
        </View>

        <View style={styles.sectionRow}>
          <Text style={styles.sectionHead}>Accepted Rides</Text>
          <TouchableOpacity onPress={() => loadHistory()} disabled={refreshing}>
            <Text style={styles.reloadLink}>{refreshing ? 'Loading…' : 'Reload'}</Text>
          </TouchableOpacity>
        </View>

        {loading ? (
          <ActivityIndicator style={{ marginTop: 24 }} color={colors.blue} />
        ) : rides.length === 0 ? (
          <Text style={styles.empty}>
            No rides accepted yet. When auto-accept takes a ride, it will show here with app and time.
          </Text>
        ) : (
          rides.map((ride) => (
            <TouchableOpacity
              key={ride.id}
              style={[styles.rideCard, selected.includes(ride.id) && styles.rideCardSelected]}
              onPress={() => toggle(ride.id)}
              activeOpacity={0.8}
            >
              <View style={[styles.checkbox, selected.includes(ride.id) && styles.checkboxOn]}>
                {selected.includes(ride.id) && (
                  <Ionicons name="checkmark-sharp" size={14} color={colors.white} />
                )}
              </View>

              <View style={styles.rideInfo}>
                <View style={styles.titleRow}>
                  <Text style={styles.rideTitle}>{ride.app || 'App'}</Text>
                  <View style={styles.acceptedPill}>
                    <Text style={styles.acceptedPillText}>ACCEPTED</Text>
                  </View>
                </View>
                <Text style={styles.rideDetail}>
                  {ride.tag || 'Standard'}
                  {ride.price != null ? ` · ₹${ride.price}` : ''}
                  {ride.label ? ` · ${ride.label}` : ''}
                </Text>
                <Text style={styles.rideWhen}>
                  {ride.date || 'Today'} · {ride.time}
                </Text>
              </View>

              <View style={styles.rideRight}>
                <View style={styles.speedRow}>
                  <Ionicons name="flash-outline" size={12} color={colors.purpleBright} />
                  <Text style={styles.rideMs}>{ride.ms != null ? `${ride.ms}ms` : '—'}</Text>
                </View>
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
  headerTitle: { flex: 1, fontSize: 18, fontWeight: '800', color: colors.icy, textAlign: 'center' },
  headerBtn: {
    width: 38, height: 38, borderRadius: 10,
    backgroundColor: colors.surface,
    borderWidth: 1, borderColor: colors.border,
    alignItems: 'center', justifyContent: 'center',
  },
  deleteBtn: {
    width: 38, height: 38, borderRadius: 10,
    backgroundColor: colors.dangerDim,
    borderWidth: 1, borderColor: colors.danger + '44',
    alignItems: 'center', justifyContent: 'center',
  },

  scroll: { padding: 20, gap: 14, paddingBottom: 32 },

  summaryCard: {
    backgroundColor: colors.surface,
    borderRadius: 14, padding: 22,
    borderWidth: 1, borderColor: colors.border,
    alignItems: 'center', gap: 8,
  },
  summaryLabel: { fontSize: 11, color: colors.icyMuted, letterSpacing: 1.5, fontWeight: '600' },
  summaryCount: { fontSize: 44, fontWeight: '700', color: colors.icy },
  summarySplit: { flexDirection: 'row', gap: 10, flexWrap: 'wrap', justifyContent: 'center' },
  splitChip: {
    borderWidth: 1, borderRadius: 8,
    paddingHorizontal: 14, paddingVertical: 5,
    fontSize: 12, fontWeight: '600',
  },

  sectionRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  sectionHead: {
    fontSize: 12, fontWeight: '700', color: colors.icyMuted,
    letterSpacing: 1, textTransform: 'uppercase',
  },
  reloadLink: {
    fontSize: 13, fontWeight: '700', color: colors.blue,
  },
  empty: { fontSize: 13, color: colors.icyMuted, textAlign: 'center', marginTop: 12, lineHeight: 20 },

  rideCard: {
    flexDirection: 'row', alignItems: 'center',
    backgroundColor: colors.surface,
    borderRadius: 12, padding: 14,
    borderWidth: 1, borderColor: colors.border,
    gap: 12,
  },
  rideCardSelected: {
    borderColor: colors.purple,
    backgroundColor: colors.purpleGlow,
  },
  checkbox: {
    width: 22, height: 22, borderRadius: 6,
    borderWidth: 1.5, borderColor: colors.border,
    alignItems: 'center', justifyContent: 'center',
  },
  checkboxOn: { backgroundColor: colors.purple, borderColor: colors.purple },

  rideInfo: { flex: 1, gap: 3 },
  titleRow: { flexDirection: 'row', alignItems: 'center', gap: 8, flexWrap: 'wrap' },
  rideTitle: { fontSize: 17, fontWeight: '700', color: colors.icy },
  acceptedPill: {
    backgroundColor: colors.onGreen + '22',
    borderRadius: 6,
    paddingHorizontal: 6,
    paddingVertical: 2,
  },
  acceptedPillText: {
    fontSize: 10,
    fontWeight: '800',
    color: colors.onGreen,
    letterSpacing: 0.4,
  },
  rideDetail: { fontSize: 12, color: colors.icyDim, lineHeight: 16 },
  rideWhen: { fontSize: 11, color: colors.icyMuted },

  rideRight: { alignItems: 'flex-end', gap: 6 },
  speedRow: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  rideMs: { fontSize: 13, fontWeight: '700', color: colors.purpleBright },
});
