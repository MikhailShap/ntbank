-- =====================================================================
-- НТ-Банк. Базовая схема.
-- Таблица "app_user" — потому что "user" в PostgreSQL зарезервировано.
-- =====================================================================

create table app_user
(
    id            bigserial primary key,
    login         varchar(64)  not null unique,
    password_hash varchar(100) not null,
    full_name     varchar(160) not null,
    phone         varchar(20)  not null unique,
    role          varchar(20)  not null default 'USER',
    enabled       boolean      not null default true,
    created_at    timestamp    not null default now(),
    last_login_at timestamp
);

create table account
(
    id        bigserial primary key,
    user_id   bigint        not null references app_user (id) on delete cascade,
    number    varchar(20)   not null unique,
    title     varchar(60)   not null,
    currency  varchar(3)    not null,
    balance   numeric(19, 2) not null default 0,
    opened_at date          not null default current_date,
    active    boolean       not null default true
);
create index idx_account_user on account (user_id);

create table card
(
    id         bigserial primary key,
    account_id bigint      not null references account (id) on delete cascade,
    user_id    bigint      not null references app_user (id) on delete cascade,
    pan_masked varchar(25) not null,
    holder     varchar(60) not null,
    expires    varchar(5)  not null,
    kind       varchar(16) not null default 'DEBIT',
    status     varchar(16) not null default 'ACTIVE'
);
create index idx_card_user on card (user_id);

-- История операций. Индекс (account_id, created_at desc) используется "быстрой" выпиской;
-- дефект "Медленный SQL" выполняет запрос так, что этот индекс не применяется.
create table operation
(
    id           bigserial primary key,
    account_id   bigint         not null references account (id) on delete cascade,
    created_at   timestamp      not null,
    direction    varchar(4)     not null,
    kind         varchar(24)    not null,
    amount       numeric(19, 2) not null,
    currency     varchar(3)     not null,
    description  varchar(160)   not null,
    counterparty varchar(120),
    status       varchar(16)    not null default 'OK'
);
create index idx_operation_account_date on operation (account_id, created_at desc);

create table service_catalog
(
    id                 bigserial primary key,
    code               varchar(32)    not null unique,
    name               varchar(120)   not null,
    category           varchar(40)    not null,
    provider           varchar(80)    not null,
    account_label      varchar(60)    not null,
    min_amount         numeric(19, 2) not null default 1,
    max_amount         numeric(19, 2) not null default 100000,
    commission_percent numeric(5, 2)  not null default 0,
    active             boolean        not null default true
);
create index idx_service_name on service_catalog (lower(name));
create index idx_service_category on service_catalog (category);

create table transfer
(
    id                uuid primary key,
    type              varchar(10)    not null,
    status            varchar(16)    not null,
    user_id           bigint         not null references app_user (id) on delete cascade,
    from_account_id   bigint         not null references account (id),
    to_account_id     bigint references account (id),
    to_phone          varchar(20),
    to_user_id        bigint references app_user (id),
    to_name           varchar(160),
    amount            numeric(19, 2) not null,
    currency          varchar(3)     not null,
    credited_amount   numeric(19, 2),
    credited_currency varchar(3),
    rate              numeric(12, 4),
    comment           varchar(160),
    otp_code          varchar(6),
    otp_attempts      int            not null default 0,
    created_at        timestamp      not null,
    confirmed_at      timestamp,
    completed_at      timestamp,
    reject_reason     varchar(200),
    idempotency_key   varchar(120)
);
create index idx_transfer_user on transfer (user_id, created_at desc);
create index idx_transfer_status on transfer (status);

create table payment
(
    id               bigserial primary key,
    user_id          bigint         not null references app_user (id) on delete cascade,
    account_id       bigint         not null references account (id),
    service_id       bigint         not null references service_catalog (id),
    personal_account varchar(40)    not null,
    amount           numeric(19, 2) not null,
    currency         varchar(3)     not null,
    status           varchar(16)    not null,
    created_at       timestamp      not null,
    idempotency_key  varchar(120)
);
create index idx_payment_user on payment (user_id, created_at desc);

create table payment_template
(
    id               bigserial primary key,
    user_id          bigint         not null references app_user (id) on delete cascade,
    name             varchar(80)    not null,
    service_id       bigint         not null references service_catalog (id),
    account_id       bigint         not null references account (id),
    personal_account varchar(40)    not null,
    amount           numeric(19, 2) not null,
    created_at       timestamp      not null,
    last_used_at     timestamp
);
create index idx_template_user on payment_template (user_id);

-- "Тестовые СМС": сюда падают OTP-коды, страница /dev/sms их показывает
create table sms_message
(
    id         bigserial primary key,
    phone      varchar(20)  not null,
    text       varchar(240) not null,
    code       varchar(6),
    created_at timestamp    not null
);
create index idx_sms_phone on sms_message (phone, created_at desc);

create table idempotency_record
(
    id            bigserial primary key,
    user_id       bigint       not null,
    scope         varchar(32)  not null,
    idem_key      varchar(120) not null,
    http_status   int          not null,
    response_body text         not null,
    created_at    timestamp    not null,
    constraint uq_idempotency unique (user_id, scope, idem_key)
);

create table exchange_rate
(
    currency    varchar(3) primary key,
    rate_to_rub numeric(12, 4) not null,
    updated_at  timestamp      not null default now()
);

insert into exchange_rate (currency, rate_to_rub)
values ('RUB', 1.0000),
       ('USD', 92.5000);
