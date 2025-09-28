package io.github.aplini.autoupdateplugins;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipException;

@Plugin(id = "autoupdateplugins", name = "AutoUpdatePlugins", version = "${project.version}")
public class AutoUpdatePluginsVelocity {
    private final ProxyServer proxy;
    private final Logger logger;

    // 防止重复运行更新
    boolean lock = false;
    // 等待更新完成后再重载配置
    boolean awaitReload = false;
    // 计时器对象
    Timer timer = null;
    // 更新处理线程
    CompletableFuture<Void> future = null;

    File tempFile;
    Map<String, Object> temp;
    Map<String, Object> config;

    List<String> logList = new ArrayList<>();

    @Inject
    public AutoUpdatePluginsVelocity(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        ensureDefaultConfigs();
        loadConfig();
        scheduleTasks();
        registerCommands();
        logger.info("[AUP] Velocity 初始化完成");
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    private void ensureDefaultConfigs() {
        try {
            Path pluginDir = Path.of("./plugins/AutoUpdatePlugins");
            Files.createDirectories(pluginDir);
            Path localesDir = pluginDir.resolve("Locales");
            Files.createDirectories(localesDir);
            // 导出默认配置文件（如不存在）
            exportIfMissing(pluginDir.resolve("config.yml"), "/config.yml");
            exportIfMissing(localesDir.resolve("config_en.yml"), "/Locales/config_en.yml");
        } catch (Exception e) {
            logger.warn("[AUP] 创建配置目录失败: {}", e.getMessage());
        }
    }

    private void exportIfMissing(Path target, String resourcePath) {
        try {
            if (Files.exists(target))
                return;
            try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
                if (in == null)
                    return;
                Files.copy(in, target);
            }
        } catch (Exception ignored) {
        }
    }

    public void loadConfig() {
        try {
            Path configPath = Path.of("./plugins/AutoUpdatePlugins/config.yml");
            if (!Files.exists(configPath)) {
                Files.createFile(configPath);
            }
            Yaml yaml = new Yaml();
            Object data = yaml.load(Files.newInputStream(configPath));
            @SuppressWarnings("unchecked")
            Map<String, Object> map = data instanceof Map ? (Map<String, Object>) data : new LinkedHashMap<>();
            this.config = map;

            tempFile = new File("./plugins/AutoUpdatePlugins/temp.yml");
            if (!tempFile.exists()) {
                tempFile.createNewFile();
                Files.write(tempFile.toPath(), "previous: {}\n".getBytes(StandardCharsets.UTF_8));
            }
            Object tempData = yaml.load(Files.newInputStream(tempFile.toPath()));
            @SuppressWarnings("unchecked")
            Map<String, Object> tempMap = tempData instanceof Map ? (Map<String, Object>) tempData
                    : new LinkedHashMap<>();
            this.temp = tempMap;
            if (temp.get("previous") == null) {
                temp.put("previous", new HashMap<>());
            }
            saveTemp();
        } catch (Exception e) {
            logger.warn("[AUP] 加载配置失败: {}", e.getMessage());
            this.config = new LinkedHashMap<>();
            this.temp = new LinkedHashMap<>();
            temp.put("previous", new HashMap<>());
        }
    }

