# Accept race — edge cases & OEM hardening

## State machine

`IDLE → ARMED → STRIKING → VERIFYING → COOLDOWN → IDLE`

## Device profiles

| Profile | Devices | Gesture | Hunt poll | Notes |
|--------|---------|---------|-----------|--------|
| **Stock** | Pixel, Motorola, Samsung One UI | ~8ms | 1–2ms | Short tap wins server race |
| **Heavy** | Vivo/Funtouch/OriginOS, Realme/ColorOS, Oppo, Xiaomi/Redmi/MIUI/HyperOS, IQOO, Tecno/Infinix | ~16ms + 2nd press @10ms | 1ms | Longer press so OEM registers tap; denser NLS follow-ups |

## Edge cases

1. **Stuck STRIKING/VERIFYING / burst lock** → ride 3+ never taps  
   - Catch: phase age + 1s watchdog  
   - Fix: `recoverIfRaceStuck`, `prepareForNewRideSignal`

2. **New NLS while still VERIFYING** → hunt blocked  
   - Fix: unlock then arm on every ride signal (unless live COOLDOWN)

3. **Sticky COOLDOWN latch** → all rides ignored  
   - Fix: expire `ignoreRapidoUntilMs`; clear on watchdog / new ride

4. **Late Accept paint (Vivo/Realme/MIUI)**  
   - Fix: NLS follow-up chain + FG/armed 1ms poll

5. **Short tap swallowed**  
   - Fix: ACTION_CLICK + raw gesture + heavy OEM 2nd press

6. **Bubble-only idle** (Home + float icon)  
   - Fix: never idle while ARMED/STRIKING/VERIFYING or Captain FG

7. **Master Auto-accept OFF** (a11y on, engine off)  
   - Fix: status notification ON/OFF + `SKIP_DISABLED` log

8. **SUPER RIDEX left in foreground**  
   - Fix: hunt Rapido windows; test with Captain on top

9. **Fake success (PendingIntent only)**  
   - Fix: VERIFY requires Accept seen after a real UI strike

10. **Process / a11y killed by OEM**  
    - Fix: clear latches on `onServiceConnected`; user must re-enable a11y

11. **Along-route offer during trip chrome**  
    - Fix: note on-trip UI but keep hunting Accept

## Watchdog

Every **1s** while enabled: expire COOLDOWN, `recoverIfRaceStuck("watchdog")`, reschedule hunt if needed.

## Swipe-kill survival (`:engine` process)

Accept + NLS run in `android:process=":engine"` with `stopWithTask="false"`.

- Swiping SUPER RIDEX from Recents kills the **UI** process.
- On many phones the **`:engine`** process (Accessibility + notification listener + FGS) keeps running and still accepts rides.
- Some OEMs (aggressive Vivo/MIUI) still kill the whole package — then Accessibility must be turned ON again.
- After installing this change: toggle Accessibility **off → on** once.
