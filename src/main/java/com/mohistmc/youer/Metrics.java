/*
 * Copyright (C) MohistMC.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.mohistmc.youer;

import com.mohistmc.youer.api.ServerAPI;
import com.mohistmc.youer.util.ProxyUtils;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.HttpsURLConnection;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.craftbukkit.util.Versioning;
import org.bukkit.plugin.Plugin;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.spigotmc.SpigotConfig;

/**
 * bStats collects some data for plugin authors.
 * <p>
 * Check out <a href="https://bStats.org/">...</a> to learn more about bStats!
 */
public class Metrics {

    public static final ScheduledExecutorService METRICS = Executors.newScheduledThreadPool(1,
            Thread.ofVirtual()
                    .name("Metrics", 0)
                    .factory());
    // Hoshiestar start - report to the Hoshiestar bStats service (https://bstats.org/plugin/server-implementation/Hoshiestar/34852)
    // with the bStats v2 protocol, instead of MohistMC's "server implementation" entry
    public static final int SERVICE_ID = 34852;
    private static final String REPORT_URL = "https://bStats.org/api/v2/data/server-implementation";
    private static final String METRICS_VERSION = "3.1.0";
    // Hoshiestar end
    private final String serverUUID;
    private final List<CustomChart> charts = new ArrayList<>();

    public Metrics(String serverUUID) {
        this.serverUUID = serverUUID;

        startSubmitting();
    }

    private static void sendData(JSONObject data) throws Exception {
        if (data == null) {
            throw new IllegalArgumentException("Data cannot be null!");
        }

        HttpsURLConnection connection = (HttpsURLConnection) URI.create(REPORT_URL).toURL().openConnection(); // Hoshiestar - bStats v2 service API
        byte[] compressedData = compress(data.toString());

        connection.setRequestMethod("POST");
        connection.addRequestProperty("Accept", "application/json");
        connection.addRequestProperty("Connection", "close");
        connection.addRequestProperty("Content-Encoding", "gzip"); // We gzip our request
        connection.addRequestProperty("Content-Length", String.valueOf(compressedData.length));
        connection.setRequestProperty("Content-Type", "application/json"); // We send our data in JSON format
        connection.setRequestProperty("User-Agent", "Metrics-Service/1");

        // Send data
        connection.setDoOutput(true);
        DataOutputStream outputStream = new DataOutputStream(connection.getOutputStream());
        outputStream.write(compressedData);
        outputStream.flush();
        outputStream.close();

        connection.getInputStream().close(); // We don't care about the response - Just send our data :)
    }

