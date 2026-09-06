package com.mta.faultinjection.sidecar;

import java.nio.file.Path;

public final class SidecarApp {

    private SidecarApp() {}

    public static void main(String[] args) {
        try {
            String config = parseConfig(args);
            var props = SidecarConfigLoader.load(Path.of(config));
            SidecarRuntime.run(System.in, System.out, props);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    static String parseConfig(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--config".equals(args[i])) {
                return args[i + 1];
            }
        }
        throw new IllegalArgumentException("missing --config <path>");
    }
}
