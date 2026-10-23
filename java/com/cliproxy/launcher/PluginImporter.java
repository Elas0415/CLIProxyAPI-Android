package com.cliproxy.launcher;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs a .so plugin into the backend plugins directory and enables it in
 * config.yaml under plugins.configs.&lt;id&gt;.enabled.
 *
 * The whole top-level "plugins:" section is rebuilt so that repeated imports
 * cannot produce duplicate YAML keys.
 */
final class PluginImporter {

    private PluginImporter() {
    }

    /** Returns the plugin id derived from a .so file name. */
    static String pluginId(String fileName) {
        String base = fileName;
        if (base.toLowerCase().endsWith(".so")) {
            base = base.substring(0, base.length() - 3);
        }
        return base.trim();
    }

    /**
     * Extracts the first .so entry from a zip stream into pluginDir.
     * Returns the extracted file.
     */
    static File extractSoFromZip(InputStream zipIn, File pluginDir) throws IOException {
        if (!pluginDir.exists() && !pluginDir.mkdirs()) {
            throw new IOException("cannot create plugins dir");
        }
        try (ZipInputStream zis = new ZipInputStream(zipIn)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
                if (slash >= 0) {
                    name = name.substring(slash + 1);
                }
                if (!name.toLowerCase().endsWith(".so")) {
                    continue;
                }
                File target = new File(pluginDir, name);
                try (OutputStream out = Files.newOutputStream(target.toPath())) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = zis.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                }
                target.setExecutable(true, false);
                return target;
            }
        }
        throw new IOException("zip 中未找到 .so 文件");
    }

    /**
     * Rewrites config.yaml so that plugins.enabled is true and
     * plugins.configs.&lt;id&gt;.enabled is true, preserving any other plugin
     * ids that were already enabled.
     */
    static void enablePlugin(File configFile, String id) throws IOException {
        if (configFile == null || !configFile.exists()) {
            throw new IOException("config.yaml not found");
        }
        List<String> lines = Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8);

        List<String> existingEnabled = collectEnabledPluginIds(lines);

        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (indentOf(lines.get(i)) == 0 && lines.get(i).trim().equals("plugins:")) {
                start = i;
                break;
            }
        }

        List<String> out = new ArrayList<>();
        if (start < 0) {
            out.addAll(lines);
            if (!out.isEmpty() && !out.get(out.size() - 1).trim().isEmpty()) {
                out.add("");
            }
            appendPluginsBlock(out, existingEnabled, id);
        } else {
            // Everything before the plugins section.
            for (int i = 0; i < start; i++) {
                out.add(lines.get(i));
            }
            // Skip the whole original plugins section. Comment lines (even at
            // column 0) belong to the section and are consumed with it.
            int end = start + 1;
            while (end < lines.size()) {
                String t = lines.get(end);
                if (t.trim().isEmpty() || t.trim().startsWith("#")) {
                    end++;
                    continue;
                }
                if (indentOf(t) == 0) {
                    break;
                }
                end++;
            }
            appendPluginsBlock(out, existingEnabled, id);
            // Everything after the plugins section.
            for (int i = end; i < lines.size(); i++) {
                out.add(lines.get(i));
            }
        }

        StringBuilder sb = new StringBuilder();
        for (String l : out) {
            sb.append(l).append('\n');
        }
        Files.write(configFile.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> collectEnabledPluginIds(List<String> lines) {
        List<String> ids = new ArrayList<>();
        int pluginsIndent = -1;
        int configsIndent = -1;
        for (String line : lines) {
            String trimmed = line.trim();
            if (pluginsIndent < 0) {
                if (indentOf(line) == 0 && trimmed.equals("plugins:")) {
                    pluginsIndent = 0;
                }
                continue;
            }
            if (indentOf(line) == 0) {
                break;
            }
            if (configsIndent < 0) {
                if (trimmed.equals("configs:") || trimmed.startsWith("configs: ")) {
                    configsIndent = indentOf(line);
                }
                continue;
            }
            if (indentOf(line) == configsIndent + 2 && trimmed.endsWith(":")
                    && !trimmed.startsWith("#")) {
                ids.add(trimmed.substring(0, trimmed.length() - 1).trim());
            }
        }
        return ids;
    }

    private static void appendPluginsBlock(List<String> out, List<String> existingIds, String newId) {
        List<String> ids = new ArrayList<>();
        for (String id : existingIds) {
            if (!id.isEmpty() && !ids.contains(id)) {
                ids.add(id);
            }
        }
        if (!ids.contains(newId)) {
            ids.add(newId);
        }

        out.add("plugins:");
        out.add("  enabled: true");
        out.add("  dir: \"plugins\"");
        out.add("  configs:");
        for (String id : ids) {
            out.add("    " + id + ":");
            out.add("      enabled: true");
        }
    }

    private static int indentOf(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }
}