    public void saveTemp() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("previous:\n");
            Map<String, Object> previous = new LinkedHashMap<>();
            Object prevObj = temp.get("previous");
            if (prevObj instanceof Map) {
                Map<?, ?> rawPrev = (Map<?, ?>) prevObj;
                for (Map.Entry<?, ?> e : rawPrev.entrySet()) {
                    String key = String.valueOf(e.getKey());
                    Object val = e.getValue();
                    if (val instanceof Map) {
                        Map<String, Object> sec = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> s : ((Map<?, ?>) val).entrySet()) {
                            sec.put(String.valueOf(s.getKey()), s.getValue());
                        }
                        previous.put(key, sec);
                    } else {
                        previous.put(key, val);
                    }
                }
            }
            for (Map.Entry<String, Object> e : previous.entrySet()) {
                sb.append("  ").append(e.getKey()).append(":\n");
                Map<?, ?> sec = (Map<?, ?>) e.getValue();
                for (Map.Entry<?, ?> s : sec.entrySet()) {
                    sb.append("    ").append(String.valueOf(s.getKey())).append(": ")
                            .append(String.valueOf(s.getValue())).append("\n");
                }
            }
            Files.write(tempFile.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            logger.warn("[AUP] 保存临时文件失败: {}", e.getMessage());
        }
    }

    public void scheduleTasks() {
        long startupDelay = getConfigLong("startupDelay", 64);
        long startupCycle = getConfigLong("startupCycle", 61200);
        if (startupCycle < 256 && !getConfigBoolean("disableUpdateCheckIntervalTooLow", false)) {
            logger.warn("[AUP] ### 更新检查间隔过低将造成性能问题! ###");
            startupCycle = 512;
        }
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        timer = new Timer();
        timer.schedule(new updatePlugins(), startupDelay * 1000, startupCycle * 1000);
        logger.info("[AUP] 定时器: {}s 后启动, 每 {}s 周期", startupDelay, startupCycle);
    }

    private void registerCommands() {
        // 使用 Brigadier 注册 /aup 命令
        var literal = com.mojang.brigadier.builder.LiteralArgumentBuilder.<com.velocitypowered.api.command.CommandSource>literal(
                "aup")
                .executes(ctx -> {
                    ctx.getSource().sendPlainMessage(
                            "IpacEL > AutoUpdatePlugins: 自动更新插件\n  指令:\n    - /aup reload\n    - /aup update\n    - /aup log\n    - /aup stop");
                    return 1;
                })
                .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.<com.velocitypowered.api.command.CommandSource>literal(
                        "reload").executes(ctx -> {
                            if (lock) {
                                awaitReload = true;
                                ctx.getSource().sendPlainMessage("[AUP] 当前正在运行更新, 配置重载将被推迟");
                                return 1;
                            }
                            loadConfig();
                            ctx.getSource().sendPlainMessage("[AUP] 已完成重载");
                            scheduleTasks();
                            return 1;
                        }))
                .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.<com.velocitypowered.api.command.CommandSource>literal(
                        "update").executes(ctx -> {
                            if (lock && !getConfigBoolean("disableLook", false)) {
                                ctx.getSource().sendPlainMessage("[AUP] 已有一个未完成的更新正在运行");
                                return 1;
                            }
                            ctx.getSource().sendPlainMessage("[AUP] 更新开始运行!");
                            new Timer().schedule(new updatePlugins(), 0);
                            return 1;
                        }))
                .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.<com.velocitypowered.api.command.CommandSource>literal(
                        "log").executes(ctx -> {
                            ctx.getSource().sendPlainMessage("[AUP] 完整日志:");
                            for (String li : logList) {
                                ctx.getSource().sendPlainMessage("  | " + li);
                            }
                            return 1;
                        }))
                .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.<com.velocitypowered.api.command.CommandSource>literal(
                        "stop").executes(ctx -> {
                            if (lock) {
                                if (future != null) {
                                    future.cancel(true);
                                }
                                ctx.getSource().sendPlainMessage("[AUP] 正在停止当前更新...");
                            } else {
                                ctx.getSource().sendPlainMessage("[AUP] 已停止当前更新");
                            }
                            return 1;
                        }));

        com.velocitypowered.api.command.BrigadierCommand command = new com.velocitypowered.api.command.BrigadierCommand(
                literal.build());
        var meta = proxy.getCommandManager().metaBuilder("aup").build();
        proxy.getCommandManager().register(meta, command);
    }

    // 配置访问方法
    private Object getConfig(String key) {
        return config.get(key);
    }

    private String getConfigString(String key, String def) {
        Object v = getConfig(key);
        return v == null ? def : String.valueOf(v);
    }

    private boolean getConfigBoolean(String key, boolean def) {
        Object v = getConfig(key);
        if (v instanceof Boolean)
            return (Boolean) v;
        if (v instanceof String)
            return Boolean.parseBoolean((String) v);
        return def;
    }

    private long getConfigLong(String key, long def) {
        Object v = getConfig(key);
        if (v instanceof Number)
            return ((Number) v).longValue();
        if (v instanceof String)
            try {
                return Long.parseLong((String) v);
            } catch (Exception ignored) {
            }
        return def;
    }

    // 更新逻辑
    private class updatePlugins extends TimerTask {
        String _fileName = "[???] ";
        String _nowParser = "[???] ";
        int _fail = 0;
        int _success = 0;
        int _updateFul = 0;
        int _allRequests = 0;
        long _startTime;
        float _allFileSize = 0;

        String c_file;
        String c_url;
        String c_tempPath;
        String c_updatePath;
        String c_filePath;
        String c_get;
        boolean c_zipFileCheck;
        boolean c_getPreRelease;

        public void run() {
            future = CompletableFuture.runAsync(() -> {
                if (lock && !getConfigBoolean("disableLook", false)) {
                    log(logLevel.WARN, "### 更新程序重复启动或出现错误? ###");
                    return;
                }
                lock = true;

                runUpdate();

                log(logLevel.INFO, "[## 更新全部完成 ##]");
                log(logLevel.INFO, "  - 耗时: " + Math.round((System.nanoTime() - _startTime) / 1_000_000_000.0) + " 秒");

                String st = "  - ";
                if (_fail != 0)
                    st += "失败: " + _fail + ", ";
                if (_success != 0)
                    st += "更新: " + _success + ", ";
                log(logLevel.INFO, st + "成功: " + _updateFul);

                log(logLevel.INFO, "  - 网络请求: " + _allRequests);
                log(logLevel.INFO, "  - 下载文件: " + String.format("%.2f", _allFileSize / 1048576) + "MB");

                if (awaitReload) {
                    awaitReload = false;
                    loadConfig();
                    scheduleTasks();
                    logger.info("[AUP] 已完成重载");
                }

                lock = false;
            });
        }

        public void runUpdate() {
            logList = new ArrayList<>();
            _startTime = System.nanoTime();

            log(logLevel.INFO, "[## 开始运行自动更新 ##]");

            List<?> list = (List<?>) getConfig("list");
            if (list == null) {
                log(logLevel.WARN, "更新列表配置错误?");
                return;
            }

            for (Object _li : list) {
                if (future != null && future.isCancelled()) {
                    log(logLevel.INFO, "已停止当前更新");
                    return;
                }

                _fail++;
                _updateFul++;

                Map<?, ?> li = (Map<?, ?>) _li;
                if (li == null) {
                    log(logLevel.WARN, "更新列表配置错误? 项目为空");
                    continue;
                }

                c_file = String.valueOf(sel(li.get("file"), ""));
                c_url = String.valueOf(sel(li.get("url"), "")).trim();
                if (c_file.isEmpty() || c_url.isEmpty()) {
                    log(logLevel.WARN, "更新列表配置错误? 缺少基本配置");
                    continue;
                }

                Matcher tempMatcher = Pattern.compile("([^/\\\\]+)\\..*$").matcher(c_file);
                if (tempMatcher.find()) {
                    _fileName = "[" + tempMatcher.group(1) + "] ";
                } else {
                    _fileName = "[" + c_file + "] ";
                }

                tempMatcher = Pattern.compile("(.*/|.*\\\\)([^/\\\\]+)$").matcher(c_file);
                if (tempMatcher.find()) {
                    getPath(tempMatcher.group(1));
                    c_updatePath = c_file;
                    c_filePath = c_file;
                    c_tempPath = getPath(getConfigString("tempPath", "./plugins/AutoUpdatePlugins/temp/"))
                            + tempMatcher.group(2);
                } else if (li.get("path") != null) {
                    c_updatePath = getPath(String.valueOf(li.get("path"))) + c_file;
                    c_filePath = c_updatePath;
                    c_tempPath = getPath(getConfigString("tempPath", "./plugins/AutoUpdatePlugins/temp/")) + c_file;
                } else {
                    c_updatePath = getPath(String
                            .valueOf(sel(li.get("updatePath"), getConfigString("updatePath", "./plugins/update/"))))
                            + c_file;
                    c_filePath = getPath(
                            String.valueOf(sel(li.get("filePath"), getConfigString("filePath", "./plugins/"))))
                            + c_file;
                    c_tempPath = getPath(getConfigString("tempPath", "./plugins/AutoUpdatePlugins/temp/")) + c_file;
                }

                c_get = String.valueOf(sel(li.get("get"), ""));
                c_zipFileCheck = (boolean) sel(li.get("zipFileCheck"), getConfigBoolean("zipFileCheck", true));
                c_getPreRelease = (boolean) sel(li.get("getPreRelease"), false);

                log(logLevel.DEBUG, "正在检查更新...");

                String dUrl = getFileUrl(c_url, c_get);
                if (dUrl == null) {
                    log(logLevel.WARN, _nowParser + "解析文件直链时出现错误, 将跳过此更新");
                    continue;
                }
                dUrl = checkURL(dUrl);

                String feature = "";
                String pPath = "";
                if (getConfigBoolean("enablePreviousUpdate", true)) {
                    feature = getFeature(dUrl);
                    pPath = "previous." + li.toString().hashCode();
                    if (temp.get(pPath) != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> p = (Map<String, Object>) temp.get(pPath);
                        if (String.valueOf(p.getOrDefault("dUrl", "")).equals(dUrl) &&
                                String.valueOf(p.getOrDefault("feature", "")).equals(feature)) {
                            log(logLevel.MARK, "[缓存] 文件已是最新版本");
                            _fail--;
                            continue;
                        }
                    }
                }

                if (!downloadFile(dUrl, c_tempPath)) {
                    log(logLevel.WARN, "下载文件时出现异常, 将跳过此更新");
                    delFile(c_tempPath);
                    continue;
                }

                float fileSize = new File(c_tempPath).length();
                _allFileSize += fileSize;

                if (c_zipFileCheck && Pattern.compile(getConfigString("zipFileCheckList", "\\.(?:jar|zip)$"))
                        .matcher(c_file).find()) {
                    if (!isJARFileIntact(c_tempPath)) {
                        log(logLevel.WARN, "[Zip 完整性检查] 文件不完整, 将跳过此更新");
                        delFile(c_tempPath);
                        continue;
                    }
                }

                if (getConfigBoolean("enablePreviousUpdate", true)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> p = (Map<String, Object>) temp.computeIfAbsent(pPath, k -> new HashMap<>());
                    p.put("file", c_file);
                    p.put("time", nowDate());
                    p.put("dUrl", dUrl);
                    p.put("feature", feature);
                    saveTemp();
                }

                if (getConfigBoolean("ignoreDuplicates", true) && (boolean) sel(li.get("ignoreDuplicates"), true)) {
                    String updatePathFileHas = fileHash(c_updatePath);
                    String tempFileHas = fileHash(c_tempPath);
                    if (Objects.equals(tempFileHas, updatePathFileHas)
                            || Objects.equals(tempFileHas, fileHash(c_filePath))) {
                        log(logLevel.MARK, "文件已是最新版本");
                        _fail--;
                        delFile(c_tempPath);
                        continue;
                    }
                }

                float oldFileSize = new File(c_updatePath).exists() ? new File(c_updatePath).length()
                        : new File(c_filePath).length();

                try {
                    Files.move(Path.of(c_tempPath), Path.of(c_updatePath), StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) {
                    log(logLevel.WARN, e.getMessage());
                }

                log(logLevel.DEBUG, "更新完成 [" + String.format("%.2f", oldFileSize / 1048576) + "MB] -> ["
                        + String.format("%.2f", fileSize / 1048576) + "MB]");

                _success++;
                _fail--;

                _fileName = "[???] ";
                _nowParser = "[???] ";
            }
        }

        // 复制所有工具方法
        public boolean isJARFileIntact(String filePath) {
            try (JarFile jarFile = new JarFile(new File(filePath))) {
                jarFile.close();
                return true;
            } catch (ZipException e) {
                return false;
            } catch (Exception e) {
                return false;
            }
        }

        public String fileHash(String filePath) {
            try {
                byte[] data = Files.readAllBytes(Paths.get(filePath));
                byte[] hash = MessageDigest.getInstance("MD5").digest(data);
                return new BigInteger(1, hash).toString(16);
            } catch (Exception e) {
                return "null";
            }
        }

        public String getFileUrl(String _url, String matchFileName) {
            String url = _url.replaceAll("/$", "");

            if (url.contains("://github.com/")) {
                _nowParser = "[GitHub] ";
                Matcher matcher = Pattern.compile("/([^/]+)/([^/]+)$").matcher(url);
                if (matcher.find()) {
                    String data;
                    Map<?, ?> map;
                    if (c_getPreRelease) {
                        data = httpGet("https://api.github.com/repos" + matcher.group(0) + "/releases");
                        if (data == null)
                            return null;
                        map = (Map<?, ?>) new com.google.gson.Gson().fromJson(data, ArrayList.class).get(0);
                    } else {
                        data = httpGet("https://api.github.com/repos" + matcher.group(0) + "/releases/latest");
                        if (data == null)
                            return null;
                        map = new com.google.gson.Gson().fromJson(data, HashMap.class);
                    }
                    ArrayList<?> assets = (ArrayList<?>) map.get("assets");
                    for (Object _li : assets) {
                        Map<?, ?> li = (Map<?, ?>) _li;
                        String fileName = String.valueOf(li.get("name"));
                        if (matchFileName.isEmpty() || Pattern.compile(matchFileName).matcher(fileName).matches()) {
                            String dUrl = String.valueOf(li.get("browser_download_url"));
                            log(logLevel.DEBUG, _nowParser + "找到版本: " + dUrl);
                            return dUrl;
                        }
                    }
                    log(logLevel.WARN, "[GitHub] 没有匹配的文件: " + url);
                    return null;
                }
                log(logLevel.WARN, "[GitHub] 未找到存储库路径: " + url);
                return null;
            }

            else if (url.contains("://ci.")) {
                _nowParser = "[Jenkins] ";
                String data = httpGet(url + "/lastSuccessfulBuild/api/json");
                if (data == null)
                    return null;
                Map<?, ?> map = new com.google.gson.Gson().fromJson(data, HashMap.class);
                ArrayList<?> artifacts = (ArrayList<?>) map.get("artifacts");
                for (Object _li : artifacts) {
                    Map<?, ?> li = (Map<?, ?>) _li;
                    String fileName = String.valueOf(li.get("fileName"));
                    if (matchFileName.isEmpty() || Pattern.compile(matchFileName).matcher(fileName).matches()) {
                        String dUrl = url + "/lastSuccessfulBuild/artifact/" + li.get("relativePath");
                        log(logLevel.DEBUG, _nowParser + "找到版本: " + dUrl);
                        return dUrl;
                    }
                }
                log(logLevel.WARN, "[Jenkins] 没有匹配的文件: " + url);
                return null;
            }

            else if (url.contains("://www.spigotmc.org/")) {
                _nowParser = "[Spigot] ";
                Matcher matcher = Pattern.compile("([0-9]+)$").matcher(url);
                if (matcher.find()) {
                    String dUrl = "https://api.spiget.org/v2/resources/" + matcher.group(1) + "/download";
                    log(logLevel.DEBUG, _nowParser + "找到版本: " + dUrl);
                    return dUrl;
                }
                log(logLevel.WARN, "[Spigot] URL 解析错误, 不包含插件 ID?: " + url);
                return null;
            }

            else if (url.contains("://modrinth.com/")) {
                _nowParser = "[Modrinth] ";
                Matcher matcher = Pattern.compile("/([^/]+)$").matcher(url);
                if (matcher.find()) {
                    String data = httpGet("https://api.modrinth.com/v2/project" + matcher.group(0) + "/version");
                    if (data == null)
                        return null;
                    ArrayList<?> versions = new com.google.gson.Gson().fromJson(data, ArrayList.class);
                    for (Object _version : versions) {
                        Map<?, ?> version = (Map<?, ?>) _version;
                        ArrayList<?> files = (ArrayList<?>) version.get("files");
                        for (Object _file : files) {
                            Map<?, ?> file = (Map<?, ?>) _file;
                            String fileName = String.valueOf(file.get("filename"));
                            if (matchFileName.isEmpty() || Pattern.compile(matchFileName).matcher(fileName).matches()) {
                                String dUrl = String.valueOf(file.get("url"));
                                log(logLevel.DEBUG, _nowParser + "找到版本: " + dUrl);
                                return dUrl;
                            }
                        }
                    }
                    log(logLevel.WARN, "[Modrinth] 没有匹配的文件: " + url);
                    return null;
                }
                log(logLevel.WARN, "[Modrinth] URL 解析错误, 未找到项目名称: " + url);
                return null;
            }

            else if (url.contains("://dev.bukkit.org/")) {
                _nowParser = "[Bukkit] ";
                String dUrl = url + "/files/latest";
                log(logLevel.DEBUG, _nowParser + "找到版本: " + dUrl);
                return dUrl;
            }

            else if (url.contains("://builds.guizhanss.com/")) {
                _nowParser = "[鬼斩构建站] ";
                Matcher matcher = Pattern.compile("/([^/]+)/([^/]+)/([^/]+)$").matcher(url);
                if (matcher.find()) {
                    String dUrl = "https://builds.guizhanss.com/api/download" + matcher.group(0) + "/latest";
                    log(logLevel.DEBUG, _nowParser + "找到版本: " + dUrl);
                    return dUrl;
                }
                log(logLevel.WARN, _nowParser + "未找到存储库路径: " + url);
                return null;
            }

            else if (url.contains("://www.minebbs.com/")) {
                _nowParser = "[MineBBS] ";
                return url + "/download";
            }

            else if (url.contains("://legacy.curseforge.com/")) {
                _nowParser = "[CurseForge] ";
                String html = httpGet(url);
                if (html == null)
                    return null;
                String[] lines = html.split("<a");
                for (String li : lines) {
                    Matcher matcher = Pattern.compile("data-project-id=\"([0-9]+)\"").matcher(li);
                    if (matcher.find()) {
                        String data = httpGet(
                                "https://api.curseforge.com/servermods/files?projectIds=" + matcher.group(1));
                        if (data == null)
                            return null;
                        ArrayList<?> arr = (ArrayList<?>) new com.google.gson.Gson().fromJson(data, ArrayList.class);
                        Map<?, ?> map = (Map<?, ?>) arr.get(arr.size() - 1);
                        String dUrl = String.valueOf(map.get("downloadUrl"));
                        log(logLevel.DEBUG, _nowParser + "找到版本: " + dUrl);
                        return dUrl;
                    }
                }
                log(logLevel.WARN, _nowParser + "未找到项目 ID: " + url);
                return null;
            }

            else {
                _nowParser = "[URL] ";
                log(logLevel.DEBUG, _nowParser + _url);
                return _url;
            }
        }

        public Object sel(Object in1, Object in2) {
            if (in1 == null) {
                return in2;
            }
            return in1;
        }

        public okhttp3.Call fetch(String url, boolean head) {
            _allRequests++;
            okhttp3.OkHttpClient.Builder client = new okhttp3.OkHttpClient.Builder();

            if (!getConfigString("proxy.type", "DIRECT").equals("DIRECT")) {
                client.proxy(new Proxy(
                        Proxy.Type.valueOf(getConfigString("proxy.type", "HTTP")),
                        new InetSocketAddress(
                                getConfigString("proxy.host", "127.0.0.1"),
                                (int) getConfigLong("proxy.port", 7890))));
            }

            if (!getConfigBoolean("sslVerify", true)) {
                try {
                    X509TrustManager trustManager = new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    };

                    SSLContext sslContext = SSLContext.getInstance("SSL");
                    sslContext.init(null, new TrustManager[] { trustManager }, new SecureRandom());
                    client.sslSocketFactory(sslContext.getSocketFactory(), trustManager);

                } catch (Exception e) {
                    log(logLevel.NET_WARN, "[HTTP] [sslVerify: false]" + e.getMessage());
                }
            }

            okhttp3.Request.Builder request = new okhttp3.Request.Builder().url(url);
            if (head)
                request.head();

            List<?> list = (List<?>) getConfig("setRequestProperty");
            if (list != null) {
                for (Object _li : list) {
                    Map<?, ?> li = (Map<?, ?>) _li;
                    request.header(String.valueOf(li.get("name")), String.valueOf(li.get("value")));
                }
            }

            return client.build().newCall(request.build());
        }

        public String httpGet(String url) {
            try (okhttp3.Response response = fetch(url, false).execute()) {
                if (!response.isSuccessful()) {
                    return null;
                }
                return response.body().string();
            } catch (Exception e) {
                log(logLevel.NET_WARN, "[HTTP] " + e.getMessage());
            }
            return null;
        }

        public boolean downloadFile(String url, String path) {
            try (okhttp3.Response response = fetch(url, false).execute()) {
                if (!response.isSuccessful()) {
                    return false;
                }
                try (InputStream inputStream = response.body().byteStream();
                        OutputStream outputStream = new FileOutputStream(path)) {

                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                }
                return true;
            } catch (Exception e) {
                log(logLevel.NET_WARN, "[HTTP] " + e.getMessage());
            }
            return false;
        }

        public String getFeature(String url) {
            try (okhttp3.Response response = fetch(url, true).execute()) {
                if (!response.isSuccessful()) {
                    return "??_" + nowDate().hashCode();
                }
                String contentLength = String.valueOf(sel(response.headers().get("Content-Length"), -1));
                if (!contentLength.equals("-1")) {
                    return "CL_" + contentLength;
                }
                String location = String.valueOf(sel(response.headers().get("Location"), "Invalid"));
                if (!location.equals("Invalid")) {
                    return "LH_" + location.hashCode();
                }
            } catch (Exception e) {
                log(logLevel.NET_WARN, "[HTTP.HEAD] " + e.getMessage());
            }
            return "??_" + nowDate().hashCode();
        }

        public void log(logLevel level, String text) {
            List<String> userLogLevel = getConfigStringList("logLevel");
            if (userLogLevel.isEmpty()) {
                userLogLevel = List.of("DEBUG", "MARK", "INFO", "WARN", "NET_WARN");
            }

            if (userLogLevel.contains(level.name)) {
                switch (level.name) {
                    case "DEBUG":
                        logger.info(_fileName + text);
                        break;
                    case "INFO":
                        logger.info(text);
                        break;
                    case "MARK":
                        logger.info("[AUP] " + _fileName + text);
                        break;
                    case "WARN", "NET_WARN":
                        logger.warn(_fileName + text);
                        break;
                }
            }

            logList.add(level.color + (level.name.equals("INFO") ? "" : _fileName) + text);
        }

        enum logLevel {
            DEBUG("", "DEBUG"),
            INFO("", "INFO"),
            MARK("§a", "MARK"),
            WARN("§e", "WARN"),
            NET_WARN("§e", "NET_WARN");

            private final String color;
            private final String name;

            logLevel(String color, String name) {
                this.color = color;
                this.name = name;
            }
        }

        public String nowDate() {
            LocalDateTime now = LocalDateTime.now();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            return now.format(formatter);
        }

        public String checkURL(String url) {
            try {
                return new URI(url.trim().replace(" ", "%20")).toASCIIString();
            } catch (URISyntaxException e) {
                log(logLevel.WARN, "[URI] URL 无效或不规范: " + url);
                return null;
            }
        }

        public void delFile(String path) {
            new File(path).delete();
        }
    }

    // 工具方法
    public String getPath(String path) {
        Path directory = Paths.get(path);
        try {
            Files.createDirectories(directory);
            return directory + "/";
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<String> getConfigStringList(String key) {
        Object v = getConfig(key);
        if (v instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> list = (List<String>) v;
            return list;
        }
        return new ArrayList<>();
    }
}
