CREATE TABLE IF NOT EXISTS users (
                       id       INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                       username VARCHAR(50)  NOT NULL UNIQUE,
                       password VARCHAR(500) NOT NULL,
                       enabled  BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS authorities (
                             id        INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                             username  VARCHAR(50) NOT NULL REFERENCES users (username),
                             authority VARCHAR(50) NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ix_auth_username ON authorities (username, authority);

CREATE TABLE IF NOT EXISTS stations (
                          station_id  INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                          coord_x     DOUBLE PRECISION NOT NULL,
                          coord_y     DOUBLE PRECISION NOT NULL,
                          radius      DOUBLE PRECISION NOT NULL,
                          robot_count INTEGER NOT NULL DEFAULT 0 CHECK (robot_count >= 0),
                          drone_count INTEGER NOT NULL DEFAULT 0 CHECK (drone_count >= 0)
);

CREATE TABLE IF NOT EXISTS orders (
                        order_id           INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                        user_id            INTEGER NOT NULL REFERENCES users (id),
                        destination        VARCHAR(500) NOT NULL,
                        package_weight_lbs DOUBLE PRECISION NOT NULL CHECK (package_weight_lbs > 0),
                        price              DOUBLE PRECISION NOT NULL CHECK (price >= 0),
                        time DOUBLE PRECISION NOT NULL CHECK (time >= 0),
                        vehicle            VARCHAR(20) NOT NULL CHECK (vehicle IN ('ROBOT', 'DRONE')),
                        station_id         INTEGER NOT NULL REFERENCES stations (station_id),
                        status             VARCHAR(30) NOT NULL DEFAULT 'PENDING_DROPOFF'
                            CHECK (status IN ('PENDING_DROPOFF', 'AT_STATION', 'BEFORE_HALF_WAY',
                                              'HALF_WAY', 'MORE_THAN_HALF_WAY', 'DELIVERED', 'CANCELLED')),
                        created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);