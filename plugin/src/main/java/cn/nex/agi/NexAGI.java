package cn.nex.agi;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NexAGI —— Minecraft 聊天桥。
 *
 * 公屏：玩家聊天带触发词 -> POST 中转（mode=chat）-> AI 只能用 mc_say 工具说话
 * 私聊：/agi -> POST 中转（mode=direct，独立会话，NDJSON 流式）-> 打字机式贴到 action bar，说完发到聊天
 */
public class NexAGI extends JavaPlugin implements Listener, TabCompleter {

    /** /agi 的 Tab 提示占位符（lang.yml 的 tab-hint 优先） */
    public static final String AI_HINT = "<在此输入聊天内容>";

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private HttpServer http;
    private ExecutorService httpPool;
    private YamlConfiguration lang;

    private final Map<String, Long> seenChat = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastCall = new ConcurrentHashMap<>();
    /** 正在等回复的 /agi 玩家 */
    private final Map<UUID, StreamState> pending = new ConcurrentHashMap<>();

    /** 公屏上下文缓冲：每个元素带序号，触发时取“上次触发之后”的部分 */
    private static class Ctx {
        final long seq;
        final String text;

        Ctx(long seq, String text) {
            this.seq = seq;
            this.text = text;
        }
    }

    private final Deque<Ctx> chatCtx = new ArrayDeque<>();
    private long chatSeq = 0L;
    private long lastTriggerSeq = 0L;
    private static final int CTX_MAX = 20;      // 最多带多少条上下文
    private static final int CTX_BUFFER = 200;  // 缓冲里最多存多少条

    private String instance = "生存服";
    private String trigger = "罗希";
    private String bridgeUrl = "http://127.0.0.1:8899/mc/message";
    private String token = "";
    private String replyFormat = "§6[NEX AGI] §f";
    private String aiReplyFormat = "§5[NEX AGI 私聊] §f";
    private String streamFormat = "§d✦ §7{text}";
    private List<String> thinkingFrames = new ArrayList<>();
    private String thinkingPrefix = "§9✦ ";
    private String thinkingText = "thinkink";
    private List<String> thinkingDots = new ArrayList<>();
    private long thinkingDotIntervalMs = 100L;
    private List<float[]> thinkingStops = new ArrayList<>(); // 每个停靠点的 [饱和度, 明度]
    private float thinkingHue = 225f;
    private long thinkingIntervalMs = 400L;
    private int streamTickPeriod = 2;      // 每几 tick 播一个字
    private int streamCharsPerTick = 1;    // 每次播几个字
    private double thinkingSpeed = 0.03;   // 等待动画每帧的相位增量
    private int listenPort = 8900;
    private long cooldownMs = 1500L;
    private boolean debug = false;

    /** 指令执行 */
    private boolean commandEnabled = true;
    private List<String> commandBlacklist = new ArrayList<>();
    private String lastRequesterName = "";
    private UUID lastRequesterId = null;
    private boolean lastRequesterOp = false;
    private long lastRequesterAt = 0L;

    /** 一次 /agi 的流式状态 */
    private static class StreamState {
        final StringBuilder buf = new StringBuilder();
        int revealed = 0;
        int frame = 0;
        int tickCounter = 0;
        boolean done = false;
        boolean delivered = false;
        String finalText = "";
        BukkitTask task;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        loadLang();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand agiCmd = getCommand("agi");
        if (agiCmd != null) agiCmd.setTabCompleter(this);
        PluginCommand adminCmd = getCommand("nexagi");
        if (adminCmd != null) adminCmd.setTabCompleter(this);
        startHttp();
        getLogger().info("NexAGI 已启动：实例=" + instance + " 端口=" + listenPort + " 中转=" + bridgeUrl);
    }

    @Override
    public void onDisable() {
        stopHttp();
        for (StreamState st : pending.values()) {
            if (st.task != null) st.task.cancel();
        }
        pending.clear();
        seenChat.clear();
        lastCall.clear();
        synchronized (chatCtx) {
            chatCtx.clear();
        }
    }

