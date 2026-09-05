package com.app.nino.repository;

import com.app.nino.model.entity.EventParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EventParticipantRepository
        extends JpaRepository<EventParticipant, EventParticipant.EventParticipantId> {

    @Query("SELECT ep FROM EventParticipant ep WHERE ep.event.id = :eventId")
    List<EventParticipant> findByEventId(@Param("eventId") Long eventId);

    @Query("SELECT ep FROM EventParticipant ep WHERE ep.relative.id = :relativeId")
    List<EventParticipant> findByRelativeId(@Param("relativeId") Long relativeId);

    @Modifying
    @Query("DELETE FROM EventParticipant ep WHERE ep.event.id = :eventId")
    void deleteByEventId(@Param("eventId") Long eventId);
}
