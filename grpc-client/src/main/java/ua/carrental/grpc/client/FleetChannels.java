package ua.carrental.grpc.client;

import io.grpc.*;
import io.grpc.netty.NettyChannelBuilder;
import java.io.IOException;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

public final class FleetChannels {
    private FleetChannels() {}

    public static ManagedChannel open(String target, boolean plaintext) {
        var builder = NettyChannelBuilder.forTarget(target);
        if (plaintext) builder.usePlaintext(); else builder.useTransportSecurity();
        return configure(builder).build();
    }

    public static <T extends ManagedChannelBuilder<T>> T configure(T builder) {
        try (var in = FleetChannels.class.getResourceAsStream("/grpc/fleet-service-config.json")) {
            if (in == null) throw new IllegalStateException("Missing gRPC retry configuration");
            Map<String, ?> config = JsonMapper.builder().build().readValue(in, new TypeReference<Map<String, Object>>() {});
            return builder.defaultServiceConfig(config).disableServiceConfigLookUp().enableRetry()
                    .maxRetryAttempts(4).retryBufferSize(4 * 1024 * 1024).perRpcBufferLimit(1024 * 1024);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot load gRPC retry configuration", ex);
        }
    }
}
