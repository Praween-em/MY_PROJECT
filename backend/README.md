# AG rider Backend

Express + **PostgreSQL** + Telegram-assisted payments + Admin panel.

Deploy to Railway when the project is ready. Local development does not require AWS/DynamoDB.

## Quick start (local)

1. Create a Postgres database.
2. Copy env:

```bash
cp .env.example .env
# edit DATABASE_URL + ADMIN_JWT_SECRET
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
| GET | `/payment-contact` | Telegram payment link + image state |
| GET | `/payment-contact/image` | Current payment-screen image |
| POST | `/admin/api/login` | Admin login |
| GET | `/admin/api/users` | Search users |
| PATCH | `/admin/api/users/:phone` | Grant plan / max devices / block |
| POST | `/admin/api/users/:phone/devices/reset` | Unbind all devices |
| PUT | `/admin/api/payment-contact` | Update Telegram payment link |
| POST | `/admin/api/payment-contact/image` | Upload payment-screen image |
| DELETE | `/admin/api/payment-contact/image` | Remove payment-screen image |

## Telegram payment workflow

1. Open `/admin` → **Payment Contact**.
2. Set the `https://t.me/...` destination.
3. Upload an optional PNG, JPEG, or WebP image up to 2 MB.
4. The user chooses a plan and taps **Continue on Telegram**.
5. Confirm payment in Telegram.
6. Search the user's phone in `/admin` and grant the selected plan.

## Admin device-change playbook

1. Open `/admin` → search phone  
2. **Reset all devices** (user moved to a new phone), **or** raise max devices to 2–5  
3. User opens the app on the new phone → device binds automatically  

## Railway (later)

1. New project + PostgreSQL plugin  
2. Deploy this `backend/` folder (Dockerfile runs migrate then start)  
3. Set `DATABASE_URL`, `DATABASE_SSL=true`, `ADMIN_JWT_SECRET`
4. Seed admin once via Railway shell: `npm run seed:admin -- ...`  
5. Configure the Telegram link and image in the admin panel.
6. Set app `EXPO_PUBLIC_API_URL` to the Railway URL and rebuild the APK.
