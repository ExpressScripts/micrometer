/**
 * Copyright 2020 Pivotal Software, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.newrelic;

import static io.micrometer.core.instrument.util.StringEscapeUtils.escapeJson;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MissingRequiredConfigurationException;
import io.micrometer.core.instrument.config.NamingConvention;
import io.micrometer.core.instrument.util.*;
import io.micrometer.core.ipc.http.HttpSender;
import io.micrometer.core.ipc.http.HttpUrlConnectionSender;

/**
 * Publishes metrics to New Relic Insights REST API.
 * 
 * @author Jon Schneider
 * @author Johnny Lim
 * @author Neil Powell
 * @since 1.4.0
 */
public class NewRelicApiClientProvider implements NewRelicClientProvider {

    private final Logger logger = LoggerFactory.getLogger(NewRelicApiClientProvider.class);

    private final NewRelicConfig config;
    private final HttpSender httpClient;
    private final NamingConvention namingConvention;
    private final String insightsEndpoint;

    @SuppressWarnings("deprecation")
    public NewRelicApiClientProvider(NewRelicConfig config) {
        this(config, new HttpUrlConnectionSender(config.connectTimeout(), config.readTimeout()), new NewRelicNamingConvention());
    }
    
    @SuppressWarnings("deprecation")
    public NewRelicApiClientProvider(NewRelicConfig config, String proxyHost, int proxyPort) {
        this(config, new HttpUrlConnectionSender(config.connectTimeout(), config.readTimeout(), 
                            new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort))), new NewRelicNamingConvention());
    }

    public NewRelicApiClientProvider(NewRelicConfig config, HttpSender httpClient, NamingConvention namingConvention) {

        if (!config.meterNameEventTypeEnabled() && StringUtils.isEmpty(config.eventType())) {
            throw new MissingRequiredConfigurationException("eventType must be set to report metrics to New Relic");
        }
        if (StringUtils.isEmpty(config.accountId())) {
            throw new MissingRequiredConfigurationException("accountId must be set to report metrics to New Relic");
        }
        if (StringUtils.isEmpty(config.apiKey())) {
            throw new MissingRequiredConfigurationException("apiKey must be set to report metrics to New Relic");
        }
        if (StringUtils.isEmpty(config.uri())) {
            throw new MissingRequiredConfigurationException("uri must be set to report metrics to New Relic");
        }

        this.config = config;
        this.httpClient = httpClient;
        this.namingConvention = namingConvention;
        this.insightsEndpoint = config.uri() + "/v1/accounts/" + config.accountId() + "/events";
    }

    @Override
    public void publish(NewRelicMeterRegistry meterRegistry) {
        // New Relic's Insights API limits us to 1000 events per call
        // 1:1 mapping between Micrometer meters and New Relic events
        for (List<Meter> batch : MeterPartition.partition(meterRegistry, Math.min(config.batchSize(), 1000))) {
            sendEvents(batch.stream().flatMap(meter -> meter.match(
                    this::writeGauge,
                    this::writeCounter,
                    this::writeTimer,
                    this::writeSummary,
                    this::writeLongTaskTimer,
                    this::writeTimeGauge,
                    this::writeFunctionCounter,
                    this::writeFunctionTimer,
                    this::writeMeter)));
        }
    }
    
    @Override
    public Stream<String> writeLongTaskTimer(LongTaskTimer timer) {
        TimeUnit timeUnit = TimeUnit.valueOf(timer.getId().getBaseUnit());
        return Stream.of(
                event(timer.getId(),
                        new Attribute(ACTIVE_TASKS, timer.activeTasks()),
                        new Attribute(DURATION, timer.duration(timeUnit)),
                        new Attribute(TIME_UNIT, timeUnit.name().toLowerCase())
                )
        );
    }

    @Override
    public Stream<String> writeFunctionCounter(FunctionCounter counter) {
        double count = counter.count();
        if (Double.isFinite(count)) {
            return Stream.of(event(counter.getId(), new Attribute(THROUGHPUT, count)));
        }
        return Stream.empty();
    }

    @Override
    public Stream<String> writeCounter(Counter counter) {
        return Stream.of(event(counter.getId(), new Attribute(THROUGHPUT, counter.count())));
    }

    @Override
    public Stream<String> writeGauge(Gauge gauge) {
        Double value = gauge.value();
        if (Double.isFinite(value)) {
            return Stream.of(event(gauge.getId(), new Attribute(VALUE, value)));
        }
        return Stream.empty();
    }

    @Override
    public Stream<String> writeTimeGauge(TimeGauge gauge) {
        Double value = gauge.value();
        if (Double.isFinite(value)) {
            return Stream.of(
                    event(gauge.getId(),
                            new Attribute(VALUE, value),
                            new Attribute(TIME_UNIT, gauge.baseTimeUnit().name().toLowerCase())
                    )
            );
        }
        return Stream.empty();
    }

    @Override
    public Stream<String> writeSummary(DistributionSummary summary) {
        return Stream.of(
                event(summary.getId(),
                        new Attribute(COUNT, summary.count()),
                        new Attribute(AVG, summary.mean()),
                        new Attribute(TOTAL, summary.totalAmount()),
                        new Attribute(MAX, summary.max())
                )
            );
    }

    @Override
    public Stream<String> writeTimer(Timer timer) {
        TimeUnit timeUnit = TimeUnit.valueOf(timer.getId().getBaseUnit());
        return Stream.of(
                event(timer.getId(),
                        new Attribute(COUNT, timer.count()),
                        new Attribute(AVG, timer.mean(timeUnit)),
                        new Attribute(TOTAL_TIME, timer.totalTime(timeUnit)),
                        new Attribute(MAX, timer.max(timeUnit)),
                        new Attribute(TIME_UNIT, timeUnit.name().toLowerCase())
                )
            );
    }

    @Override
    public Stream<String> writeFunctionTimer(FunctionTimer timer) {
        TimeUnit timeUnit = TimeUnit.valueOf(timer.getId().getBaseUnit());
        return Stream.of(
                event(timer.getId(),
                        new Attribute(COUNT, timer.count()),
                        new Attribute(AVG, timer.mean(timeUnit)),
                        new Attribute(TOTAL_TIME, timer.totalTime(timeUnit)),
                        new Attribute(TIME_UNIT, timeUnit.name().toLowerCase())
                )
            );
    }

    @Override
    public Stream<String> writeMeter(Meter meter) {
        // Snapshot values should be used throughout this method as there are chances for values to be changed in-between.
        Map<String, Attribute> attributes = new HashMap<>();
        for (Measurement measurement : meter.measure()) {
            double value = measurement.getValue();
            if (!Double.isFinite(value)) {
                continue;
            }
            String name = measurement.getStatistic().getTagValueRepresentation();
            attributes.put(name, new Attribute(name, value));
        }
        if (attributes.isEmpty()) {
            return Stream.empty();
        }
        return Stream.of(event(meter.getId(), attributes.values().toArray(new Attribute[0])));
    }

    private String event(Meter.Id id, Attribute... attributes) {
        if (!config.meterNameEventTypeEnabled()) {
            // Include contextual attributes when publishing all metrics under a single categorical eventType,
            // NOT when publishing an eventType per Meter/metric name
            int size = attributes.length;
            Attribute[] newAttrs = Arrays.copyOf(attributes, size + 2);

            String name = id.getConventionName(namingConvention);
            newAttrs[size] = new Attribute(METRIC_NAME, name);
            newAttrs[size + 1] = new Attribute(METRIC_TYPE, id.getType().toString());
            
            return event(id, Tags.empty(), newAttrs);
        }
        return event(id, Tags.empty(), attributes);
    }

    private String event(Meter.Id id, Iterable<Tag> extraTags, Attribute... attributes) {
        StringBuilder tagsJson = new StringBuilder();

        for (Tag tag : id.getConventionTags(namingConvention)) {
            tagsJson.append(",\"").append(escapeJson(tag.getKey())).append("\":\"").append(escapeJson(tag.getValue())).append("\"");
        }

        for (Tag tag : extraTags) {
            tagsJson.append(",\"").append(escapeJson(namingConvention.tagKey(tag.getKey())))
                    .append("\":\"").append(escapeJson(namingConvention.tagValue(tag.getValue()))).append("\"");
        }

        String eventType = getEventType(id, config, namingConvention);
        
        return Arrays.stream(attributes)
                .map(attr -> 
                        (attr.getValue() instanceof Number) 
                            ? ",\"" + attr.getName() + "\":" + DoubleFormat.wholeOrDecimal(((Number) attr.getValue()).doubleValue())
                            : ",\"" + attr.getName() + "\":\"" + namingConvention.tagValue(attr.getValue().toString()) + "\""
                )
                .collect(Collectors.joining("", "{\"eventType\":\"" + escapeJson(eventType) + "\"", tagsJson + "}"));
    }

    void sendEvents(Stream<String> events) {
        try {
            AtomicInteger totalEvents = new AtomicInteger();

            httpClient.post(insightsEndpoint)
                    .withHeader("X-Insert-Key", config.apiKey())
                    .withJsonContent(events.peek(ev -> totalEvents.incrementAndGet()).collect(Collectors.joining(",", "[", "]")))
                    .send()
                    .onSuccess(response -> logger.debug("successfully sent {} metrics to New Relic.", totalEvents))
                    .onError(response -> logger.error("failed to send metrics to new relic: http {} {}", response.code(), response.body()));
        } catch (Throwable e) {
            logger.warn("failed to send metrics to new relic", e);
        }
    }

    private class Attribute {
        private final String name;
        private final Object value;

        private Attribute(String name, Object value) {
            this.name = name;
            this.value = value;
        }

        public String getName() {
            return name;
        }

        public Object getValue() {
            return value;
        }
    }
}
