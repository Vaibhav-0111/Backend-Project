package com.example.webhook.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class DeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);
    
    private final DeliveryClaimer deliveryClaimer;
    private final String workerId = UUID.randomUUID().toString();

    public DeliveryWorker(DeliveryClaimer deliveryClaimer) {
        this.deliveryClaimer = deliveryClaimer;
    }

    @Scheduled(fixedDelayString = "${webhook.worker.delay:1000}")
    public void processDeliveries() {
        List<Delivery> deliveries = deliveryClaimer.claimDeliveries(workerId, 50);
        if (!deliveries.isEmpty()) {
            log.info("Worker {} claimed {} deliveries", workerId, deliveries.size());
            // HTTP delivery dispatch will be implemented here
        }
    }
}
