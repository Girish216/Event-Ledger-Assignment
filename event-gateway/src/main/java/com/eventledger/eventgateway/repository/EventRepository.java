package com.eventledger.eventgateway.repository;

import com.eventledger.eventgateway.domain.Event;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {

    Optional<Event> findByEventId(String eventId);

    List<Event> findByAccountIdOrderByEventTimestampAsc(String accountId);
}
