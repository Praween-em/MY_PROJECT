# Remaining updates — 13 October 2026

AG rider source has the same-card hold. It has not been compiled or run on a phone. The installed app does not include this change until the next Android build. `plugins/withAutoClicker.js` copies `native-android/src` into the Android project on prebuild.

## Change already in source

File: `native-android/src/AutoClickerService.java`

After a confirmed accept, the same Accept card is held for **1 second** (`SAME_CARD_HOLD_AFTER_OK_MS`). After a verify miss, it is held for **0.8 seconds** (`SAME_CARD_HOLD_AFTER_MISS_MS`). A button that moves more than **48 px** (`CARD_IDENTITY_QUANTUM_PX`), or a different fare, or a pickup that differs by more than **0.2 km**, is still clicked immediately.

While the hold is active:

- The hunt poll waits at least 80 ms.
- Alert spray, the Rapido micro-burst, and notification follow-ups stop.
- `strikeRapidoInstant` and `strikeOla` skip that card.
- A duplicate notification returns before cooldown is cleared and before a predictive tap.
- A verify miss refreshes the accessibility tree.

The first tap is unchanged: notification listener, 1 ms tap, Shizuku for Ola, and the `:engine` process.

Left out on purpose: RidioPro’s 250 ms poll, 30 ms-only tap, and 1.5 second lock on every ride.

## Tests still to run

Rebuild, install, and check on a real phone:

1. First Accept on Rapido still lands as fast as before.
2. After a confirmed accept, the same card is not clicked again for about 1 second.
3. A second ride with a different fare or pickup is clicked immediately, even if the button is in the same place.
4. After a miss, the frozen Accept is not clicked in a loop, and the tree refresh runs.
5. Ola still waits out the slider, then uses the Shizuku press, and does not re-click that same card.
6. Repeat on ColorOS, MIUI, or Funtouch, where the accessibility tree often freezes after 2–3 accepts.

## Payments — 13 October 2026

RidioPro does not charge a card or UPI inside the app. The user picks a plan, the app calls `place_order`, and the order stays `awaiting_confirmation` until an admin confirms it in Supabase. Then `get_my_entitlement` turns the plan on.

AG rider now records the same kind of order:

- The plans screen posts the chosen plan to `POST /subscription/orders` and still opens Telegram.
- The screen shows “Order placed. Awaiting confirmation.”
- Admin panel → **Pending orders** → Confirm grants that plan. Reject does not.
- Apply migration `backend/migrations/007_subscription_orders.sql` (`npm run migrate`) before this works on the server.

## Still open

- **Unparsed next ride.** If fare filters are off and the next card has no readable ₹ or km, and the button has not moved more than 48 px, that ride waits out the 1 second or 0.8 second hold.
- **Entitlement stays allowed until the first server snapshot.** The phone number is still the login credential.
- **Ride-history confirmation is off** (`HISTORY_CONFIRM_ENABLED = false`).
- **RidioPro Rapido rules are not in AG rider:** max fare, minimum pickup, and max drop. Those need a home-screen control and an engine check.
- **AG rider also watches** `com.rapido.captain`. RidioPro only clicks `com.olacabs.oladriver` and `com.rapido.rider`.
