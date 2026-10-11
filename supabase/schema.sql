-- MY INVEST Supabase schema
-- Run this file in Supabase SQL Editor for a NEW project.
-- This schema stores configured plan rates; it does not guarantee or manufacture investment returns.
create extension if not exists pgcrypto;

create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  email text not null,
  full_name text not null default '',
  country text,
  date_of_birth date,
  address text,
  phone text,
  role text not null default 'investor' check (role in ('investor','admin')),
  account_status text not null default 'pending' check (account_status in ('pending','approved','rejected','resubmission_required','suspended')),
  rejection_reason text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.investment_plans (
  id uuid primary key default gen_random_uuid(),
  slug text not null unique,
  name text not null,
  icon text not null,
  minimum_amount numeric(14,2) not null check (minimum_amount > 0),
  fixed_profit_rate numeric(5,2) not null default 60.00 check (fixed_profit_rate >= 0 and fixed_profit_rate <= 100),
  profit_rule text not null default 'fixed_once' check (profit_rule = 'fixed_once'),
  is_active boolean not null default true,
  updated_at timestamptz not null default now()
);

insert into public.investment_plans (slug,name,icon,minimum_amount,fixed_profit_rate,profit_rule)
values
 ('bronze','Bronze','🥉',250.00,60.00,'fixed_once'),
 ('silver','Silver','🥈',500.00,60.00,'fixed_once'),
 ('gold','Gold','🥇',1000.00,60.00,'fixed_once'),
 ('platinum','Platinum','💎',5000.00,60.00,'fixed_once'),
 ('diamond','Diamond','💠',10000.00,60.00,'fixed_once'),
 ('elite','Elite','👑',25000.00,60.00,'fixed_once')
on conflict (slug) do update set
 name=excluded.name, icon=excluded.icon, minimum_amount=excluded.minimum_amount,
 fixed_profit_rate=excluded.fixed_profit_rate, profit_rule=excluded.profit_rule;

create table if not exists public.wallet_accounts (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  deposited_amount numeric(14,2) not null default 0 check (deposited_amount >= 0),
  capital_amount numeric(14,2) not null default 0 check (capital_amount >= 0),
  profit_amount numeric(14,2) not null default 0 check (profit_amount >= 0),
  withdrawals_amount numeric(14,2) not null default 0 check (withdrawals_amount >= 0),
  updated_at timestamptz not null default now()
);

create table if not exists public.investments (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  plan_id uuid not null references public.investment_plans(id),
  principal_amount numeric(14,2) not null check (principal_amount > 0),
  fixed_profit_rate numeric(5,2) not null default 60.00 check (fixed_profit_rate >= 0 and fixed_profit_rate <= 100),
  fixed_profit_amount numeric(14,2) not null default 0 check (fixed_profit_amount >= 0),
  status text not null default 'pending' check (status in ('pending','active','completed','rejected','cancelled')),
  profit_verification_status text not null default 'not_verified' check (profit_verification_status in ('not_verified','verified','rejected')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.transactions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  transaction_type text not null check (transaction_type in ('deposit','investment','profit_credit','withdrawal_request','withdrawal','admin_adjustment','refund')),
  amount numeric(14,2) not null check (amount >= 0),
  status text not null default 'pending' check (status in ('pending','posted','rejected','cancelled')),
  description text not null default '',
  reference text unique,
  created_by uuid references auth.users(id),
  created_at timestamptz not null default now()
);

create table if not exists public.kyc_submissions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  document_type text not null,
  id_image_path text not null,
  face_image_path text not null,
  status text not null default 'pending' check (status in ('pending','approved','rejected','resubmission_required')),
  review_reason text,
  reviewed_by uuid references auth.users(id),
  reviewed_at timestamptz,
  created_at timestamptz not null default now()
);

