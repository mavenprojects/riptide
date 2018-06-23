package org.zalando.riptide.metrics;

import com.google.common.collect.ImmutableList;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import lombok.AllArgsConstructor;
import org.apiguardian.api.API;
import org.springframework.http.client.ClientHttpResponse;
import org.zalando.riptide.Plugin;
import org.zalando.riptide.RequestArguments;
import org.zalando.riptide.RequestExecution;

import java.io.IOException;
import java.util.Arrays;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.Iterables.concat;
import static java.util.Objects.nonNull;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.apiguardian.api.API.Status.EXPERIMENTAL;
import static org.zalando.fauxpas.FauxPas.throwingBiConsumer;

@API(status = EXPERIMENTAL)
public final class MetricsPlugin implements Plugin {

    private final MeterRegistry registry;
    private final String metricName;
    private final ImmutableList<Tag> defaultTags;
    private final Clock clock;

    public MetricsPlugin(final MeterRegistry registry) {
        this(registry, "http.client.requests", ImmutableList.of());
    }

    private MetricsPlugin(final MeterRegistry registry, final String metricName, final ImmutableList<Tag> defaultTags) {
        this.registry = registry;
        this.metricName = metricName;
        this.defaultTags = defaultTags;
        this.clock = registry.config().clock();
    }

    public MetricsPlugin withMetricName(final String metricName) {
        return new MetricsPlugin(registry, metricName, defaultTags);
    }

    public MetricsPlugin withDefaultTags(final Tag... defaultTags) {
        return withDefaultTags(ImmutableList.copyOf(defaultTags));
    }

    public MetricsPlugin withDefaultTags(final Iterable<Tag> defaultTags) {
        return new MetricsPlugin(registry, metricName, ImmutableList.copyOf(defaultTags));
    }

    @Override
    public RequestExecution interceptBeforeRouting(final RequestArguments arguments, final RequestExecution execution) {
        return () -> {
            final Measurement measurement = new Measurement(arguments);

            return execution.execute()
                    .whenComplete(throwingBiConsumer((response, throwable) -> {
                        if (nonNull(response)) {
                            measurement.record(response);
                        }
                    }));
        };
    }

    @Override
    public RequestExecution prepare(final RequestArguments arguments, final RequestExecution execution) {
        return execution;
    }

    @AllArgsConstructor
    private final class Measurement {

        private final long startTime = clock.monotonicTime();
        private final RequestArguments arguments;

        void record(final ClientHttpResponse response) throws IOException {
            final long endTime = clock.monotonicTime();

            final Iterable<Tag> tags = concat(defaultTags, tags(arguments, response));
            final Timer timer = registry.timer(metricName, tags);

            final long duration = endTime - startTime;
            timer.record(duration, NANOSECONDS);
        }

    }

    private Iterable<Tag> tags(final RequestArguments arguments, final ClientHttpResponse response) throws IOException {
        return Arrays.asList(
                Tag.of("method", method(arguments)),
                Tag.of("uri", uri(arguments)),
                Tag.of("status", status(response)),
                Tag.of("clientName", client(arguments))
        );
    }

    private String method(final RequestArguments arguments) {
        return arguments.getMethod().name();
    }

    private String uri(final RequestArguments arguments) {
        return firstNonNull(arguments.getUriTemplate(), arguments.getRequestUri().getPath());
    }

    private String status(final ClientHttpResponse response) throws IOException {
        return String.valueOf(response.getRawStatusCode());
    }

    private String client(final RequestArguments arguments) {
        return firstNonNull(arguments.getRequestUri().getHost(), "none");
    }

}
