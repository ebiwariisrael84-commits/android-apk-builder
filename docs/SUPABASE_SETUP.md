# MY INVEST — Supabase backend setup

This project currently has a local-only Android account system. The Android client now contains Supabase Auth/REST calls with transient connection retries, cloud wallet and transaction reads, validated investment/withdrawal requests, profile updates, private KYC upload, and admin review calls. The live service still depends on running the SQL below in your Supabase project, creating the private storage bucket, and verifying the Actions secrets.

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
5. The schema configures storage access policies. Create a bucket named `kyc-documents` in Storage and keep it PRIVATE (public access off) if it does not already exist.

The schema creates six plans with a configured 60% rate and a `fixed_once` rule, plus validated request RPCs and audited administrator routines. This is configuration, not a guarantee of earnings. The app displays profit separately from deposited capital; do not credit profit unless supported by verified actual performance. If you already ran an earlier version of this schema, rerun the latest complete `supabase/schema.sql` so the new functions and policies are installed.

## 3. Create the one administrator account
1. In Supabase Authentication → Users, create the owner account or use the normal app signup after client authentication is connected.
2. Copy the exact email used by the owner.
3. In SQL Editor, run the final admin-promotion statement at the bottom of `supabase/schema.sql`, replacing `YOUR_ADMIN_EMAIL` with that exact email.
4. Verify exactly one profile row was updated. Do not promote investor accounts or expose a service-role secret.
5. Configure Auth → URL Configuration and email settings for your app's password reset and approval notifications as needed.

Do not add admin registration to the public app. Admin status is assigned only by the project owner from the trusted dashboard.

## 4. App connection credentials
The Android client only uses the Project URL and publishable/anon key. The app uses authenticated Supabase sessions; the anon key alone does not grant admin access. The workflow now fails early if either Actions secret is missing or the URL is not a Supabase HTTPS project URL.

Do not post database passwords, access tokens, service-role keys, or email-provider secrets in chat or source code. We will add the Android client integration after the project URL and publishable/anon key are available.

## 5. Add build configuration to GitHub Actions
After creating the Supabase project, open this repository on GitHub:
1. Open Settings → Secrets and variables → Actions.
2. Choose New repository secret.
3. Add `SUPABASE_URL` with the Project URL.
4. Add `SUPABASE_ANON_KEY` with the publishable/anon client key.
5. Save each secret. Do not add a `service_role` key.

The Android Gradle configuration and build workflow now accept these values at build time. The runtime API integration is still a separate step; adding secrets alone does not connect sign-in or account data.

## Account notification emails
- The app now requests password-reset emails through Supabase Auth and handles the `myinvest://reset-password` deep link to set a new password.
- The repository includes `supabase/functions/admin-notify/index.ts`. Deploy it as the `admin-notify` Edge Function with JWT verification enabled.
- Configure Edge Function secrets `RESEND_API_KEY` and `FROM_EMAIL`; verify the sender domain with Resend. The function checks the caller's live session and approved admin role before sending account-status emails.
- Approval/rejection emails will not send until the function is deployed, Resend is configured, and the account's email settings are tested.

## Current limitations
- The schema must still be executed in your live Supabase project and the private `kyc-documents` bucket must exist.
- Your administrator role must be assigned manually to the trusted owner account in the Supabase SQL Editor.
- End-to-end login, password reset, email delivery, private document access, plan editing and cross-device behavior require live tests after setup.
- Deposit collection and actual withdrawal payouts are intentionally not connected to a payment provider. Requests can be submitted and reviewed, but approval does not transfer money.e app records requests; an administrator-approved withdrawal still requires a real payment provider/manual payout and verified server-side reconciliation.
- KYC images are uploaded to a private bucket and admin image links expire after five minutes. Create the `kyc-documents` bucket as private before testing.
- The database schema must be executed, the owner must be manually promoted to admin, and login/KYC/permissions must be tested against the live Supabase project. A successful APK build alone does not prove the live backend works.
