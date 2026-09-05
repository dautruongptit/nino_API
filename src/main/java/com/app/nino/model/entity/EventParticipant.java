package com.app.nino.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "event_participants")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@IdClass(EventParticipant.EventParticipantId.class)
public class EventParticipant {

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id")
    private Event event;

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "relative_id")
    private Relative relative;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /** Composite key class — bắt buộc với @IdClass */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EventParticipantId implements Serializable {
        private Long event;
        private Long relative;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof EventParticipantId)) return false;
            EventParticipantId that = (EventParticipantId) o;
            return Objects.equals(event, that.event) && Objects.equals(relative, that.relative);
        }

        @Override
        public int hashCode() {
            return Objects.hash(event, relative);
        }
    }
}
