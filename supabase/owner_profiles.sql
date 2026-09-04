-- InfoCaller owner-consent schema (Supabase Postgres, Free tier).
-- Principle: Contacts permission != publish consent. Only number owner can publish own profile.

create extension if not exists "pgcrypto";

create table if not exists public.owner_profiles (
  phone_hash text primary key check (phone_hash ~ '^[0-9a-f]{64}$'),
  phone_e164_enc text not null,
  display_name text not null check (char_length(display_name) between 2 and 80),
  photo_url text,
  business_name text,
  business_category text,
  country text,
  is_business boolean not null default false,
  visibility text not null default 'public' check (visibility in ('public','unlisted','private')),
  consent_granted boolean not null default false,
  consent_version integer not null default 1,
  consent_at timestamptz,
  verified boolean not null default false,
  verified_at timestamptz,
  spam_score integer not null default 0,
  report_count integer not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.otp_verifications (
  id bigserial primary key,
  phone_hash text not null,
  code_hash text not null,
  expires_at timestamptz not null,
  attempts integer not null default 0,
  consumed boolean not null default false,
  created_at timestamptz not null default now()
);
create index if not exists otp_phone_hash_idx on public.otp_verifications (phone_hash, created_at desc);

create table if not exists public.owner_sessions (
  token_hash text primary key,
  phone_hash text not null references public.owner_profiles(phone_hash) on delete cascade,
  expires_at timestamptz not null,
  created_at timestamptz not null default now()
);

create table if not exists public.consent_audits (
  id bigserial primary key,
  phone_hash text not null,
  action text not null check (action in ('request_otp','verify_ok','verify_fail','publish','update','revoke','visibility','delete')),
  detail text,
  ip text,
  created_at timestamptz not null default now()
);
create index if not exists audits_phone_idx on public.consent_audits (phone_hash, created_at desc);

create table if not exists public.spam_reports (
  id bigserial primary key,
  phone_hash text not null,
  reporter_hash text,
  reason text check (reason in ('spam','scam','telemarketing','abuse','other')),
  created_at timestamptz not null default now()
);
create index if not exists spam_phone_idx on public.spam_reports (phone_hash, created_at desc);

-- Legacy community table kept for read-only migration.
create table if not exists public.community_lookups (
  phone_hash text primary key,
  display_name text,
  report_count integer not null default 0,
  updated_at timestamptz not null default now()
);

alter table public.owner_profiles enable row level security;
alter table public.otp_verifications enable row level security;
alter table public.owner_sessions enable row level security;
alter table public.consent_audits enable row level security;
alter table public.spam_reports enable row level security;
alter table public.community_lookups enable row level security;

-- No direct anon write. Backend uses service_role. Anon can read ONLY public verified consented profiles.
drop policy if exists "owner_public_read" on public.owner_profiles;
create policy "owner_public_read"
on public.owner_profiles for select to anon
using (verified = true and consent_granted = true and visibility = 'public');

drop policy if exists "community_read" on public.community_lookups;
create policy "community_read" on public.community_lookups for select to anon using (true);
