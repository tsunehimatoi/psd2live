package io.github.psd2live.testing;

import java.io.*;
public class OpacityWorker {
    public static void main(String[] args) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        int sequence = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.contains("\"mode\":\"crash\"")) System.exit(7);
            if (line.contains("\"mode\":\"wait\"")) { reader.readLine(); return; }
            if (line.contains("\"mode\":\"invalid\"")) { System.out.println("{\"protocol\":99}"); continue; }
            if (line.contains("\"mode\":\"bad-write\"")) {
                System.out.println("{\"protocol\":1,\"id\":" + (++sequence) + ",\"commands\":[{\"op\":\"mesh_opacity\",\"id\":\"body\",\"value\":2}]}");
                continue;
            }
            System.out.println("{\"protocol\":1,\"id\":" + (++sequence) + ",\"commands\":[{\"op\":\"mesh_opacity\",\"id\":\"body\",\"value\":0.5}]}");
        }
    }
}
