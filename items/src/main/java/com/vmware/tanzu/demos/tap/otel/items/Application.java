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

package com.vmware.tanzu.demos.tap.otel.items;

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
import java.util.Map;
import java.util.Random;

import static org.springframework.web.util.WebUtils.ERROR_EXCEPTION_ATTRIBUTE;

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
     * Create a counter for the number of times the items service was hit.
     * This is for demo purpose.
     */
    @Bean
    @Qualifier("itemsServiceHitCounter")
    Counter itemsServiceHitCounter(MeterRegistry reg) {
        return Counter.builder("hit.counter").baseUnit("hits").description("Hit counter").tags("page", "items").register(reg);
    }
}

@RestController
class ItemController {
    private static final Logger LOGGER = LoggerFactory.getLogger(ItemController.class);
    private static final Map<String, OrderItem> ITEMS;

    static {
        // Create an in-memory list of items.
        final var i1 = new OrderItem("41a1c650-df66-46d0-b7fb-96a117c5dda7", "Hat: Spring Boot FTW", "100");
        final var i2 = new OrderItem("e5b6da9d-ba51-4119-b527-ace1aaa7985e", "Laptop sticker: I love Java", "15");
        final var i3 = new OrderItem("dc68e695-e8c3-4bc9-9531-28aed4a6ecd6", "T-shirt: Kubernetes is boring", "27");
        ITEMS = Map.of(i1.itemId(), i1, i2.itemId(), i2, i3.itemId(), i3);

    }

    private final Random random = new Random();
    private final Counter hitCounter;

    public ItemController(@Qualifier("itemsServiceHitCounter") Counter hitCounter) {
        this.hitCounter = hitCounter;
    }

    @GetMapping("/api/v1/items/{itemId}")
    OrderItem orders(@PathVariable("itemId") String itemId) throws InterruptedException {
        hitCounter.increment();

        LOGGER.info("Looking up items: {}", itemId);
        final var item = ITEMS.get(itemId);

        final var delay = random.nextLong(1000);
        LOGGER.debug("Slowing down items service by {} ms", delay);
        Thread.sleep(delay);

        if (item == null) {
            LOGGER.info("Item not found: {}", itemId);
            // This error is mapped to a 404 HTTP response.
            throw new ItemNotFoundException(itemId);
        }
        LOGGER.info("Item {} found: {}", itemId, item);
        return item;
    }
}


class ItemNotFoundException extends RuntimeException {
    private final String itemId;

    ItemNotFoundException(String itemId) {
        super("Item not found: " + itemId);
        this.itemId = itemId;
    }

    public String getItemId() {
        return itemId;
    }
}

@RestControllerAdvice
class ItemControllerAdvice {
    @ExceptionHandler(ItemNotFoundException.class)
    ProblemDetail handleItemNotFoundException(HttpServletRequest request, ItemNotFoundException e) {
        // Map this exception to a RFC 7807 entity (Problem Details for HTTP APIs).
        final var detail = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        detail.setTitle(e.getMessage());
        detail.setType(URI.create("urn:problem-type:item-not-found"));
        request.setAttribute(ERROR_EXCEPTION_ATTRIBUTE, e);
        return detail;
    }
}

record OrderItem(String itemId, String title, String price) {
}
