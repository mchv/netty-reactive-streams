package com.typesafe.netty.http;

import akka.dispatch.Futures;
import akka.japi.Function;
import akka.japi.function.Function2;
import akka.stream.Materializer;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import org.reactivestreams.Publisher;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertNotNull;
import static org.testng.AssertJUnit.assertEquals;

/**
 * Helpers for building HTTP test servers
 */
public class HttpHelper {

    protected final Materializer materializer;

    public HttpHelper(Materializer materializer) {
        this.materializer = materializer;
    }

    /**
     * An echo HTTP server
     */
    public HttpResponse echo(Object msg) {
        HttpResponse response;
        if (msg instanceof HttpRequest) {

            HttpRequest request = (HttpRequest) msg;
            if (request instanceof FullHttpRequest) {
                response = new DefaultFullHttpResponse(request.getProtocolVersion(), HttpResponseStatus.OK,
                        ((FullHttpRequest) msg).content());
                response.headers().set("Request-Type", "Full");
            } else if (request instanceof StreamedHttpRequest) {
                response = new DefaultStreamedHttpResponse(request.getProtocolVersion(), HttpResponseStatus.OK,
                        ((StreamedHttpRequest) msg));
                response.headers().set("Request-Type", "Streamed");
            } else {
                throw new IllegalArgumentException("Unsupported HTTP request: " + request);
            }

            if (HttpHeaders.isTransferEncodingChunked(request)) {
                HttpHeaders.setTransferEncodingChunked(response);
            } else if (HttpHeaders.isContentLengthSet(request)) {
                long contentLength = HttpHeaders.getContentLength(request);
                response.headers().set("Request-Content-Length", contentLength);
                HttpHeaders.setContentLength(response, contentLength);
            } else {
                HttpHeaders.setContentLength(response, 0);
            }

            HttpHeaders.setHeader(response, "Request-Uri", request.getUri());
        } else {
            throw new IllegalArgumentException("Unsupported message: " + msg);
        }

        return response;
    }

    public StreamedHttpRequest createStreamedRequest(String method, String uri, List<String> body) {
        List<HttpContent> content = new ArrayList<>();
        for (String chunk: body) {
            content.add(new DefaultHttpContent(Unpooled.copiedBuffer(chunk, Charset.forName("utf-8"))));
        }
        Publisher<HttpContent> publisher = Source.from(content).runWith(Sink.<HttpContent>publisher(), materializer);
        return new DefaultStreamedHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.valueOf(method), uri,
                publisher);
    }

    public StreamedHttpRequest createStreamedRequest(String method, String uri, List<String> body, long contentLength) {
        StreamedHttpRequest request = createStreamedRequest(method, uri, body);
        HttpHeaders.setContentLength(request, contentLength);
        return request;
    }

    public StreamedHttpRequest createChunkedRequest(String method, String uri, List<String> body) {
        StreamedHttpRequest request = createStreamedRequest(method, uri, body);
        HttpHeaders.setTransferEncodingChunked(request);
        return request;
    }

    public FullHttpResponse createFullResponse(String body) {
        ByteBuf content = Unpooled.copiedBuffer(body, Charset.forName("utf-8"));
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
        HttpHeaders.setContentLength(response, content.readableBytes());
        return response;
    }

    public StreamedHttpResponse createStreamedResponse(HttpVersion version, List<String> body, long contentLength) {
        List<HttpContent> content = new ArrayList<>();
        for (String chunk: body) {
            content.add(new DefaultHttpContent(Unpooled.copiedBuffer(chunk, Charset.forName("utf-8"))));
        }
        Publisher<HttpContent> publisher = Source.from(content).runWith(Sink.<HttpContent>publisher(), materializer);
        StreamedHttpResponse response = new DefaultStreamedHttpResponse(version, HttpResponseStatus.OK, publisher);
        HttpHeaders.setContentLength(response, contentLength);
        return response;
    }

    public String extractBody(Object msg) throws Exception {
        return Await.result(extractBodyAsync(msg), Duration.apply(1, TimeUnit.SECONDS));
    }

    public Future<String> extractBodyAsync(Object msg) {
        if (msg instanceof FullHttpMessage) {
            String body = contentAsString((FullHttpMessage) msg);
            return Futures.successful(body);
        } else if (msg instanceof StreamedHttpMessage) {
            return Source.from((StreamedHttpMessage) msg).runFold("", new Function2<String, HttpContent, String>() {
                @Override
                public String apply(String body, HttpContent content) throws Exception {
                    return body + contentAsString(content);
                }
            }, materializer);
        } else {
            throw new IllegalArgumentException("Unknown message type: " + msg);
        }
    }

    private String contentAsString(HttpContent content) {
        String body = content.content().toString(Charset.forName("utf-8"));
        ReferenceCountUtil.release(content);
        return body;
    }

    public void assertRequestTypeStreamed(HttpResponse response) {
        assertEquals(response.headers().get("Request-Type"), "Streamed");
    }

    public void assertRequestTypeFull(HttpResponse response) {
        assertEquals(response.headers().get("Request-Type"), "Full");
    }

    public long getRequestContentLength(HttpResponse response) {
        String contentLength = response.headers().get("Request-Content-Length");
        assertNotNull(contentLength, "Expected the request to have a content length");
        return Long.parseLong(contentLength);
    }

    public boolean hasRequestContentLength(HttpResponse response) {
        return response.headers().contains("Request-Content-Length");
    }

    public void cancelStreamedMessage(Object msg) {
        if (msg instanceof StreamedHttpMessage) {
            Source.from((StreamedHttpMessage) msg).runWith(Sink.<HttpContent>cancelled(), materializer);
        } else {
            throw new IllegalArgumentException("Unknown message type: " + msg);
        }
    }
}