    private void loadSettings() {
        reloadConfig();
        instance = getConfig().getString("instance", "生存服");
        trigger = getConfig().getString("trigger", "罗希");
        if (trigger == null || trigger.isEmpty()) trigger = "罗希";
        bridgeUrl = getConfig().getString("bridge-url", "http://127.0.0.1:8899/mc/message");
        token = getConfig().getString("token", "");
        if (token == null) token = "";
        listenPort = getConfig().getInt("listen-port", 8900);
        cooldownMs = getConfig().getLong("cooldown-ms", 1500L);
        debug = getConfig().getBoolean("debug", false);
        streamTickPeriod = Math.max(1, getConfig().getInt("stream-tick-period", 2));
        streamCharsPerTick = Math.max(1, getConfig().getInt("stream-chars-per-tick", 1));
        thinkingSpeed = getConfig().getDouble("thinking-speed", 0.03);
        if (thinkingSpeed <= 0.001) thinkingSpeed = 0.03;
        commandEnabled = getConfig().getBoolean("command-enabled", true);
        commandBlacklist = new ArrayList<>(getConfig().getStringList("command-blacklist"));
        if (commandBlacklist.isEmpty()) {
            commandBlacklist.addAll(java.util.Arrays.asList(
                    "stop", "restart", "op", "deop", "ban", "ban-ip", "pardon", "pardon-ip",
                    "kick", "whitelist", "plugins", "pl", "reload", "execute"));
        }
        thinkingIntervalMs = getConfig().getLong("thinking-interval-ms", 400L);
        if (thinkingIntervalMs < 100L) thinkingIntervalMs = 100L;
    }

    /** 读 lang.yml：插件所有输出到玩家眼前的文字都在这个文件里 */
    private void loadLang() {
        File f = new File(getDataFolder(), "lang.yml");
        if (!f.exists()) {
            try {
                saveResource("lang.yml", false);
            } catch (Exception ignored) {
            }
        }
        lang = YamlConfiguration.loadConfiguration(f);
        replyFormat = color(lang.getString("broadcast-format", "§6[NEX AGI] §f"));
        aiReplyFormat = color(lang.getString("private-format", "§5[NEX AGI 私聊] §f"));
        streamFormat = color(lang.getString("stream-format", "§d✦ §7{text}"));

        List<String> frames = lang.getStringList("thinking-frames");
        for (int i = 0; i < frames.size(); i++) frames.set(i, color(frames.get(i)));
        if (frames.isEmpty()) {
            frames = new ArrayList<>();
            frames.add("§d✦ §7thinkink. §d✦");
            frames.add("§d✧ §7thinkink.. §d✧");
            frames.add("§d✦ §7thinkink... §d✦");
            frames.add("§d✧ §7thinkink.... §d✧");
        }
        thinkingFrames = frames;

        thinkingPrefix = color(lang.getString("thinking-prefix", "&9✦ "));
        thinkingText = lang.getString("thinking-text", "thinkink");
        if (thinkingText == null) thinkingText = "";
        thinkingDots = new ArrayList<>(lang.getStringList("thinking-dots"));
        if (thinkingDots.isEmpty()) {
            for (int i = 0; i <= 9; i++) thinkingDots.add(".".repeat(i));
        }
        thinkingDotIntervalMs = lang.getLong("thinking-dot-interval-ms", 300L);
        if (thinkingDotIntervalMs < 50L) thinkingDotIntervalMs = 50L;

        // 渐变：先把颜色转成 HSV，色相锁定在“最鲜艳那个色”的蓝，
        // 只对饱和度和明度做插值——否则白->蓝的直线插值会走灰白区域，发闷
        List<TextColor> cols = new ArrayList<>();
        for (String hex : lang.getStringList("thinking-colors")) {
            TextColor c = parseHex(hex);
            if (c != null) cols.add(c);
        }
        if (cols.isEmpty()) {
            cols.add(TextColor.color(0xFFFFFF));
            cols.add(TextColor.color(0x2E5BFF));
            cols.add(TextColor.color(0x00238C));
            cols.add(TextColor.color(0xFFFFFF));
        }
        thinkingStops = new ArrayList<>();
        float maxSat = -1f;
        for (TextColor c : cols) {
            float[] hsv = rgbToHsv(c.red(), c.green(), c.blue());
            if (hsv[1] > maxSat) {
                maxSat = hsv[1];
                thinkingHue = hsv[0];
            }
            thinkingStops.add(new float[]{hsv[1], hsv[2]});
        }
    }

