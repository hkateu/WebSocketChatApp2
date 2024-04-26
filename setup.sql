CREATE DATABASE websocket;

\c websocket;

CREATE TABLE users (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL
);

CREATE TABLE rooms (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL
);

CREATE TABLE messages (
    id UUID PRIMARY KEY,
    message TEXT NOT NULL,
    time TIMESTAMP DEFAULT NOW(),
    user_id UUID REFERENCES users (id),
    room_id UUID REFERENCES rooms (id)
);