package com.knowledge;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class KnowledgeServer {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        String webRoot = args.length > 1 ? args[1] : findWebRoot();

        File dataDir = new File("data");
        if (!dataDir.exists()) dataDir.mkdirs();

        DatabaseHelper db = new DatabaseHelper("data/knowledge.db");
        ApiServer server = new ApiServer(db, webRoot);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down...");
            server.stop();
            db.close();
        }));

        server.start(port);
        System.out.println("====================================================");
        System.out.println("  Knowledge Server started");
        System.out.println("  Web UI: http://127.0.0.1:" + port);
        System.out.println("  API:    http://127.0.0.1:" + port + "/api/stats");
        System.out.println("====================================================");
        System.out.println("Press Ctrl+C to stop.");
    }

    private static String findWebRoot() {
        String[] candidates = {"web", "../web", "server/../web"};
        for (String c : candidates) {
            Path p = Paths.get(c, "index.html");
            if (Files.exists(p) && Files.isRegularFile(p)) {
                return Paths.get(c).toAbsolutePath().normalize().toString();
            }
        }
        return new File("web").getAbsolutePath();
    }
}
