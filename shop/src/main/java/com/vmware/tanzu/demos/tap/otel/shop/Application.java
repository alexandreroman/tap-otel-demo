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

package com.vmware.tanzu.demos.tap.otel.shop;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.vmware.tanzu.demos.tap.otel.shop.FullOrder.toFullOrder;

enum OrderState {
    Draft, New, InProgress, OnHold, Completed, Canceled
}

/**
 * Declarative HTTP client used for accessing service orders
 * (new feature starting Spring Framework 6.0).
 */
interface OrderServiceClient {
    @GetExchange("/api/v1/orders/{orderId}")
    Order findOrder(@PathVariable("orderId") String orderId);
}

/**
 * Declarative HTTP client used for accessing service items
 * (new feature starting Spring Framework 6.0).
 */
interface ItemServiceClient {
    @GetExchange("/api/v1/items/{itemId}")
    OrderItem findItem(@PathVariable("itemId") String itemId);
}

@SpringBootApplication
@EnableConfigurationProperties
@ConfigurationPropertiesScan
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    OrderServiceClient orderServiceClient(WebClient.Builder builder, ServiceConf services) {
        return HttpServiceProxyFactory
                .builder(WebClientAdapter.forClient(builder.baseUrl(services.orders()).build())).build()
                .createClient(OrderServiceClient.class);
    }

    @Bean
    ItemServiceClient itemServiceClient(WebClient.Builder builder, ServiceConf services) {
        return HttpServiceProxyFactory
                .builder(WebClientAdapter.forClient(builder.baseUrl(services.items()).build())).build()
                .createClient(ItemServiceClient.class);
    }

    @Bean
    JdkClientHttpConnector clientHttpConnector() {
        final var httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        return new JdkClientHttpConnector(httpClient);
    }
}

@Component
class CustomWebClientCustomizer implements WebClientCustomizer {
    private final String appName;
    private final ClientHttpConnector clientHttpConnector;

    public CustomWebClientCustomizer(@Value("${spring.application.name}")
                                     String appName,
                                     ClientHttpConnector clientHttpConnector) {
        this.appName = appName;
        this.clientHttpConnector = clientHttpConnector;
    }

    @Override
    public void customize(WebClient.Builder builder) {
        // Set the HTTP User-Agent.
        builder.defaultHeader(HttpHeaders.USER_AGENT, appName)
                // Reuse client connector (setting connection timeouts).
                .clientConnector(clientHttpConnector);
    }
}

/**
 * This record holds URI for remote services.
 */
@ConfigurationProperties(prefix = "app.services")
record ServiceConf(String orders, String items) {
}

/**
 * This class is responsible for registering custom metrics.
 */
@Configuration(proxyBeanMethods = false)
class MetricsConf {
    /**
     * Create a counter for the number of times the index page was hit.
     * This is for demo purpose.
     */
    @Bean
    @Qualifier("indexPageHitCounter")
    Counter indexPageHitCounter(MeterRegistry reg) {
        return Counter.builder("hit.counter")
                .baseUnit("hits")
                .description("Hit counter")
                .tags("page", "index").register(reg);
    }
}

@RestController
class IndexController {
    private static final Logger LOGGER = LoggerFactory.getLogger(IndexController.class);
    private final Counter hitCounter;
    private final OrderServiceClient osc;
    private final ItemServiceClient isc;
    private final ObservationRegistry reg;

    public IndexController(@Qualifier("indexPageHitCounter") Counter hitCounter, OrderServiceClient osc, ItemServiceClient isc, ObservationRegistry reg) {
        this.hitCounter = hitCounter;
        this.osc = osc;
        this.isc = isc;
        this.reg = reg;
    }

    @GetMapping("/")
    IndexPage index(@Value("${app.title}") String title) {
        LOGGER.info("Building content for index page");
        hitCounter.increment();

        // Use a static list for orders.
        final var orderIds = List.of(
                "e5377e96-c6c6-4f00-bdd1-f36efb6b9b6a",
                "998d14af-aac1-4082-8194-990a3c24f553"
        );

        // Rely on Observation API to measure time spent building the index page content.
        final var obs = Observation.start("shop.indexPage", reg);
        try {
            final var orders = orderIds.stream().map(this::fetchOrder).
                    filter(Objects::nonNull).toList();
            final var fullOrders = new ArrayList<FullOrder>(orders.size());
            for (final var order : orders) {
                final var items = order.itemIds().stream().map(this::fetchOrderItem).
                        filter(Objects::nonNull).toList();
                fullOrders.add(toFullOrder(order, items));
            }
            // Creating an Observation event will result in the creation of a new metric.
            obs.event(Observation.Event.of("built"));
            return new IndexPage(title, fullOrders);
        } catch (Exception e) {
            obs.error(e);
            throw e;
        } finally {
            obs.stop();
        }
    }

    private Order fetchOrder(String orderId) {
        final var obs = Observation.start("shop.findOrder", reg);
        obs.highCardinalityKeyValue("order", orderId);

        LOGGER.info("Fetching order details: {}", orderId);
        try {
            return obs.observeChecked(() -> osc.findOrder(orderId));
        } catch (Exception e) {
            LOGGER.warn("Failed to get order details: {}", orderId, e);
        }
        return null;
    }

    private OrderItem fetchOrderItem(String itemId) {
        final var obs = Observation.start("shop.findItem", reg);
        obs.highCardinalityKeyValue("item", itemId);

        LOGGER.info("Fetching item details: {}", itemId);
        try {
            return obs.observeChecked(() -> isc.findItem(itemId));
        } catch (Exception e) {
            LOGGER.warn("Failed to get item details: {}", itemId, e);
        }
        return null;
    }
}

record IndexPage(String title, List<FullOrder> orders) {
}

record FullOrder(String orderId, String customerId, OrderState state, Instant dueDate, List<OrderItem> items) {
    public static FullOrder toFullOrder(Order order, List<OrderItem> items) {
        return new FullOrder(order.orderId(), order.customerId(), order.state(), order.dueDate(), items);
    }
}

record Order(String orderId, String customerId, OrderState state, Instant dueDate, List<String> itemIds) {
}

record OrderItem(String itemId, String title, String price) {
}
