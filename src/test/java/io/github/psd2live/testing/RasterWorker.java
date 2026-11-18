package io.github.psd2live.testing;

import java.io.*;
import java.nio.file.*;
public class RasterWorker {
    public static void main(String[] args) throws Exception {
        Path pack = Path.of(RasterWorker.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
        Path resources = Path.of(System.getenv("PSD2LIVE_PLUGIN_RESOURCES"));
        String result = Files.readString(pack.resolve("result.json"));
        try (var files = Files.list(pack)) {
            for (Path file : files.toList()) if (file.getFileName().toString().matches("[0-9a-f]{64}"))
                Files.copy(file, resources.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        var reader = new BufferedReader(new InputStreamReader(System.in));
        int sequence = 0;
        while (reader.readLine() != null)
            System.out.println("{\"protocol\":1,\"id\":" + (++sequence) + ",\"changes\":" + result + "}");
    }
}