    private static String color(String s) {
        if (s == null) return "";
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    /** 取 lang.yml 里的文字，支持 {name} 占位符 */
    private String msg(String key, String def, Object... kv) {
        String s = (lang == null) ? null : lang.getString(key);
        if (s == null || s.isEmpty()) s = def;
        s = color(s);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("{" + kv[i] + "}", String.valueOf(kv[i + 1]));
        }
        return s;
    }

    /** Tab 提示文字（lang.yml 的 tab-hint） */
    private String hint() {
        return msg("tab-hint", AI_HINT);
    }

    // ---------------- 上行：公屏聊天 ----------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPaperChat(AsyncChatEvent event) {
        String text;
        try {
            text = PlainTextComponentSerializer.plainText().serialize(event.message());
        } catch (Throwable t) {
            return;
        }
        handleChat(event.getPlayer(), text);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBukkitChat(AsyncPlayerChatEvent event) {
        handleChat(event.getPlayer(), event.getMessage());
    }

    private void handleChat(Player player, String raw) {
        if (player == null || raw == null || raw.isEmpty()) return;

        long now = System.currentTimeMillis();
        String key = player.getName() + "|" + raw;
        Long prev = seenChat.put(key, now);
        if (prev != null && now - prev < 2500L) return; // 两套聊天事件重复触发，去重
        if (seenChat.size() > 500) {
            seenChat.entrySet().removeIf(e -> now - e.getValue() > 10000L);
        }

        // 不管有没有触发词，先进上下文缓冲
        rememberChat(player.getName() + "：" + raw);

        int idx = indexOfIgnoreCase(raw, trigger);
        if (idx < 0) return;

        String text = raw.substring(idx + trigger.length()).trim();
        while (!text.isEmpty() && ",，.。:：;；!！?？~～ 、\u3000".indexOf(text.charAt(0)) >= 0) {
            text = text.substring(1).trim();
        }
        if (text.isEmpty()) text = "（只喊了你的名字，没别的了）";
        if (text.length() > 500) text = text.substring(0, 500);

        Long last = lastCall.get(player.getUniqueId());
        if (last != null && now - last < cooldownMs) return;
        lastCall.put(player.getUniqueId(), now);
        rememberRequester(player);

        final String msg = text;
        final String ctx = takeContext();
        if (debug) {
            getLogger().info("[上行] " + player.getName() + " op=" + player.isOp() + " -> " + msg
                    + " 上下文=" + (ctx.isEmpty() ? 0 : ctx.split("\n").length) + "条");
        }
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> postToBridge(player, msg, "chat", 8000, ctx));
    }

    /** 记一条公屏聊天进缓冲 */
    private void rememberChat(String line) {
        synchronized (chatCtx) {
            chatSeq++;
            chatCtx.addLast(new Ctx(chatSeq, line));
            while (chatCtx.size() > CTX_BUFFER) chatCtx.removeFirst();
        }
    }

    /** 取“上次触发之后”的聊天，最多 20 条（不足 20 条就只发这部分） */
    private String takeContext() {
        List<Ctx> picked = new ArrayList<>();
        synchronized (chatCtx) {
            for (Ctx c : chatCtx) {
                if (c.seq > lastTriggerSeq) picked.add(c);
            }
            if (picked.size() > CTX_MAX) {
                picked = new ArrayList<>(picked.subList(picked.size() - CTX_MAX, picked.size()));
            }
            lastTriggerSeq = chatSeq;
        }
        StringBuilder sb = new StringBuilder();
        for (Ctx c : picked) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(c.text);
        }
        return sb.toString();
    }

    // ---------------- 上行：公屏 / /agi ----------------

    private String buildBody(Player player, String text, String mode) {
        return buildBody(player, text, mode, "");
    }

    private String buildBody(Player player, String text, String mode, String ctx) {
        return MiniJson.obj(
                "instance", instance,
                "player", player.getName(),
                "uuid", player.getUniqueId().toString(),
                "op", player.isOp(),
                "text", text,
                "port", listenPort,
                "mode", mode,
                "context", ctx == null ? "" : ctx
        );
    }

    private void postToBridge(Player player, String text, String mode, int readTimeoutMs) {
        postToBridge(player, text, mode, readTimeoutMs, "");
    }

    private void postToBridge(Player player, String text, String mode, int readTimeoutMs, String ctx) {
        HttpURLConnection conn = null;
        try {
            conn = openPost(buildBody(player, text, mode, ctx), readTimeoutMs);
            int code = conn.getResponseCode();
            if (debug) getLogger().info("[上行] 中转返回 " + code);
        } catch (Exception e) {
            getLogger().warning("[上行] 发送失败: " + e.getClass().getSimpleName() + " " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private HttpURLConnection openPost(String body, int readTimeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(bridgeUrl).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(readTimeoutMs);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Connection", "close");
        if (!token.isEmpty()) conn.setRequestProperty("X-Token", token);
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(data.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(data);
        }
        return conn;
    }

    /** /agi：先起等待动画，等 AI 调 mc_agi 工具把话发回来 */
    private void startAgi(Player player, String text) {
        final UUID id = player.getUniqueId();
        StreamState st = new StreamState();

        StreamState old = pending.put(id, st);
        if (old != null && old.task != null) old.task.cancel();

        st.task = Bukkit.getScheduler().runTaskTimer(this, () -> renderTick(id, st), 0L, streamTickPeriod);

        // 60 秒还没等到工具回复就收尾，避免星星一直转
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (pending.get(id) != st) return;
            synchronized (st) {
                if (st.done) return;
            }
            if (st.task != null) st.task.cancel();
            pending.remove(id);
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                p.sendActionBar(Component.empty());
                p.sendMessage(msg("empty-answer", "（我这会儿没想出要说什么）"));
            }
        }, 20L * 60L);

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> postToBridge(player, text, "agi", 8000));
    }

    /** mc_agi 工具下发的内容：打字机播完，再发一条私聊 */
    private void deliverFromTool(Player target, String text) {
        final UUID id = target.getUniqueId();
        StreamState st = pending.get(id);
        if (st == null) {
            st = new StreamState();
            pending.put(id, st);
            final StreamState fs = st;
            st.task = Bukkit.getScheduler().runTaskTimer(this, () -> renderTick(id, fs), 0L, streamTickPeriod);
        }
        synchronized (st) {
            st.buf.setLength(0);
            st.buf.append(text);
            st.revealed = 0;
            st.delivered = false;
            st.done = true;
            st.finalText = text;
        }
    }

    /** 记下“最近一次找 AI 说话的人”，指令执行只认他 */
    private void rememberRequester(Player p) {
        lastRequesterName = p.getName();
        lastRequesterId = p.getUniqueId();
        lastRequesterOp = p.isOp();
        lastRequesterAt = System.currentTimeMillis();
    }

    private Player findPlayer(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) return p;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(name)) return online;
        }
        return null;
    }

    /** 每 tick 跑一次：没字时播星星，有字时逐字往外吐，吐完发聊天 */
    private void renderTick(UUID id, StreamState st) {
        Player p = Bukkit.getPlayer(id);
        if (p == null || !p.isOnline()) {
            if (st.task != null) st.task.cancel();
            pending.remove(id);
            return;
        }

        String visible;
        boolean finished;
        boolean thinking;
        synchronized (st) {
            int total = st.buf.length();
            if (st.revealed < total) {
                int step = Math.max(1, streamCharsPerTick);
                int backlog = total - st.revealed;
                if (backlog > 40) step = Math.max(step, backlog / 10); // 落后太多就加速，别让长回答拖太久
                st.revealed = Math.min(total, st.revealed + step);
            }
            visible = st.buf.substring(0, st.revealed);
            thinking = (visible.isEmpty()) && !st.done;
            finished = st.done && st.revealed >= total && !st.delivered;
            if (finished) st.delivered = true;
        }

        if (finished) {
            if (st.task != null) st.task.cancel();
            pending.remove(id, st);
            p.sendActionBar(net.kyori.adventure.text.Component.empty());
            deliverPrivate(p, st.finalText);
            return;
        }

        if (thinking) {
            p.sendActionBar(buildThinking(st));
            synchronized (st) {
                st.frame++;
            }
            return;
        }

        String s = streamFormat.replace("{text}", visible.replace("\n", " "));
        if (s.length() > 90) s = "…" + s.substring(s.length() - 88);
        p.sendActionBar(LEGACY.deserialize(s));
    }

    /** 等待动画：文字 + 每 0.5 秒在 "..." / "......" 之间切换 + 光带缓慢扫过 */
    private Component buildThinking(StreamState st) {
        String body = thinkingBody(st);
        if (body.isEmpty()) {
            if (thinkingFrames.isEmpty()) return Component.text("");
            return LEGACY.deserialize(thinkingFrames.get(st.frame % thinkingFrames.size()));
        }
        TextComponent.Builder b = Component.text();
        if (!thinkingPrefix.isEmpty()) b.append(LEGACY.deserialize(thinkingPrefix));
        double phase = (st.frame * thinkingSpeed) % 1.0;
        b.append(gradientText(body, phase));
        return b.build();
    }

    /** 当前这一帧要显示的完整文字（含变长的点） */
    private String thinkingBody(StreamState st) {
        if (thinkingText.isEmpty()) return "";
        String dots = "";
        if (!thinkingDots.isEmpty()) {
            long elapsedMs = st.frame * (streamTickPeriod * 50L);
            int i = (int) ((elapsedMs / thinkingDotIntervalMs) % thinkingDots.size());
            dots = thinkingDots.get(i);
        }
        return thinkingText + dots;
    }

    /** 逐字渐变一句话 */
    private Component gradientText(String text, double phase) {
        TextComponent.Builder b = Component.text();
        int n = Math.max(1, text.length());
        for (int i = 0; i < text.length(); i++) {
            b.append(Component.text(String.valueOf(text.charAt(i))).color(gradient(phase + (double) i / n)));
        }
        return b.build();
    }

    /** 在色带上插值，t 会自动循环；全程锁定同一个蓝色相，保证饱和度 */
    private TextColor gradient(double t) {
        if (thinkingStops.isEmpty()) return TextColor.color(0xFFFFFF);
        if (thinkingStops.size() == 1) {
            float[] only = thinkingStops.get(0);
            return fromHsv(thinkingHue, only[0], only[1]);
        }
        t = t - Math.floor(t);
        double seg = 1.0 / (thinkingStops.size() - 1);
        int i = (int) Math.min(thinkingStops.size() - 2, Math.floor(t / seg));
        double local = (t - i * seg) / seg;
        float[] a = thinkingStops.get(i);
        float[] b = thinkingStops.get(i + 1);
        float s = (float) (a[0] + (b[0] - a[0]) * local);
        float v = (float) (a[1] + (b[1] - a[1]) * local);
        return fromHsv(thinkingHue, s, v);
    }

    private static float[] rgbToHsv(int r, int g, int b) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float d = max - min;
        float h = 0f;
        if (d > 0.0001f) {
            if (max == rf) h = 60f * (((gf - bf) / d) % 6f);
            else if (max == gf) h = 60f * (((bf - rf) / d) + 2f);
            else h = 60f * (((rf - gf) / d) + 4f);
            if (h < 0f) h += 360f;
        }
        float s = (max <= 0.0001f) ? 0f : d / max;
        return new float[]{h, s, max};
    }

    private static TextColor fromHsv(float h, float s, float v) {
        float c = v * s;
        float hp = ((h % 360f) + 360f) % 360f / 60f;
        float x = c * (1f - Math.abs((hp % 2f) - 1f));
        float r = 0f, g = 0f, b = 0f;
        if (hp < 1f) { r = c; g = x; }
        else if (hp < 2f) { r = x; g = c; }
        else if (hp < 3f) { g = c; b = x; }
        else if (hp < 4f) { g = x; b = c; }
        else if (hp < 5f) { r = x; b = c; }
        else { r = c; b = x; }
        float m = v - c;
        return TextColor.color(clamp255((r + m) * 255f), clamp255((g + m) * 255f), clamp255((b + m) * 255f));
    }

    private static int clamp255(float f) {
        int i = Math.round(f);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    private static TextColor parseHex(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 6) return null;
        try {
            return TextColor.color(Integer.parseInt(s, 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 私聊回给提问本人（只有他看得到） */
    private void deliverPrivate(Player player, String reply) {
        final UUID id = player.getUniqueId();
        final String text = (reply == null || reply.trim().isEmpty())
                ? msg("empty-answer", "（我这会儿没想出要说什么）")
                : reply.trim();
        Bukkit.getScheduler().runTask(this, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) return;
            p.sendMessage(aiReplyFormat + text);
            if (debug) getLogger().info("[私聊] -> " + p.getName() + ": " + text);
        });
    }

    // ---------------- 下行：mc_say -> 公屏，mc_agi -> 私聊 ----------------

    private void startHttp() {
        stopHttp();
        try {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", listenPort), 0);
            httpPool = Executors.newFixedThreadPool(2);
            http.setExecutor(httpPool);
            http.createContext("/say", new SayHandler());
            http.createContext("/reply", new ReplyHandler());
            http.createContext("/cmd", new CmdHandler());
            http.createContext("/health", new HealthHandler());
            http.start();
        } catch (Exception e) {
            getLogger().severe("本地端口 " + listenPort + " 启动失败（端口被占？）: " + e.getMessage());
        }
    }

    private void stopHttp() {
        if (http != null) {
            try {
                http.stop(0);
            } catch (Exception ignored) {
            }
            http = null;
        }
        if (httpPool != null) {
            httpPool.shutdownNow();
            httpPool = null;
        }
    }

    private class SayHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                reply(ex, 405, MiniJson.obj("ok", false, "error", "use POST"));
                return;
            }
            if (!token.isEmpty()) {
                String t = ex.getRequestHeaders().getFirst("X-Token");
                if (!token.equals(t)) {
                    reply(ex, 403, MiniJson.obj("ok", false, "error", "bad token"));
                    return;
                }
            }
            Map<String, String> json = MiniJson.parse(readBody(ex.getRequestBody()));
            String text = json.get("text");
            if (text == null || text.trim().isEmpty()) {
                reply(ex, 400, MiniJson.obj("ok", false, "error", "empty text"));
                return;
            }
            if (debug) getLogger().info("[下行] " + text);
            say(text.trim());
            reply(ex, 200, MiniJson.obj("ok", true, "instance", instance));
        }
    }

    /** mc_agi 工具：私聊内容回给指定玩家（自带打字机） */
    private class ReplyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                reply(ex, 405, MiniJson.obj("ok", false, "error", "use POST"));
                return;
            }
            if (!token.isEmpty()) {
                String t = ex.getRequestHeaders().getFirst("X-Token");
                if (!token.equals(t)) {
                    reply(ex, 403, MiniJson.obj("ok", false, "error", "bad token"));
                    return;
                }
            }
            Map<String, String> json = MiniJson.parse(readBody(ex.getRequestBody()));
            String who = json.get("player");
            String text = json.get("text");
            if (who == null || who.trim().isEmpty()) {
                reply(ex, 400, MiniJson.obj("ok", false, "error", "empty player"));
                return;
            }
            if (text == null || text.trim().isEmpty()) {
                reply(ex, 400, MiniJson.obj("ok", false, "error", "empty text"));
                return;
            }
            Player target = findPlayer(who.trim());
            if (target == null) {
                reply(ex, 200, MiniJson.obj("ok", false, "error", "玩家不在线: " + who, "player", who));
                return;
            }
            if (debug) getLogger().info("[私聊下发] -> " + target.getName() + ": " + text);
            deliverFromTool(target, text.trim());
            reply(ex, 200, MiniJson.obj("ok", true, "player", target.getName(), "instance", instance));
        }
    }

    /** server_command：执行一条服务器指令（只允许最近一次发起请求的 OP 触发） */
    private class CmdHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                reply(ex, 405, MiniJson.obj("ok", false, "error", "use POST"));
                return;
            }
            if (!token.isEmpty()) {
                String t = ex.getRequestHeaders().getFirst("X-Token");
                if (!token.equals(t)) {
                    reply(ex, 403, MiniJson.obj("ok", false, "error", "bad token"));
                    return;
                }
            }
            Map<String, String> json = MiniJson.parse(readBody(ex.getRequestBody()));
            String cmd = json.get("command");
            if (cmd == null || cmd.trim().isEmpty()) {
                reply(ex, 400, MiniJson.obj("ok", false, "error", "empty command"));
                return;
            }
            cmd = cmd.trim();
            while (cmd.startsWith("/")) cmd = cmd.substring(1).trim();
            if (cmd.isEmpty()) {
                reply(ex, 400, MiniJson.obj("ok", false, "error", "empty command"));
                return;
            }
            if (!commandEnabled) {
                reply(ex, 200, MiniJson.obj("ok", false, "error", "服务器关闭了指令执行（command-enabled: false）"));
                return;
            }
            if (lastRequesterId == null || System.currentTimeMillis() - lastRequesterAt > 300000L) {
                reply(ex, 200, MiniJson.obj("ok", false, "error", "没有最近的请求者，拒绝执行"));
                return;
            }
            if (!lastRequesterOp) {
                reply(ex, 200, MiniJson.obj("ok", false, "error", "请求者 " + lastRequesterName + " 不是 OP，拒绝执行"));
                return;
            }
            String first = cmd.split("\\s+")[0].toLowerCase();
            int colon = first.indexOf(':');
            if (colon >= 0) first = first.substring(colon + 1);
            for (String b : commandBlacklist) {
                if (first.equalsIgnoreCase(b)) {
                    reply(ex, 200, MiniJson.obj("ok", false, "error", "指令被禁用: " + first));
                    return;
                }
            }
            final String finalCmd = cmd;
            final String requester = lastRequesterName;
            Bukkit.getScheduler().runTask(NexAGI.this, () -> {
                Bukkit.getConsoleSender().sendMessage(msg("command-console",
                        "§8[NEX AGI 执行] /{cmd} （由 {player} 请求）", "cmd", finalCmd, "player", requester));
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd);
            });
            reply(ex, 200, MiniJson.obj("ok", true, "command", finalCmd, "by", requester, "instance", instance));
        }
    }

    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            reply(ex, 200, MiniJson.obj("ok", true, "instance", instance, "port", listenPort,
                    "online", Bukkit.getOnlinePlayers().size()));
        }
    }

    /** 真正把话说出去（主线程） */
    public void say(String text) {
        final String msg = replyFormat + text;
        Bukkit.getScheduler().runTask(this, () -> {
            Bukkit.getConsoleSender().sendMessage(msg("console-format", "[NEX AGI] {text}", "text", text));
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendMessage(msg);
            }
        });
    }

    private static String readBody(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int r;
        while ((r = in.read(buf)) > 0) {
            bos.write(buf, 0, r);
            if (bos.size() > 65536) break;
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void reply(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
        ex.close();
    }

    private static int indexOfIgnoreCase(String haystack, String needle) {
        if (needle.isEmpty()) return -1;
        return haystack.toLowerCase().indexOf(needle.toLowerCase());
    }

    // ---------------- Tab 补全 ----------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String name = command.getName().toLowerCase();
        String cur = args.length > 0 ? args[args.length - 1] : "";
        if (name.equals("agi")) {
            if (args.length <= 1) {
                String h = hint();
                if (cur.isEmpty() || h.startsWith(cur)) out.add(h);
            }
            return out;
        }
        if (name.equals("nexagi") && args.length == 1) {
            for (String s : new String[]{"reload", "say", "thinktest"}) {
                if (s.startsWith(cur.toLowerCase())) out.add(s);
            }
            return out;
        }
        return out;
    }

    // ---------------- 命令 ----------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase();
        if (name.equals("agi")) return cmdAgi(sender, args);
        if (!name.equals("nexagi")) return false;
        if (!sender.hasPermission("nexagi.admin")) {
            sender.sendMessage(msg("no-admin", "§c没有权限"));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            loadSettings();
            loadLang();
            startHttp();
            sender.sendMessage(msg("reload-done", "§a已重载：实例={instance} 端口={port} 触发词={trigger}",
                    "instance", instance, "port", listenPort, "trigger", trigger));
            sender.sendMessage(msg("reload-hint", "§a私聊指令 /agi 权限节点 nexagi.ai（默认全部玩家可用）"));
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("thinktest")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c这条只能在游戏里看效果");
                return true;
            }
            final Player p = (Player) sender;
            final StreamState st = new StreamState();
            st.task = Bukkit.getScheduler().runTaskTimer(this, () -> {
                if (!p.isOnline() || st.frame > 60) {
                    if (st.task != null) st.task.cancel();
                    if (p.isOnline()) p.sendActionBar(Component.empty());
                    return;
                }
                p.sendActionBar(buildThinking(st));
                synchronized (st) {
                    st.frame++;
                }
            }, 0L, streamTickPeriod);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("say")) {
            String text = String.join(" ", args).substring(3).trim();
            if (!text.isEmpty()) say(text);
            return true;
        }
        sender.sendMessage(msg("admin-usage", "§e用法: /nexagi reload | /nexagi say <内容>"));
        return true;
    }

    /** /agi <内容> —— 独立会话私聊 AI；回答逐字打在 action bar，打完发到聊天 */
    private boolean cmdAgi(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(msg("player-only", "§c这条指令只能在游戏里用"));
            return true;
        }
        Player p = (Player) sender;
        if (!p.hasPermission("nexagi.ai")) {
            p.sendMessage(msg("no-permission", "§c你没有使用 NEX AGI 的权限"));
            return true;
        }
        rememberRequester(p);
        String text = String.join(" ", args).trim();
        if (text.isEmpty() || text.equals(hint()) || text.equals(AI_HINT)) {
            p.sendMessage(msg("hint-only", "§e在 /agi 后面直接写你想说的话，例如：§f/agi 在吗"));
            return true;
        }
        if (text.length() > 300) text = text.substring(0, 300);
        p.sendMessage(msg("echo-format", "§7[你 -> NEX AGI] §f{text}", "text", text));
        final String t = text;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> startAgi(p, t));
        return true;
    }
}
