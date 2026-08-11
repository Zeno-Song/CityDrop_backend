package com.citydrop.backend.db;

import com.citydrop.backend.db.entities.StationEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

public interface StationRepository extends ListCrudRepository<StationEntity, Integer> {

    StationEntity findByStationId(int stationId);

    @Modifying
    @Query("""
            UPDATE stations
            SET robot_count = robot_count - 1
            WHERE station_id = :stationId
              AND robot_count > 0
            """)
    int decrementRobotCount(@Param("stationId") int stationId);

    @Modifying
    @Query("""
            UPDATE stations
            SET drone_count = drone_count - 1
            WHERE station_id = :stationId
              AND drone_count > 0
            """)
    int decrementDroneCount(@Param("stationId") int stationId);

    @Modifying
    @Query("""
            UPDATE stations
            SET robot_count = robot_count + 1
            WHERE station_id = :stationId
            """)
    void incrementRobotCount(@Param("stationId") int stationId);

    @Modifying
    @Query("""
            UPDATE stations
            SET drone_count = drone_count + 1
            WHERE station_id = :stationId
            """)
    void incrementDroneCount(@Param("stationId") int stationId);
}
