CREATE TABLE project (
    id          bigint        GENERATED ALWAYS AS IDENTITY,
    owner_id    bigint        NOT NULL,
    name        varchar(100)  NOT NULL,
    description varchar(1000),
    created_at  timestamptz   NOT NULL DEFAULT now(),
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_project PRIMARY KEY (id),
    CONSTRAINT fk_project_owner FOREIGN KEY (owner_id) REFERENCES "user" (id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uq_project_owner_name ON project (owner_id, lower(name));