    private static byte[] compress(final String str) throws IOException {
        if (str == null) {
            return null;
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        GZIPOutputStream gzip = new GZIPOutputStream(outputStream);
        gzip.write(str.getBytes(StandardCharsets.UTF_8));
        gzip.close();
        return outputStream.toByteArray();
    }

    public void addCustomChart(CustomChart chart) {
        if (chart == null) {
            throw new IllegalArgumentException("Chart cannot be null!");
        }
        charts.add(chart);
    }

    private void startSubmitting() {
        METRICS.scheduleAtFixedRate(() -> {
            if (MinecraftServer.getServer().hasStopped()) {
                return;
            }
            submitData();
        }, (long) (1000 * 60 * (3 + Math.random() * 3)), 1000 * 60 * 30, TimeUnit.MILLISECONDS); // Hoshiestar - bStats requires a randomized first report
    }

    private JSONObject getServiceData() {
        JSONObject service = new JSONObject();
        service.put("id", SERVICE_ID);
        JSONArray customCharts = new JSONArray();
        for (CustomChart customChart : charts) {
            JSONObject chart = customChart.getRequestJsonObject();
            if (chart == null) {
                continue;
            }
            customCharts.add(chart);
        }
        service.put("customCharts", customCharts);
        return service;
    }

    private JSONObject getServerData() {
        JSONObject data = new JSONObject();
        data.put("serverUUID", serverUUID);
        data.put("metricsVersion", METRICS_VERSION);
        data.put("playerAmount", Bukkit.getOnlinePlayers().size());
        data.put("onlineMode", Bukkit.getOnlineMode() ? 1 : 0);
        data.put("bukkitVersion", Bukkit.getVersion());
        data.put("bukkitName", Bukkit.getName());
        data.put("javaVersion", System.getProperty("java.version"));
        data.put("osName", System.getProperty("os.name"));
        data.put("osArch", System.getProperty("os.arch"));
        data.put("osVersion", System.getProperty("os.version"));
        data.put("coreCount", Runtime.getRuntime().availableProcessors());
        return data;
    }

    private void submitData() {
        final JSONObject data = getServerData();
        data.put("service", getServiceData());

        try {
            sendData(data);
        } catch (Exception ignored) {
        }
    }

    public static abstract class CustomChart {
        final String chartId;

        CustomChart(String chartId) {
            if (chartId == null || chartId.isEmpty()) {
                throw new IllegalArgumentException("ChartId cannot be null or empty!");
            }
            this.chartId = chartId;
        }

        private JSONObject getRequestJsonObject() {
            JSONObject chart = new JSONObject();
            chart.put("chartId", chartId);
            try {
                JSONObject data = getChartData();
                if (data == null) {
                    return null;
                }
                chart.put("data", data);
            } catch (Throwable t) {
                return null;
            }
            return chart;
        }

        protected abstract JSONObject getChartData() throws Exception;

    }

    public static class SimplePie extends CustomChart {

        private final Callable<String> callable;

        public SimplePie(String chartId, Callable<String> callable) {
            super(chartId);
            this.callable = callable;
        }

        @Override
        protected JSONObject getChartData() throws Exception {
            JSONObject data = new JSONObject();
            String value = callable.call();
            if (value == null || value.isEmpty()) {
                return null;
            }
            data.put("value", value);
            return data;
        }
    }

    public static class DrilldownPie extends CustomChart {

        private final Callable<Map<String, Map<String, Integer>>> callable;

        public DrilldownPie(String chartId, Callable<Map<String, Map<String, Integer>>> callable) {
            super(chartId);
            this.callable = callable;
        }

        @Override
        public JSONObject getChartData() throws Exception {
            JSONObject data = new JSONObject();
            JSONObject values = new JSONObject();
            Map<String, Map<String, Integer>> map = callable.call();
            if (map == null || map.isEmpty()) {
                return null;
            }
            boolean reallyAllSkipped = true;
            for (Map.Entry<String, Map<String, Integer>> entryValues : map.entrySet()) {
                JSONObject value = new JSONObject();
                boolean allSkipped = true;
                for (Map.Entry<String, Integer> valueEntry : map.get(entryValues.getKey()).entrySet()) {
                    value.put(valueEntry.getKey(), valueEntry.getValue());
                    allSkipped = false;
                }
                if (!allSkipped) {
                    reallyAllSkipped = false;
                    values.put(entryValues.getKey(), value);
                }
            }
            if (reallyAllSkipped) {
                return null;
            }
            data.put("values", values);
            return data;
        }
    }

    public static class SingleLineChart extends CustomChart {

        private final Callable<Integer> callable;

        public SingleLineChart(String chartId, Callable<Integer> callable) {
            super(chartId);
            this.callable = callable;
        }

        @Override
        protected JSONObject getChartData() throws Exception {
            JSONObject data = new JSONObject();
            int value = callable.call();
            if (value == 0) {
                return null;
            }
            data.put("value", value);
            return data;
        }

    }

    public static class MohistMetrics {
        private static String bucket(int n) { // Hoshiestar - grouped counts read better in a pie
            if (n == 0) return "0";
            if (n <= 10) return "1-10";
            if (n <= 50) return "11-50";
            if (n <= 100) return "51-100";
            if (n <= 200) return "101-200";
            if (n <= 300) return "201-300";
            return "300+";
        }

        public static void startMetrics() {
            File configFile = new File(new File((File) MinecraftServer.getServer().options.valueOf("plugins"), "bStats"), "config.yml");
            YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);

            if (!config.isSet("serverUuid")) {
                config.addDefault("enabled", true);
                config.addDefault("serverUuid", UUID.randomUUID().toString());
                config.addDefault("logFailedRequests", false);

                config.options().header(
                        """
                                bStats collects some data for plugin authors like how many servers are using their plugins.
                                To honor their work, you should not disable it.
                                This has nearly no effect on the server performance!
                                Check out https://bStats.org/ to learn more :)"""
                ).copyDefaults(true);
                try {
                    config.save(configFile);
                } catch (IOException ignored) {
                }
            }

            String serverUUID = config.getString("serverUuid");

            if (config.getBoolean("enabled", true)) {
                Metrics metrics = new Metrics(serverUUID);

                // servers, OS, cores and location are bStats defaults; everything else is a custom chart of the service
                metrics.addCustomChart(new SingleLineChart("players", () -> Bukkit.getOnlinePlayers().size()));
                metrics.addCustomChart(new SimplePie("online_mode", () -> Bukkit.getOnlineMode() ? "online" : "offline"));
                metrics.addCustomChart(new SimplePie("minecraft_version", () -> SharedConstants.getCurrentVersion().name()));
                metrics.addCustomChart(new SimplePie("hoshiestar_version", com.mohistmc.youer.util.YouerVersion::getVersion));
                metrics.addCustomChart(new SimplePie("neoforge_version", () -> net.neoforged.fml.ModList.get().getModContainerById("neoforge").map(c -> c.getModInfo().getVersion().toString()).orElse(null)));
                metrics.addCustomChart(new SimplePie("proxy", () -> String.valueOf(ProxyUtils.useProxy())));
                metrics.addCustomChart(new SimplePie("mod_count", () -> bucket(ServerAPI.modlists_All.size())));
                metrics.addCustomChart(new SimplePie("plugin_count", () -> bucket(Bukkit.getPluginManager().getPlugins().length)));

                metrics.addCustomChart(new DrilldownPie("java_version", () -> {
                    Map<String, Map<String, Integer>> map = new HashMap<>();
                    String javaVersion = System.getProperty("java.version");
                    Map<String, Integer> entry = new HashMap<>();
                    entry.put(javaVersion, 1);

                    String majorVersion = javaVersion.split("\\.")[0];
                    String release;

                    int indexOf = javaVersion.lastIndexOf('.');

                    if (majorVersion.equals("1")) {
                        release = "Java " + javaVersion.substring(0, indexOf);
                    } else {
                        Matcher versionMatcher = Pattern.compile("\\d+").matcher(majorVersion);
                        if (versionMatcher.find()) {
                            majorVersion = versionMatcher.group(0);
                        }
                        release = "Java " + majorVersion;
                    }
                    map.put(release, entry);

                    return map;
                }));

                metrics.addCustomChart(new DrilldownPie("mod_plugin", () -> {
                    Map<String, Map<String, Integer>> map = new HashMap<>();

                    Map<String, Integer> modslist = new HashMap<>();
                    String[] mods = ServerAPI.modlists_All.toString().replace("[", "").replace("]", "").split(", ");
                    for (String x : mods) {
                        if (x.equals("minecraft") || x.equals("neoforge") || x.equals("youer")) {
                            continue;
                        }
                        modslist.put(x, 1);
                    }

                    Map<String, Integer> pluginlist = new HashMap<>();
                    for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                        if (plugin.isEnabled() && !plugin.getName().equals("youer")) {
                            pluginlist.put(plugin.getPluginMeta().getName(), 1);
                        }
                    }
                    map.put("mods", modslist);
                    map.put("plugins", pluginlist);

                    return map;
                }));
            }
        }
    }
}