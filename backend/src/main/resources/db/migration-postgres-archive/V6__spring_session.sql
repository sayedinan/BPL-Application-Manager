-- V6 — Spring Session JDBC schema.
--
-- Source: org/springframework/session/jdbc/schema-postgresql.sql
-- bundled with spring-session-jdbc 3.3.3 (the version pulled in by
-- Spring Boot 3.3.5's BOM). Copied verbatim so the schema in this
-- database is byte-for-byte what Spring Session itself expects, and
-- so a future upgrade path is "replace this file with the next
-- version's schema-postgresql.sql" without re-deriving anything.
--
-- SPEC §3.1 notes this as "Managed by Spring Session schema (Flyway
-- V1.1)". The V1.1 numbering is impractical against the existing
-- V1–V5 history on bpl_dev (Flyway won't apply a version lower than
-- the latest applied), so this is numbered V6 to keep the migration
-- sequence monotonic. The end-state schema is identical to a V1.1
-- apply would have produced.
--
-- SPEC §8.1 (Authentication) commits this project to session-based
-- auth via HttpOnly cookie, NOT JWT. The SPRING_SESSION table backs
-- the server-side session store that the cookie references; there
-- is no token table, refresh table, or client-side expiry check
-- anywhere in this design.
--
-- Spring Boot's default `spring.session.jdbc.initialize-schema` is
-- `embedded`, which would auto-run schema-postgresql.sql on first
-- boot and collide with this Flyway-managed table. Set to `never`
-- in application-prod.yml (SPEC §13.1) and in application-dev.yml
-- so Flyway is the single source of truth for these tables.

CREATE TABLE SPRING_SESSION (
    PRIMARY_ID            CHAR(36) NOT NULL,
    SESSION_ID            CHAR(36) NOT NULL,
    CREATION_TIME         BIGINT   NOT NULL,
    LAST_ACCESS_TIME      BIGINT   NOT NULL,
    MAX_INACTIVE_INTERVAL INT      NOT NULL,
    EXPIRY_TIME           BIGINT   NOT NULL,
    PRINCIPAL_NAME        VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36)     NOT NULL,
    ATTRIBUTE_NAME     VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES    BYTEA        NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);
