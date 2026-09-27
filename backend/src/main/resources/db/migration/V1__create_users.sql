CREATE TABLE "user" (
    id            bigint       GENERATED ALWAYS AS IDENTITY,
    email         varchar(254) NOT NULL,
    password_hash varchar(100) NOT NULL,
    display_name  varchar(100) NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_user PRIMARY KEY (id)
);

CREATE UNIQUE INDEX uq_user_email ON "user" (lower(email));
