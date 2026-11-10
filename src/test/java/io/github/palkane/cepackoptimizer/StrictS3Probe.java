package io.github.palkane.cepackoptimizer;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Read-only integration check; credentials and signed URLs are never logged. */
public final class StrictS3Probe {
    public static void main(String[] args) throws Exception {
        var config = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        Path state = Path.of(args[1]);
        var publication = new Gson().fromJson(Files.readString(state.resolve("current.json")),
                StrictNamespaceHost.Published.class);
        var s3 = new ShardS3(config.get("endpoint").getAsString(), config.get("bucket").getAsString(),
                config.get("region").getAsString(), config.get("access_key_id").getAsString(),
                config.get("access_key_secret").getAsString(), config.get("upload_path").getAsString() + ".strict", 120);
        Path pack = state.resolve("objects").resolve(publication.file());
        StrictNamespaceHost.validate(pack, publication.result());
        String url = s3.url("GET", publication.file());
        var client = HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).build();
        var response = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(90)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200 || !Arrays.equals(response.body(), Files.readAllBytes(pack)))
            throw new AssertionError("Strict signed download differs");
        String remoteHash = response.headers().firstValue("x-amz-meta-sha1")
                .or(() -> response.headers().firstValue("x-cos-meta-sha1")).orElse("");
        if (!remoteHash.equals(publication.result().outputSha1()))
            throw new AssertionError("Strict remote hash mismatch");
        var anonymous = client.send(HttpRequest.newBuilder(URI.create(url.substring(0, url.indexOf('?'))))
                .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.discarding());
        if (anonymous.statusCode() != 403) throw new AssertionError("Strict pack is not private");
        System.out.println("PASS strict ZIP/map binding, exactly two namespaces, full signed download, metadata hash, anonymous403; bytes="
                + response.body().length + ", sha1=" + remoteHash);
    }
}
