package com.example.webhook.delivery;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface DeliveryAttemptRepository extends CrudRepository<DeliveryAttempt, UUID> {
}
