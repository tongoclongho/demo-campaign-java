
CREATE SEQUENCE IF NOT EXISTS campaign_user_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS campaign_user  (
    id                bigint        NOT NULL DEFAULT nextval('campaign_user_seq'),
    email             varchar(255)  NOT NULL,
    loyalty_points    integer       NOT NULL DEFAULT 0,
    created           timestamp,
    last_modified     timestamp,
    CONSTRAINT pk_user PRIMARY KEY (id),
    CONSTRAINT uk_user_email UNIQUE (email)
);


CREATE SEQUENCE IF NOT EXISTS campaign_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS campaign (
    id                bigint        NOT NULL DEFAULT nextval('campaign_seq'),
    code              varchar(255)  NOT NULL,
    total_capacity    integer       NOT NULL DEFAULT 1,
    created           timestamp,
    last_modified     timestamp,
    CONSTRAINT pk_campaign PRIMARY KEY (id),
    CONSTRAINT uk_campaign_code UNIQUE (code)
);


CREATE SEQUENCE IF NOT EXISTS claim_campaign_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE IF NOT EXISTS claim_campaign (
    id                bigint        NOT NULL DEFAULT nextval('claim_campaign_seq'),
    campaign_id       bigint,
    user_id           bigint,
    claim_date        timestamp,
    status            varchar(255),
    reason            varchar(255),
    created           timestamp,
    last_modified     timestamp,
    CONSTRAINT pk_claim_campaign PRIMARY KEY (id)
);


CREATE INDEX ix_campaign_id ON claim_campaign (campaign_id);