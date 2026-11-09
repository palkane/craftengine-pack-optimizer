package net.sicilia.pack;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Explicit operator integration check; never prints credentials or signed URLs. */
public final class ShardS3Probe {
    public static void main(String[] args) throws Exception {
        JsonObject config = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        var s3 = new ShardS3(config.get("endpoint").getAsString(), config.get("bucket").getAsString(),
                config.get("region").getAsString(), config.get("access_key_id").getAsString(),
                config.get("access_key_secret").getAsString(), config.get("upload_path").getAsString() + ".shards", 120);
        Path state = Path.of(args[2]);
        boolean verifyOnly = args.length > 3 && args[3].equals("--verify-only");
        var manifest = verifyOnly
                ? new Gson().fromJson(Files.readString(state.resolve("current.json")), NamespaceShards.Manifest.class)
                : NamespaceShardHost.publishGroup(Path.of(args[1]), state, 8, s3::upload);
        HttpClient client = HttpClient.newHttpClient();
        int checks = 0;
        for (var shard : manifest.shards()) {
            String url = s3.url("GET", shard.file());
            var response = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || !Arrays.equals(response.body(),
                    Files.readAllBytes(state.resolve("objects").resolve(shard.file()))))
                throw new AssertionError("Signed shard download differs");
            checks++;
            var anonymous = client.send(HttpRequest.newBuilder(URI.create(url.substring(0, url.indexOf('?'))))
                    .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.discarding());
            if (anonymous.statusCode() != 403) throw new AssertionError("Shard is not private");
            checks++;
        }
        Files.writeString(state.resolve("download-checks.json"),
                "{\"passed\":" + checks + ",\"failed\":0,\"client_acceptance\":\"pending\"}\n");
        System.out.println("PASS " + (verifyOnly ? "verified published" : "uploaded/HEAD-verified")
                + " " + manifest.shards().size()
                + " shards; " + checks + " full-download/private-access checks");
    }
}
