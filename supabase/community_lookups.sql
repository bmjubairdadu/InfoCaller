-- Supabase setup for opt-in community lookup.
-- Run this in Supabase Dashboard > SQL Editor. Do NOT store phone numbers in plain text.

create table if not exists public.community_lookups (
  phone_hash text primary key,
  display_name text,
  report_count integer not null default 0,
  updated_at timestamptz not null default now()
);

alter table public.community_lookups enable row level security;

drop policy if exists "community_read" on public.community_lookups;
create policy "community_read"
on public.community_lookups
for select
to anon
using (true);

drop policy if exists "community_insert_hash_only" on public.community_lookups;
create policy "community_insert_hash_only"
on public.community_lookups
for insert
to anon
with check (phone_hash ~ '^[0-9a-f]{64}$');

create index if not exists community_lookups_updated_at_idx
on public.community_lookups (updated_at desc);
