# SUPER RIDEX Backend

Express + **PostgreSQL** + Razorpay + Admin panel.

Deploy to Railway when the project is ready. Local development does not require AWS/DynamoDB.

## Quick start (local)

1. Create a Postgres database.
2. Copy env:

```bash
cp .env.example .env
# edit DATABASE_URL + Razorpay keys + ADMIN_JWT_SECRET
```

3. Install & migrate:

```bash
npm install
npm run migrate
npm run seed:admin -- admin@example.com 'YourStrongPassword'
npm run dev
```

4. Open admin panel: [http://localhost:3000/admin](http://localhost:3000/admin)  
5. Health: [http://localhost:3000/health](http://localhost:3000/health)

## Mobile app

Set in the app root `.env`:

```
EXPO_PUBLIC_API_URL=http://YOUR_LAN_IP:3000
```

(Use your machine LAN IP for a physical Android device.)

## Main APIs

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/referral/register` | Create user + bind device |
| GET | `/subscription/status` | Sub status + device entitlement |
| POST | `/entitlement/check` | Gate for auto-accept ON |
| POST | `/subscription/create-order` | Razorpay order |
| POST | `/subscription/verify-payment` | Verify + activate |
| POST | `/webhook/razorpay` | Razorpay webhook |
| POST | `/admin/api/login` | Admin login |
| GET | `/admin/api/users` | Search users |
| PATCH | `/admin/api/users/:phone` | Grant plan / max devices / block |
| POST | `/admin/api/users/:phone/devices/reset` | Unbind all devices |

## Admin device-change playbook

1. Open `/admin` → search phone  
2. **Reset all devices** (user moved to a new phone), **or** raise max devices to 2–5  
3. User opens the app on the new phone → device binds automatically  

## Railway (later)

1. New project + PostgreSQL plugin  
2. Deploy this `backend/` folder (Dockerfile runs migrate then start)  
3. Set `DATABASE_URL`, `DATABASE_SSL=true`, Razorpay keys, `ADMIN_JWT_SECRET`  
4. Seed admin once via Railway shell: `npm run seed:admin -- ...`  
5. Point Razorpay webhook to `https://<url>/webhook/razorpay`  
6. Set app `EXPO_PUBLIC_API_URL` to the Railway URL and rebuild the APK  
