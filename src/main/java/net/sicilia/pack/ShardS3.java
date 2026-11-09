package net.sicilia.pack;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** The existing private S3 bucket, using immutable content-addressed object keys. */
final class ShardS3 {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
            .proxy(ProxySelector.of(null)).build();
    private final String host, prefix, region, access, secret;
    private final int validity;

    ShardS3(String endpoint, String bucket, String region, String access, String secret,
            String prefix, int validity) {
        this.host = bucket + "." + endpoint;
        this.region = region;
        this.access = access;
        this.secret = secret;
        this.prefix = prefix;
        this.validity = validity;
        if (validity < 1 || validity > 604800) throw new IllegalArgumentException("Invalid S3 URL validity");
    }

    String url(String method, String file) throws Exception {
        return url(method, file, Map.of());
    }

    private String url(String method, String file, Map<String, String> headers) throws Exception {
        Instant now = Instant.now();
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC).format(now);
        String day = stamp.substring(0, 8);
        String scope = day + "/" + region + "/s3/aws4_request";
        SortedMap<String, String> params = new TreeMap<>();
        params.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        params.put("X-Amz-Credential", access + "/" + scope);
        params.put("X-Amz-Date", stamp);
        params.put("X-Amz-Expires", Integer.toString(validity));
        SortedMap<String, String> signed = new TreeMap<>(headers);
        signed.put("host", host);
        String signedNames = String.join(";", signed.keySet());
        params.put("X-Amz-SignedHeaders", signedNames);
        String query = String.join("&", params.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).toList());
        String path = "/" + String.join("/", Arrays.stream((prefix + "/" + file).split("/", -1))
                .map(ShardS3::encode).toList());
        String canonicalHeaders = String.join("", signed.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue() + "\n").toList());
        String canonical = method + "\n" + path + "\n" + query + "\n" + canonicalHeaders
                + "\n" + signedNames + "\nUNSIGNED-PAYLOAD";
        byte[] key = ("AWS4" + secret).getBytes(StandardCharsets.UTF_8);
        for (String part : List.of(day, region, "s3", "aws4_request")) key = hmac(key, part);
        String signature = HexFormat.of().formatHex(hmac(key,
                "AWS4-HMAC-SHA256\n" + stamp + "\n" + scope + "\n" + hash(canonical.getBytes(StandardCharsets.UTF_8))));
        return "https://" + host + path + "?" + query + "&X-Amz-Signature=" + signature;
    }

    void upload(String file, byte[] bytes, String sha1) throws Exception {
        Map<String, String> headers = Map.of("x-amz-meta-sha1", sha1,
                "x-amz-checksum-sha256", Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(bytes)));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url("PUT", file, headers)))
                .timeout(Duration.ofMinutes(5)).header("x-amz-meta-sha1", sha1)
                .header("Content-Type", "application/zip");
        builder.header("x-amz-checksum-sha256", headers.get("x-amz-checksum-sha256"));
        HttpRequest request = builder.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
        int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        if (status / 100 != 2) throw new IOException("S3 shard upload HTTP " + status);
        HttpResponse<Void> head = client.send(HttpRequest.newBuilder(URI.create(url("HEAD", file)))
                .timeout(Duration.ofSeconds(30)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
        String remoteHash = head.headers().firstValue("x-amz-meta-sha1")
                .or(() -> head.headers().firstValue("x-cos-meta-sha1")).orElse("");
        if (head.statusCode() != 200 || !remoteHash.equals(sha1)
                || head.headers().firstValueAsLong("Content-Length").orElse(-1) != bytes.length)
            throw new IOException("S3 shard verification failed");
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")
                .replace("*", "%2A").replace("%7E", "~");
    }
    private static byte[] hmac(byte[] key, String text) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
