package com.example.webhook.event;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    public ResponseEntity<?> ingestEvent(@Valid @RequestBody EventIngestionRequest request) {
        Event event = eventService.ingestEvent(request);
        return ResponseEntity.accepted().body(Map.of("internalEventId", event.getId()));
    }
}
