-- ============================================================
-- 1. 清空 orders 和 stations 表的数据（保留表结构）
-- ============================================================
TRUNCATE TABLE orders RESTART IDENTITY CASCADE;
TRUNCATE TABLE stations RESTART IDENTITY CASCADE;


-- ============================================================
-- 2. 重新插入站点数据
-- ============================================================
INSERT INTO stations (coord_x, coord_y, radius, robot_count, drone_count)
VALUES
    (37.7749, -122.4194, 5.0, 3, 2),   -- 一号站
    (37.7561, -122.4476, 4.5, 2, 3),   -- 二号站
    (37.7123, -122.4000, 6.0, 4, 3);   -- 三号站


-- ============================================================
-- 3. 重新插入订单数据（user_id 直接写 1）
-- ============================================================
INSERT INTO orders (
    user_id,
    destination,
    package_weight_lbs,
    price,
    time,
    vehicle,
    station_id,
    status
)
VALUES
    -- 订单1：待取件 | 机器人 | 一号站
    (
        1,
        '123 Market Street, San Francisco, CA 94103',
        5.5,
        15.99,
        12.5,
        'ROBOT',
        1,  -- 一号站的 station_id
        'PENDING_DROPOFF'
    ),

    -- 订单2：已到达站点 | 无人机 | 二号站
    (
        1,
        '456 Mission Boulevard, San Francisco, CA 94105',
        2.0,
        8.50,
        5.0,
        'DRONE',
        2,  -- 二号站的 station_id
        'AT_STATION'
    ),

    -- 订单3：未过半程 | 机器人 | 三号站
    (
        1,
        '789 Howard Avenue, San Francisco, CA 94107',
        12.0,
        28.75,
        20.0,
        'ROBOT',
        3,  -- 三号站的 station_id
        'BEFORE_HALF_WAY'
    ),

    -- 订单4：已过半程 | 无人机 | 一号站
    (
        1,
        '101 2nd Street, San Francisco, CA 94105',
        3.5,
        10.25,
        8.0,
        'DRONE',
        1,  -- 一号站的 station_id
        'MORE_THAN_HALF_WAY'
    ),

    -- 订单5：已送达 | 机器人 | 二号站
    (
        1,
        '202 3rd Street, San Francisco, CA 94107',
        8.0,
        22.00,
        18.0,
        'ROBOT',
        2,  -- 二号站的 station_id
        'DELIVERED'
    ),

    -- 订单6：已取消 | 无人机 | 三号站
    (
        1,
        '303 4th Street, San Francisco, CA 94103',
        1.0,
        4.99,
        1.5,
        'DRONE',
        3,  -- 三号站的 station_id
        'CANCELLED'
    ),

    -- 订单7：正好半程 | 机器人 | 一号站
    (
        1,
        '404 5th Street, San Francisco, CA 94103',
        6.0,
        18.50,
        15.0,
        'ROBOT',
        1,  -- 一号站的 station_id
        'HALF_WAY'
    );