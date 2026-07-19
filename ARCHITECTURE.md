# Prime Clicker — Architecture & Implementation Plan

## System Overview

```
┌─────────────────────────────────────────┐
│            Android Device               │
│                                         │
│  ┌─────────────────────────────────┐    │
│  │   React Native App (Expo Bare)  │    │
│  │   LoginScreen → HomeScreen      │    │
│  │   HistoryScreen → ProfileScreen │    │
│  └────────────┬────────────────────┘    │
│               │ Native Bridge           │
│  ┌────────────▼────────────────────┐    │
│  │  AutoClickerService (Java)      │    │
│  │  AccessibilityService           │    │
│  │  Listens to ALL app UI events   │    │
│  │  Taps accept button on match    │    │
│  └─────────────────────────────────┘    │
└──────────────────┬──────────────────────┘
                   │ HTTPS
┌──────────────────▼──────────────────────┐
│         AWS (Mumbai region)             │
│                                         │
│  API Gateway → Lambda → Express App     │
│                    │                    │
│             DynamoDB Table              │
│          (phone | subStart | subEnd)    │
└──────────────────┬──────────────────────┘
                   │ Webhooks + Orders
┌──────────────────▼──────────────────────┐
│              Razorpay                   │
│  Order creation, payment processing,    │
│  webhook delivery                       │
└─────────────────────────────────────────┘
```

---

## Folder Structure

```
rapido-autoclicker/
│
├── App.js                          Navigation root (React Navigation)
├── app.json                        Expo config + Android permissions
│
├── src/
│   ├── screens/
│   │   ├── LoginScreen.js          Phone + OTP entry
│   │   ├── PlansScreen.js          Plan selection + payment trigger
│   │   ├── HomeScreen.js           Main dashboard (auto-accept controls)
│   │   ├── HistoryScreen.js        Ride history + earnings
│   │   ├── ProfileScreen.js        User info + referral program
│   │   └── SettingsScreen.js       Delay, permissions, notifications
│   │
│   ├── theme/
│   │   └── colors.js               Design tokens (neon green + dark)
│   │
│   ├── services/
│   │   ├── api.js                  All HTTP calls to backend
│   │   ├── payment.js              Razorpay checkout flow
│   │   └── permissions.js          Android permission helpers
│   │
│   ├── hooks/
│   │   ├── useSubscription.js      Subscription state + backend sync
│   │   └── usePermissions.js       Permission state + AppState listener
│   │
│   ├── utils/
│   │   └── storage.js              AsyncStorage session cache
│   │
│   └── native/
│       └── README.md               Native Android module implementation guide
│
└── backend/
    ├── src/
    │   ├── index.js                Express app entry
    │   ├── lambda.js               AWS Lambda handler wrapper
    │   ├── config/
    │   │   ├── dynamodb.js         DynamoDB client
    │   │   └── razorpay.js         Razorpay SDK instance
    │   ├── routes/
    │   │   ├── subscription.js     create-order / verify-payment / status
    │   │   └── webhook.js          Razorpay server-to-server webhook
    │   ├── models/
    │   │   └── user.js             DynamoDB CRUD for user/subscription
    │   ├── middleware/
    │   │   └── auth.js             Phone-based request auth
    │   └── services/
    │       └── razorpay.js         Order creation + signature verification
    ├── serverless.yml              AWS Lambda + DynamoDB infrastructure
    ├── .env.example                Environment variable template
    └── package.json
```

---

## Payment Flow

```
PlansScreen (user picks plan)
    │
    ▼
payment.js: startPayment(phone, planType)
    │
    ├─► POST /subscription/create-order  ──► Razorpay: orders.create()
    │        returns { orderId, amount }
    │
    ▼
RazorpayCheckout.open({ order_id, amount, ... })
    │   (native payment sheet on device)
    │
    ├── User pays ──────────────────────────────────────┐
    │                                                    │
    ▼                                                    ▼
payment.js: verifyPayment(...)              Razorpay Webhook
    │                                       POST /webhook/razorpay
    ▼                                           │
POST /subscription/verify-payment               ▼
    │                                   activateSubscription()
    ▼                                   (safety net if app call fails)
activateSubscription()
    │
    ▼
DynamoDB: update subscriptionEnd = now + plan days
    │
    ▼
App: subscription is active → HomeScreen unlocked
```

