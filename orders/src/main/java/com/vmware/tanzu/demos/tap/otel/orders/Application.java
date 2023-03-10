/*
 * Copyright (c) 2023 VMware, Inc. or its affiliates
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.vmware.tanzu.demos.tap.otel.orders;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.springframework.web.util.WebUtils.ERROR_EXCEPTION_ATTRIBUTE;

enum OrderState {
    Draft, New, InProgress, OnHold, Completed, Canceled
}

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}

/**
 * This class is responsible for registering custom metrics.
 */
@Configuration(proxyBeanMethods = false)
class MetricsConf {
    /**
     * Create a counter for the number of times the orders service was hit.
     * This is for demo purpose.
     */
    @Bean
    @Qualifier("ordersServiceHitCounter")
    Counter ordersServiceHitCounter(MeterRegistry reg) {
        return Counter.builder("hit.counter").baseUnit("hits").description("Hit counter").tags("page", "orders").register(reg);
    }
}

@RestController
class OrderController {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderController.class);
    private static final Map<String, Order> ORDERS;

    static {
        // Create an in-memory list of orders.
        final var o1 = new Order("e5377e96-c6c6-4f00-bdd1-f36efb6b9b6a",
                "johndoe", OrderState.New, Instant.parse("2022-11-14T09:13:30+00:00"),
                List.of("41a1c650-df66-46d0-b7fb-96a117c5dda7", "e5b6da9d-ba51-4119-b527-ace1aaa7985e"));
        final var o2 = new Order("998d14af-aac1-4082-8194-990a3c24f553",
                "bartsimpsons", OrderState.Canceled, Instant.parse("2022-11-25T09:34:30+00:00"),
                List.of("dc68e695-e8c3-4bc9-9531-28aed4a6ecd6"));
        ORDERS = Map.of(o1.orderId(), o1, o2.orderId(), o2);
    }

    private final Random random = new Random();
    private final Counter hitCounter;

    public OrderController(@Qualifier("ordersServiceHitCounter") Counter hitCounter) {
        this.hitCounter = hitCounter;
    }

    @GetMapping("/api/v1/orders/{orderId}")
    Order orders(@PathVariable("orderId") String orderId) throws InterruptedException {
        hitCounter.increment();

        LOGGER.info("Looking up order: {}", orderId);
        final var order = ORDERS.get(orderId);

        final var delay = random.nextLong(1000);
        LOGGER.debug("Slowing down orders service by {} ms", delay);
        Thread.sleep(delay);

        if (order == null) {
            LOGGER.info("Order not found: {}", orderId);
            // This error is mapped to a 404 HTTP response.
            throw new OrderNotFoundException(orderId);
        }
        LOGGER.info("Order {} found: {}", orderId, order);
        return order;
    }
}

class OrderNotFoundException extends RuntimeException {
    private final String orderId;

    OrderNotFoundException(String orderId) {
        super("Order not found: " + orderId);
        this.orderId = orderId;
    }

    public String getOrderId() {
        return orderId;
    }
}

@RestControllerAdvice
class OrderControllerAdvice {
    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail handleOrderNotFoundException(HttpServletRequest request, OrderNotFoundException e) {
        // Map this exception to a RFC 7807 entity (Problem Details for HTTP APIs).
        final var detail = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        detail.setTitle(e.getMessage());
        detail.setType(URI.create("urn:problem-type:order-not-found"));
        request.setAttribute(ERROR_EXCEPTION_ATTRIBUTE, e);
        return detail;
    }
}

record Order(String orderId, String customerId, OrderState state, Instant dueDate, List<String> itemIds) {
}
