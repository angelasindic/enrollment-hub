-- Spring Security JDBC user store, adapted from the default users.ddl shipped in spring-security-core
-- (varchar_ignorecase is HSQLDB-specific -> varchar for PostgreSQL). Backs JdbcUserDetailsManager.
--
-- The demo user is seeded here with a BCrypt-encoded password (ADR-05): username 'user',
-- password 'password'. This is a sandbox credential — replace with a real user store / identity
-- federation outside the demo.

CREATE TABLE users (
    username varchar(50)  NOT NULL PRIMARY KEY,
    password varchar(500) NOT NULL,
    enabled  boolean      NOT NULL
);

CREATE TABLE authorities (
    username  varchar(50) NOT NULL,
    authority varchar(50) NOT NULL,
    CONSTRAINT fk_authorities_users FOREIGN KEY (username) REFERENCES users (username)
);

CREATE UNIQUE INDEX ix_auth_username ON authorities (username, authority);

INSERT INTO users (username, password, enabled)
VALUES ('user', '{bcrypt}$2a$10$L8Y9ntbrvh0rG71Em2oD3OLUJq7IrTp3KJ1xmkho7sOlq/BYN0CPu', true);

INSERT INTO authorities (username, authority)
VALUES ('user', 'ROLE_USER');