---

## Android Permissions Required

| Permission | How it's granted | Why needed |
|---|---|---|
| `BIND_ACCESSIBILITY_SERVICE` | User manually in Settings > Accessibility | Core — enables reading other app UI and tapping buttons |
| `SYSTEM_ALERT_WINDOW` | Special settings intent | Display overlays on top of Rapido/Ola |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Special settings intent | Keeps background service alive — without this Android kills it after ~10 min |
| `FOREGROUND_SERVICE` | Manifest only | Required to run accessibility service persistently |
| `POST_NOTIFICATIONS` | Runtime dialog (Android 13+) | Show persistent "Service Running" notification |
| `RECEIVE_BOOT_COMPLETED` | Manifest only | Auto-start service when device restarts |
| `VIBRATE` | Manifest only | Haptic feedback when ride is accepted |

### Permission Setup Screen (UX flow)
When the user first opens the app after login, show a checklist screen:
1. Accessibility Service — [ ] → button: "Enable" → opens Settings > Accessibility
2. Display Over Apps — [ ] → button: "Enable" → opens overlay settings intent
3. Battery Exemption — [ ] → button: "Enable" → opens battery settings intent
4. Notification — [ ] → button: "Allow" → runtime permission dialog

App state listener re-checks all permissions each time user returns from Settings.

---

## Backend Data Model (DynamoDB)

```
Table: primeclicker-users
Partition key: phone (String)

Item fields:
{
  phone:             "9876543210",
  uniqueId:          "MEC-96554",
  subscriptionStart: "2026-07-08T13:00:00.000Z",
  subscriptionEnd:   "2026-08-07T13:00:00.000Z",
  planType:          "monthly",
  razorpayPaymentId: "pay_XXXXXXXXXX",
  createdAt:         "2026-07-01T10:00:00.000Z"
}
```

Only 3 fields the app cares about at runtime:
- `phone` — identity
- `subscriptionEnd` — is today before this? → active
- `planType` — what to show in Profile screen

---

## AWS Deployment Steps

```bash
# 1. Install serverless framework
npm install -g serverless

# 2. Configure AWS credentials
aws configure

# 3. Install backend dependencies
cd backend && npm install

# 4. Set environment variables
cp .env.example .env
# Fill in RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET, RAZORPAY_WEBHOOK_SECRET

# 5. Deploy to AWS Lambda + DynamoDB (Mumbai)
serverless deploy --stage prod

# Output: API Gateway URL like https://xxxxxxx.execute-api.ap-south-1.amazonaws.com
# Paste this URL into src/services/api.js (BASE_URL)

# 6. Register webhook URL in Razorpay dashboard
# URL: https://xxxxxxx.execute-api.ap-south-1.amazonaws.com/webhook/razorpay
# Events: payment.captured
```

---

## Implementation Order (What to Build Next)

### Phase 1 — Core Native (most critical)
1. Run `npx expo prebuild` to generate android/ folder
2. Write `AutoClickerService.java` — the accessibility service
3. Write `AutoClickerModule.java` — React Native bridge
4. Update `AndroidManifest.xml` with service + permissions
5. Build and test on a real Android device

### Phase 2 — Payments
1. `npm install react-native-razorpay`
2. Uncomment Razorpay code in `src/services/payment.js`
3. Wire `PlansScreen.js` pay button to call `startPayment()`
4. Test with Razorpay test keys

### Phase 3 — Backend Deploy
1. Create AWS account + configure credentials
2. Run `serverless deploy` from backend/
3. Update `BASE_URL` in `src/services/api.js`
4. Register webhook URL in Razorpay dashboard
5. Test end-to-end payment + subscription activation

### Phase 4 — Polish
1. Implement `useSubscription` hook in screens
2. Paywall: redirect to PlansScreen if subscription expired
3. OTP verification (Twilio/MSG91 or Firebase Auth)
4. Referral system
