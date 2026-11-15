package ua.carrental.fleet.grpc;

import io.grpc.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;

@Configuration(proxyBeanMethods = false)
public class GrpcAttemptLogging {
    private static final Logger log = LoggerFactory.getLogger(GrpcAttemptLogging.class);
    private static final Metadata.Key<String> PREVIOUS = Metadata.Key.of("grpc-previous-rpc-attempts", Metadata.ASCII_STRING_MARSHALLER);
    @Bean
    @GlobalServerInterceptor
    ServerInterceptor grpcAttemptLogger() {
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                    Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                String previous = headers.get(PREVIOUS);
                log.info("grpc method={} previousAttempts={}", call.getMethodDescriptor().getFullMethodName(), previous == null ? "0" : previous);
                return next.startCall(new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
                    @Override public void close(Status status, Metadata trailers) {
                        log.info("grpc method={} status={}", getMethodDescriptor().getFullMethodName(), status.getCode());
                        super.close(status, trailers);
                    }
                }, headers);
            }
        };
    }
}
