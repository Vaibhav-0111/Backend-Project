package com.example.webhook.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class DeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final DeliveryClaimer deliveryClaimer;
    private final DeliveryService deliveryService;
    private final String workerId = UUID.randomUUID().toString();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public DeliveryWorker(DeliveryClaimer deliveryClaimer, DeliveryService deliveryService) {
        this.deliveryClaimer = deliveryClaimer;
        this.deliveryService = deliveryService;
    }

    @Scheduled(fixedDelayString = "${webhook.worker.delay:1000}")
    public void processDeliveries() {
        List<Delivery> deliveries = deliveryClaimer.claimDeliveries(workerId, 50);
        if (!deliveries.isEmpty()) {
            log.info("Worker {} claimed {} deliveries", workerId, deliveries.size());
            for (Delivery delivery : deliveries) {
                executor.submit(() -> deliveryService.executeDelivery(delivery));
            }
        }
    }
}
