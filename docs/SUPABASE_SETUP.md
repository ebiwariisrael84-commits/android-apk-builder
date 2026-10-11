# MY INVEST — Supabase backend setup

This project currently has a local-only Android account system. The SQL migration establishes the backend schema and access rules, but the Android app is not connected until its public Supabase Project URL and publishable/anon key are configured and the client integration is implemented and tested.

## 1. Create the Supabase project
1. Open https://supabase.com/dashboard and sign in.
2. Create a new project named `my-invest` (or another name you choose).
3. Save the database password privately. Do not send it in chat or commit it to GitHub.
4. In Project Settings → API (or Connect), copy the Project URL and the publishable/anon client key. These are client credentials; never use the `service_role` secret in the APK.

## 2. Create database tables and policies
1. Open SQL Editor in the Supabase dashboard.
2. Open `supabase/schema.sql` in this repository and copy its entire contents.
3. Paste it into a new SQL query and Run.
4. Confirm the query completes without errors.
5. Open Storage and create a bucket named `kyc-documents`; keep it PRIVATE (public access off).

The schema creates six plans with a fixed configured rate of 60% and a `fixed_once` rule. This is configuration, not a guarantee of earnings. Profit should only be credited when supported by verified, actual performance.

## 3. Create the one administrator account
1. In Supabase Authentication → Users, create the owner account or use the normal app signup after client authentication is connected.
2. Copy the exact email used by the owner.
3. In SQL Editor, run the final admin-promotion statement at the bottom of `supabase/schema.sql`, replacing `YOUR_ADMIN_EMAIL` with that exact email.
4. Verify exactly one profile row was updated. Do not promote investor accounts or expose a service-role secret.
5. Configure Auth → URL Configuration and email settings for your app's password reset and approval notifications as needed.

Do not add admin registration to the public app. Admin status is assigned only by the project owner from the trusted dashboard.

## 4. App connection credentials
The Android client should only use the Project URL and publishable/anon key. The database schema uses row-level security. The app must use authenticated Supabase sessions; the anon key alone does not grant admin access.

Do not post database passwords, access tokens, service-role keys, or email-provider secrets in chat or source code. We will add the Android client integration after the project URL and publishable/anon key are available.

## 5. Add build configuration to GitHub Actions
After creating the Supabase project, open this repository on GitHub:
1. Open Settings → Secrets and variables → Actions.
2. Choose New repository secret.
3. Add `SUPABASE_URL` with the Project URL.
4. Add `SUPABASE_ANON_KEY` with the publishable/anon client key.
5. Save each secret. Do not add a `service_role` key.

The Android Gradle configuration and build workflow now accept these values at build time. The runtime API integration is still a separate step; adding secrets alone does not connect sign-in or account data.

## Current limitations
- The existing app still uses local SharedPreferences for sign-in, balances and transaction history.
- This SQL file does not itself connect the APK to Supabase.
- Approval emails require an email/Edge Function provider and must be tested.
- Real deposits and withdrawals require a payment provider and verified server-side webhooks.
- KYC documents must remain private and should be accessed only by the owner/admin through short-lived authorized access.
- Build/test status must be checked after the Android client integration; do not treat a successful APK build alone as proof that the backend works.