create table if not exists public.admin_audit_log (
  id bigint generated always as identity primary key,
  admin_user_id uuid not null references auth.users(id),
  target_user_id uuid references auth.users(id),
  action text not null,
  details jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create or replace function public.is_admin()
returns boolean
language sql stable security definer
set search_path = public
as $$
  select exists (
    select 1 from public.profiles
    where id = auth.uid() and role = 'admin' and account_status = 'approved'
  );
$$;

create or replace function public.handle_new_user()
returns trigger
language plpgsql security definer
set search_path = public
as $$
begin
  insert into public.profiles (id,email,full_name)
  values (new.id, coalesce(new.email,''), coalesce(new.raw_user_meta_data->>'full_name',''))
  on conflict (id) do nothing;
  insert into public.wallet_accounts (user_id) values (new.id) on conflict (user_id) do nothing;
  return new;
end;
$$;

drop trigger if exists on_auth_user_created_myinvest on auth.users;
create trigger on_auth_user_created_myinvest
after insert on auth.users
for each row execute procedure public.handle_new_user();

create or replace function public.admin_adjust_wallet(
  p_user_id uuid, p_field text, p_new_amount numeric, p_reason text
)
returns void
language plpgsql security definer
set search_path = public
as $$
declare
  old_amount numeric(14,2);
begin
  if not public.is_admin() then raise exception 'Administrator access required'; end if;
  if p_field not in ('deposited_amount','capital_amount','profit_amount','withdrawals_amount') then
    raise exception 'Unsupported balance field';
  end if;
  if p_new_amount is null or p_new_amount < 0 then raise exception 'Amount must be non-negative'; end if;
  if p_reason is null or length(trim(p_reason)) < 4 then raise exception 'A reason is required'; end if;
  insert into public.wallet_accounts(user_id) values (p_user_id) on conflict (user_id) do nothing;
  select case p_field
    when 'deposited_amount' then deposited_amount
    when 'capital_amount' then capital_amount
    when 'profit_amount' then profit_amount
    else withdrawals_amount
  end into old_amount
  from public.wallet_accounts where user_id = p_user_id for update;
  if p_field = 'deposited_amount' then
    update public.wallet_accounts set deposited_amount=p_new_amount,updated_at=now() where user_id=p_user_id;
  elsif p_field = 'capital_amount' then
    update public.wallet_accounts set capital_amount=p_new_amount,updated_at=now() where user_id=p_user_id;
  elsif p_field = 'profit_amount' then
    update public.wallet_accounts set profit_amount=p_new_amount,updated_at=now() where user_id=p_user_id;
  else
    update public.wallet_accounts set withdrawals_amount=p_new_amount,updated_at=now() where user_id=p_user_id;
  end if;
  insert into public.admin_audit_log(admin_user_id,target_user_id,action,details)
  values(auth.uid(),p_user_id,'wallet_balance_changed',
    jsonb_build_object('field',p_field,'old_amount',old_amount,'new_amount',p_new_amount,'reason',trim(p_reason)));
  insert into public.transactions(user_id,transaction_type,amount,status,description,created_by)
  values(p_user_id,'admin_adjustment',p_new_amount,'posted',
    p_field || ' set by administrator: ' || trim(p_reason),auth.uid());
end;
$$;

create or replace function public.admin_set_profile_status(
  p_user_id uuid, p_status text, p_reason text default null
)
returns void
language plpgsql security definer
set search_path = public
as $$
declare old_status text;
begin
  if not public.is_admin() then raise exception 'Administrator access required'; end if;
  if p_status not in ('pending','approved','rejected','resubmission_required','suspended') then
    raise exception 'Unsupported account status';
  end if;
  select account_status into old_status from public.profiles where id=p_user_id for update;
  if old_status is null then raise exception 'Profile not found'; end if;
  update public.profiles set account_status=p_status,rejection_reason=p_reason,updated_at=now() where id=p_user_id;
  insert into public.admin_audit_log(admin_user_id,target_user_id,action,details)
  values(auth.uid(),p_user_id,'account_status_changed',
    jsonb_build_object('old_status',old_status,'new_status',p_status,'reason',p_reason));
end;
$$;

alter table public.profiles enable row level security;
alter table public.investment_plans enable row level security;
alter table public.wallet_accounts enable row level security;
alter table public.investments enable row level security;
alter table public.transactions enable row level security;
alter table public.kyc_submissions enable row level security;
alter table public.admin_audit_log enable row level security;

drop policy if exists "profiles read own or admin" on public.profiles;
create policy "profiles read own or admin" on public.profiles for select to authenticated
using (id=auth.uid() or public.is_admin());
drop policy if exists "profiles update own profile" on public.profiles;
create policy "profiles update own profile" on public.profiles for update to authenticated
using (id=auth.uid() and role='investor' and account_status <> 'suspended')
with check (id=auth.uid() and role='investor');
revoke update on public.profiles from authenticated;
grant update (full_name,country,date_of_birth,address,phone) on public.profiles to authenticated;

drop policy if exists "plans visible to signed in users" on public.investment_plans;
create policy "plans visible to signed in users" on public.investment_plans for select to authenticated using (is_active or public.is_admin());
drop policy if exists "plans admin writes" on public.investment_plans;
create policy "plans admin writes" on public.investment_plans for all to authenticated using (public.is_admin()) with check (public.is_admin());

drop policy if exists "wallet read own or admin" on public.wallet_accounts;
create policy "wallet read own or admin" on public.wallet_accounts for select to authenticated using (user_id=auth.uid() or public.is_admin());
revoke insert, update, delete on public.wallet_accounts from authenticated;

drop policy if exists "investments read own or admin" on public.investments;
create policy "investments read own or admin" on public.investments for select to authenticated using (user_id=auth.uid() or public.is_admin());
drop policy if exists "investor may request investment" on public.investments;
create policy "investor may request investment" on public.investments for insert to authenticated
with check (
  user_id=auth.uid() and status='pending' and fixed_profit_rate=60.00
  and exists(select 1 from public.profiles where id=auth.uid() and account_status='approved')
  and exists(select 1 from public.investment_plans where id=plan_id and is_active and principal_amount >= minimum_amount)
);
drop policy if exists "admin manages investments" on public.investments;
create policy "admin manages investments" on public.investments for all to authenticated using (public.is_admin()) with check (public.is_admin());

drop policy if exists "transactions read own or admin" on public.transactions;
create policy "transactions read own or admin" on public.transactions for select to authenticated using (user_id=auth.uid() or public.is_admin());
drop policy if exists "investor creates withdrawal request" on public.transactions;
create policy "investor creates withdrawal request" on public.transactions for insert to authenticated
with check (user_id=auth.uid() and transaction_type='withdrawal_request' and status='pending' and amount > 0);
drop policy if exists "admin manages transactions" on public.transactions;
create policy "admin manages transactions" on public.transactions for all to authenticated using (public.is_admin()) with check (public.is_admin());

drop policy if exists "KYC read own or admin" on public.kyc_submissions;
create policy "KYC read own or admin" on public.kyc_submissions for select to authenticated using (user_id=auth.uid() or public.is_admin());
drop policy if exists "investor submits KYC" on public.kyc_submissions;
create policy "investor submits KYC" on public.kyc_submissions for insert to authenticated
with check (user_id=auth.uid() and status='pending');
drop policy if exists "admin reviews KYC" on public.kyc_submissions;
create policy "admin reviews KYC" on public.kyc_submissions for update to authenticated
using (public.is_admin()) with check (public.is_admin());

drop policy if exists "audit log admin read only" on public.admin_audit_log;
create policy "audit log admin read only" on public.admin_audit_log for select to authenticated using (public.is_admin());
revoke insert, update, delete on public.admin_audit_log from authenticated;

revoke all on function public.admin_adjust_wallet(uuid,text,numeric,text) from public, anon;
revoke all on function public.admin_set_profile_status(uuid,text,text) from public, anon;
grant execute on function public.admin_adjust_wallet(uuid,text,numeric,text) to authenticated;
grant execute on function public.admin_set_profile_status(uuid,text,text) to authenticated;

-- Private KYC storage bucket. Create the bucket via Dashboard or Storage API:
-- id/name: kyc-documents; Public bucket: OFF.
drop policy if exists "KYC objects read own or admin" on storage.objects;
create policy "KYC objects read own or admin" on storage.objects for select to authenticated
using (bucket_id='kyc-documents' and (name like auth.uid()::text || '/%' or public.is_admin()));
drop policy if exists "investor uploads own KYC objects" on storage.objects;
create policy "investor uploads own KYC objects" on storage.objects for insert to authenticated
with check (bucket_id='kyc-documents' and name like auth.uid()::text || '/%');
drop policy if exists "admin manages KYC objects" on storage.objects;
create policy "admin manages KYC objects" on storage.objects for all to authenticated
using (bucket_id='kyc-documents' and public.is_admin())
with check (bucket_id='kyc-documents' and public.is_admin());

-- IMPORTANT: promote exactly one trusted user to admin manually in the Supabase SQL Editor
-- only after that user has signed up. Replace the email with the owner's exact login email:
-- update public.profiles set role='admin', account_status='approved'
-- where id=(select id from auth.users where email='YOUR_ADMIN_EMAIL');
-- Verify exactly one row was updated. Never expose the service_role key in the Android app.
