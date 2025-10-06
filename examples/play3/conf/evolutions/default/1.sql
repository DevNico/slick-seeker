# Users schema

# --- !Ups

CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    age INT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_users_name ON users(name);

INSERT INTO users (email, name, age, created_at, updated_at) VALUES
    ('alice@example.com', 'Alice Anderson', 25, TIMESTAMP '2024-01-01 10:00:00', TIMESTAMP '2024-01-01 10:00:00'),
    ('bob@example.com', 'Bob Barnes', 30, TIMESTAMP '2024-01-02 10:00:00', TIMESTAMP '2024-01-02 10:00:00'),
    ('charlie@example.com', 'Charlie Clark', 35, TIMESTAMP '2024-01-03 10:00:00', TIMESTAMP '2024-01-03 10:00:00'),
    ('diana@example.com', 'Diana Dove', 28, TIMESTAMP '2024-01-04 10:00:00', TIMESTAMP '2024-01-04 10:00:00'),
    ('eve@example.com', 'Eve Evans', 32, TIMESTAMP '2024-01-05 10:00:00', TIMESTAMP '2024-01-05 10:00:00'),
    ('frank@example.com', 'Frank Fisher', 45, TIMESTAMP '2024-01-06 10:00:00', TIMESTAMP '2024-01-06 10:00:00'),
    ('grace@example.com', 'Grace Green', 29, TIMESTAMP '2024-01-07 10:00:00', TIMESTAMP '2024-01-07 10:00:00'),
    ('henry@example.com', 'Henry Hill', 31, TIMESTAMP '2024-01-08 10:00:00', TIMESTAMP '2024-01-08 10:00:00'),
    ('iris@example.com', 'Iris Irwin', 27, TIMESTAMP '2024-01-09 10:00:00', TIMESTAMP '2024-01-09 10:00:00'),
    ('jack@example.com', 'Jack Jones', 33, TIMESTAMP '2024-01-10 10:00:00', TIMESTAMP '2024-01-10 10:00:00');

# --- !Downs

DROP TABLE IF EXISTS users;
