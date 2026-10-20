package net.intertime.bar;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;
import java.util.logging.Level;

public class BarManager implements Listener {

    private static final String[] KEYS = {"sidebar", "bossbar", "tab"};
    private static final int MAX_LINES = 15;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    // ------------------------------------------------------------ типи

    static final class Rank {
        final String id, perm, prefix, color;

        Rank(String id, String perm, String prefix, String color) {
            this.id = id;
            this.perm = perm;
            this.prefix = prefix;
            this.color = color;
        }
    }

    static final class Msg {
        final String text;
        final BarColor color;
        final BarStyle style;

        Msg(String text, BarColor color, BarStyle style) {
            this.text = text;
            this.color = color;
            this.style = style;
        }
    }

    static final class BarState {
        final BossBar bar;
        String title;
        BarColor color;
        BarStyle style;
        double prog = -1;

        BarState(BossBar bar) {
            this.bar = bar;
        }
    }

    static final class Board {
        final Scoreboard sb;
        final Objective obj;
        final String[] cache = new String[MAX_LINES];
        int shown = -1;
        String title;

        Board(Scoreboard sb, Objective obj) {
            this.sb = sb;
            this.obj = obj;
        }
    }

    static final class Gui implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    // ------------------------------------------------------------ поля

    private final InterBar pl;
    private final Map<UUID, Board> boards = new HashMap<>();
    private final Map<UUID, BarState> bars = new HashMap<>();
    private final Map<UUID, String> tabCache = new HashMap<>();
    private final Map<UUID, Integer> rankCache = new HashMap<>();
    private final Map<String, Set<UUID>> off = new HashMap<>();

    private final File dataFile;
    private BukkitTask task;
    private long ticks;
    private boolean errorLogged;
    private Method papi;

    private Method nfSetter;
    private Object nfBlank;
    private boolean nfTried;

    // конфіг
    private int updateTicks;
    private boolean sidebarOn, bossOn, tabOn, sortRank;
    private String titleText;
    private int titleFrom, titleTo, titleMax = 128;
    private boolean titleBold, titleAnim;
    private double titleStep;
    private int pageSeconds;
    private List<List<String>> pages;
    private List<String> header, footer;
    private List<Msg> msgs;
    private int interval;
    private List<Rank> ranks;

    public BarManager(InterBar pl) {
        this.pl = pl;
        this.dataFile = new File(pl.getDataFolder(), "data.yml");
        for (String k : KEYS) off.put(k, new HashSet<UUID>());
        loadData();
        load();
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI")
                        .getMethod("setPlaceholders", Player.class, String.class);
            } catch (Throwable ignored) {
            }
        }
    }

    // ================================================================ конфіг / дані

    private void load() {
        FileConfiguration c = pl.getConfig();
        updateTicks = Math.max(1, c.getInt("update-ticks", 5));
        sidebarOn = c.getBoolean("sidebar.enabled", true);
        bossOn = c.getBoolean("bossbar.enabled", true);
        tabOn = c.getBoolean("tab.enabled", true);
        sortRank = c.getBoolean("tab.sort-by-rank", true);

        titleText = c.getString("sidebar.title", "INTERTIME");
        titleFrom = Text.parseColor(c.getString("sidebar.title-from", "#00e5ff"), 0x00e5ff);
        titleTo = Text.parseColor(c.getString("sidebar.title-to", "#7c4dff"), 0x7c4dff);
        titleBold = c.getBoolean("sidebar.title-bold", true);
        titleAnim = c.getBoolean("sidebar.title-animation", true);
        titleStep = c.getDouble("sidebar.title-step", 0.08);
        pageSeconds = Math.max(1, c.getInt("sidebar.page-seconds", 8));
        titleMax = 128;

        pages = new ArrayList<>();
        ConfigurationSection ps = c.getConfigurationSection("sidebar.pages");
        if (ps != null) {
            for (String k : ps.getKeys(false)) {
                List<String> l = new ArrayList<>(ps.getStringList(k));
                if (l.size() > MAX_LINES) l = new ArrayList<>(l.subList(0, MAX_LINES));
                if (!l.isEmpty()) pages.add(l);
            }
        }
        if (pages.isEmpty()) pages.add(new ArrayList<>(Arrays.asList("&7%player%")));

        header = c.getStringList("tab.header");
        footer = c.getStringList("tab.footer");
        interval = Math.max(1, c.getInt("bossbar.interval", 10));

        msgs = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("bossbar.messages")) {
            msgs.add(new Msg(String.valueOf(m.get("text")),
                    barColor(String.valueOf(m.get("color"))),
                    barStyle(String.valueOf(m.get("style")))));
        }

        ranks = new ArrayList<>();
        for (Map<?, ?> m : c.getMapList("ranks")) {
            ranks.add(new Rank(String.valueOf(m.get("id")),
                    m.get("permission") == null ? "" : String.valueOf(m.get("permission")),
                    m.get("prefix") == null ? "" : String.valueOf(m.get("prefix")),
                    m.get("color") == null ? "white" : String.valueOf(m.get("color"))));
        }
        if (ranks.isEmpty()) ranks.add(new Rank("player", "", "&7", "gray"));
    }

    private static BarColor barColor(String s) {
        try {
            return BarColor.valueOf(s.toUpperCase());
        } catch (Exception e) {
            return BarColor.BLUE;
        }
    }

    private static BarStyle barStyle(String s) {
        String u = s.toUpperCase();
        if (u.equals("PROGRESS")) return BarStyle.SOLID;
        if (u.startsWith("NOTCHED_")) u = "SEGMENTED_" + u.substring(8);
        try {
            return BarStyle.valueOf(u);
        } catch (Exception e) {
            return BarStyle.SOLID;
        }
    }

    private void loadData() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        for (String k : KEYS) {
            for (String s : y.getStringList(k + "-off")) {
                try {
                    off.get(k).add(UUID.fromString(s));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        for (String k : KEYS) {
            List<String> l = new ArrayList<>();
            for (UUID u : off.get(k)) l.add(u.toString());
            y.set(k + "-off", l);
        }
        try {
            pl.getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            pl.getLogger().warning("Не вдалось зберегти data.yml: " + ex.getMessage());
        }
    }

    // ================================================================ запуск / зупинка

    public void start() {
        if (task != null) task.cancel();
        task = Bukkit.getScheduler().runTaskTimer(pl, new Runnable() {
            @Override
            public void run() {
                tick();
            }
        }, 20L, updateTicks);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        clearAll();
    }

    public void reload() {
        clearAll();
        load();
        start();
    }

    private void clearAll() {
        for (BarState s : bars.values()) s.bar.removeAll();
        for (Player p : Bukkit.getOnlinePlayers()) {
            headerFooter(p, "", "");
            try {
                p.setPlayerListName(null);
            } catch (Throwable ignored) {
            }
            if (boards.containsKey(p.getUniqueId())) {
                try {
                    p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
                } catch (Throwable ignored) {
                }
            }
        }
        boards.clear();
        bars.clear();
        tabCache.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        boards.remove(id);
        BarState s = bars.remove(id);
        if (s != null) s.bar.removeAll();
        tabCache.remove(id);
        for (Board b : boards.values()) {
            Team t = b.sb.getEntryTeam(p.getName());
            if (t != null) t.removeEntry(p.getName());
        }
    }

    // ================================================================ головний цикл

    private void tick() {
        ticks++;
        rankCache.clear();
        for (Player p : Bukkit.getOnlinePlayers()) rankCache.put(p.getUniqueId(), rankOf(p));
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                sidebar(p);
                bossbar(p);
                tab(p);
                teams(p);
            } catch (Throwable ex) {
                if (!errorLogged) {
                    errorLogged = true;
                    pl.getLogger().log(Level.WARNING, "Помилка оновлення для " + p.getName(), ex);
                }
            }
        }
    }

    private int rankOf(Player p) {
        for (int i = 0; i < ranks.size(); i++) {
            Rank r = ranks.get(i);
            if (r.perm.isEmpty() || p.hasPermission(r.perm)) return i;
        }
        return ranks.size() - 1;
    }

    // ================================================================ плейсхолдери

    private static String rep(String s, String key, Supplier<String> v) {
        return s.contains(key) ? s.replace(key, v.get()) : s;
    }

    private static int stat(Player p, String name) {
        try {
            return p.getStatistic(Statistic.valueOf(name));
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int ping(Player p) {
        try {
            return p.getPing();
        } catch (Throwable ignored) {
        }
        try {
            Object h = p.getClass().getMethod("getHandle").invoke(p);
            return h.getClass().getField("ping").getInt(h);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static double tps() {
        try {
            return Math.min(20.0, Bukkit.getTPS()[0]);
        } catch (Throwable t) {
            return 20.0;
        }
    }

    private String ph(final Player p, String s) {
        if (s.indexOf('%') < 0) return s;
        s = rep(s, "%player%", () -> p.getName());
        s = rep(s, "%online%", () -> String.valueOf(Bukkit.getOnlinePlayers().size()));
        s = rep(s, "%max%", () -> String.valueOf(Bukkit.getMaxPlayers()));
        s = rep(s, "%ping%", () -> String.valueOf(ping(p)));
        s = rep(s, "%world%", () -> p.getWorld().getName());
        s = rep(s, "%time%", () -> LocalTime.now().format(TIME));
        s = rep(s, "%date%", () -> LocalDate.now().format(DATE));
        s = rep(s, "%tps%", () -> String.format(Locale.US, "%.1f", tps()));
        s = rep(s, "%x%", () -> String.valueOf(p.getLocation().getBlockX()));
        s = rep(s, "%y%", () -> String.valueOf(p.getLocation().getBlockY()));
        s = rep(s, "%z%", () -> String.valueOf(p.getLocation().getBlockZ()));
        s = rep(s, "%rank%", () -> ranks.get(rankCache.getOrDefault(p.getUniqueId(), 0)).id.toUpperCase());
        s = rep(s, "%kills%", () -> String.valueOf(stat(p, "PLAYER_KILLS")));
        s = rep(s, "%mobkills%", () -> String.valueOf(stat(p, "MOB_KILLS")));
        s = rep(s, "%deaths%", () -> String.valueOf(stat(p, "DEATHS")));
        s = rep(s, "%kdr%", () -> String.format(Locale.US, "%.2f",
                stat(p, "PLAYER_KILLS") / (double) Math.max(1, stat(p, "DEATHS"))));
        s = rep(s, "%jumps%", () -> String.valueOf(stat(p, "JUMP")));
        s = rep(s, "%playtime%", () -> {
            long mins = stat(p, "PLAY_ONE_MINUTE") / 1200L;
            return (mins / 60) + "г " + (mins % 60) + "хв";
        });
        s = rep(s, "%health%", () -> String.valueOf((int) Math.ceil(p.getHealth())));
        if (papi != null) {
            try {
                s = (String) papi.invoke(null, p, s);
            } catch (Throwable ignored) {
            }
        }
        return s;
    }

    // ================================================================ скорборд справа

    /** Невидимий унікальний "гравець" для кожного рядка: §0§r, §1§r ... */
    private static String entry(int i) {
        return "" + Text.S + Text.CODES.charAt(i) + Text.S + "r";
    }

    private static String lineTeam(int i) {
        return String.format("ibl%02d", i);
    }

    private static String rankTeam(int i) {
        return String.format("ibr%02d", i);
    }

    private Objective newObjective(Scoreboard sb) throws Exception {
        try {
            return sb.registerNewObjective("ib", "dummy", "InterTime");
        } catch (Throwable ignored) {
        }
        Class<?> cr = Class.forName("org.bukkit.scoreboard.Criteria");
        Object dummy = cr.getField("DUMMY").get(null);
        Method m = Scoreboard.class.getMethod("registerNewObjective", String.class, cr, String.class);
        return (Objective) m.invoke(sb, "ib", dummy, "InterTime");
    }

    /** Ховає червоні цифри справа (клієнти 1.20.3+ на Paper). Старі клієнти їх показують завжди. */
    private void hideNumbers(Objective o) {
        try {
            if (!nfTried) {
                nfTried = true;
                Class<?> nf = Class.forName("io.papermc.paper.scoreboard.numbers.NumberFormat");
                nfBlank = nf.getMethod("blank").invoke(null);
                nfSetter = Objective.class.getMethod("numberFormat", nf);
            }
            if (nfSetter != null) nfSetter.invoke(o, nfBlank);
        } catch (Throwable ignored) {
        }
    }

    private Board board(Player p) throws Exception {
        Board b = boards.get(p.getUniqueId());
        if (b == null) {
            Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective o = newObjective(sb);
            hideNumbers(o);
            for (int i = 0; i < MAX_LINES; i++) {
                Team t = sb.registerNewTeam(lineTeam(i));
                t.addEntry(entry(i));
            }
            for (int i = 0; i < ranks.size(); i++) {
                Rank r = ranks.get(i);
                Team t = sb.registerNewTeam(rankTeam(i));
                t.setPrefix(Text.split(Text.color(r.prefix), 64)[0]);
                try {
                    t.setColor(ChatColor.valueOf(r.color.toUpperCase()));
                } catch (Throwable ignored) {
                }
            }
            b = new Board(sb, o);
            boards.put(p.getUniqueId(), b);
        }
        if (p.getScoreboard() != b.sb) p.setScoreboard(b.sb);
        return b;
    }

    private void setTitle(Board b) {
        double phase = titleAnim ? (ticks * titleStep) % 2.0 : 0.0;
        String s = Text.gradient(titleText, titleFrom, titleTo, phase, titleBold, titleMax);
        if (s.equals(b.title)) return;
        try {
            b.obj.setDisplayName(s);
            b.title = s;
        } catch (IllegalArgumentException ex) {
            // сервер має ліміт на довжину заголовка - наступного разу робимо коротший
            titleMax = Math.max(20, titleMax - 30);
        }
    }

    private void sidebar(Player p) throws Exception {
        Board b = board(p);
        if (!sidebarOn || !isOn(p, "sidebar")) {
            if (b.obj.getDisplaySlot() != null) b.sb.clearSlot(DisplaySlot.SIDEBAR);
            return;
        }
        if (b.obj.getDisplaySlot() != DisplaySlot.SIDEBAR) b.obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        setTitle(b);

        int page = pages.size() == 1 ? 0 : (int) (((ticks * updateTicks / 20L) / pageSeconds) % pages.size());
        List<String> src = pages.get(page);
        int n = src.size();

        if (b.shown != n) {
            for (int i = 0; i < MAX_LINES; i++) {
                if (i >= n) b.sb.resetScores(entry(i));
                else b.obj.getScore(entry(i)).setScore(n - i);
            }
            Arrays.fill(b.cache, null);
            b.shown = n;
        }
        for (int i = 0; i < n; i++) {
            String s = Text.color(ph(p, src.get(i)));
            if (s.equals(b.cache[i])) continue;
            b.cache[i] = s;
            String[] parts = Text.split(s, 64);
            Team t = b.sb.getTeam(lineTeam(i));
            if (t == null) continue;
            t.setPrefix(parts[0]);
            t.setSuffix(parts[1]);
        }
    }

    // ================================================================ боссбар

    private void bossbar(Player p) {
        UUID id = p.getUniqueId();
        BarState st = bars.get(id);
        if (!bossOn || !isOn(p, "bossbar") || msgs.isEmpty()) {
            if (st != null) {
                st.bar.removeAll();
                bars.remove(id);
            }
            return;
        }
        double elapsed = ticks * updateTicks / 20.0;
        int idx = (int) (elapsed / interval) % msgs.size();
        double prog = Math.max(0.0, Math.min(1.0, 1.0 - (elapsed % interval) / interval));
        Msg m = msgs.get(idx);
        if (st == null) {
            BossBar bar = Bukkit.createBossBar("", BarColor.WHITE, BarStyle.SOLID);
            bar.addPlayer(p);
            st = new BarState(bar);
            bars.put(id, st);
        }
        String title = Text.color(ph(p, m.text));
        if (!title.equals(st.title)) {
            st.title = title;
            st.bar.setTitle(title);
        }
        if (st.color != m.color) {
            st.color = m.color;
            st.bar.setColor(m.color);
        }
        if (st.style != m.style) {
            st.style = m.style;
            st.bar.setStyle(m.style);
        }
        if (Math.abs(prog - st.prog) > 0.004) {
            st.prog = prog;
            st.bar.setProgress(prog);
        }
    }

    // ================================================================ TAB

    private static void headerFooter(Player p, String h, String f) {
        try {
            p.setPlayerListHeaderFooter(h, f);
        } catch (Throwable ignored) {
        }
    }

    private static String colorCode(String name) {
        try {
            return ChatColor.valueOf(name.toUpperCase()).toString();
        } catch (Throwable t) {
            return Text.S + "f";
        }
    }

    private void tab(Player p) {
        UUID id = p.getUniqueId();
        if (!tabOn || !isOn(p, "tab")) {
            if (!"off".equals(tabCache.get(id))) {
                tabCache.put(id, "off");
                headerFooter(p, "", "");
                try {
                    p.setPlayerListName(null);
                } catch (Throwable ignored) {
                }
            }
            return;
        }
        Rank r = ranks.get(rankCache.getOrDefault(id, 0));
        String h = Text.color(ph(p, String.join("\n", header)));
        String f = Text.color(ph(p, String.join("\n", footer)));
        String key = r.id + "\u0000" + h + "\u0000" + f;
        if (key.equals(tabCache.get(id))) return;
        tabCache.put(id, key);

        // Якщо працюють команди (sort-by-rank), префікс у TAB вже додає команда - не дублюємо.
        try {
            if (sortRank) p.setPlayerListName(null);
            else p.setPlayerListName(Text.color(r.prefix) + colorCode(r.color) + p.getName());
        } catch (Throwable ignored) {
        }
        headerFooter(p, h, f);
    }

    /** Команди (teams) на скорборді глядача: сортування в TAB і префікс над головою. */
    private void teams(Player viewer) {
        if (!tabOn || !sortRank || !isOn(viewer, "tab")) return;
        Board b = boards.get(viewer.getUniqueId());
        if (b == null) return;
        for (Player q : Bukkit.getOnlinePlayers()) {
            int idx = rankCache.getOrDefault(q.getUniqueId(), ranks.size() - 1);
            Team t = b.sb.getTeam(rankTeam(idx));
            if (t == null || t.hasEntry(q.getName())) continue;
            Team old = b.sb.getEntryTeam(q.getName());
            if (old != null) old.removeEntry(q.getName());
            t.addEntry(q.getName());
        }
    }

    // ================================================================ перемикачі та меню

    public boolean isOn(Player p, String key) {
        return !off.get(key).contains(p.getUniqueId());
    }

    public void toggle(Player p, String key) {
        Set<UUID> set = off.get(key);
        if (!set.remove(p.getUniqueId())) set.add(p.getUniqueId());
        saveData();
    }

    static void msg(Player p, String s) {
        p.sendMessage(Text.prefix() + Text.color(s));
    }

    private static Material mat(String name) {
        Material m = Material.matchMaterial(name);
        return m != null ? m : Material.PAPER;
    }

    private static ItemStack item(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(Text.color(name));
            if (lore.length > 0) {
                List<String> l = new ArrayList<>();
                for (String s : lore) l.add(Text.color(s));
                im.setLore(l);
            }
            it.setItemMeta(im);
        }
        return it;
    }

    public void openMenu(Player p) {
        Gui g = new Gui();
        g.inv = Bukkit.createInventory(g, 27, Text.color("&b&lINTERTIME &8\u2022 &fПанелі"));
        draw(g.inv, p);
        p.openInventory(g.inv);
    }

    private void draw(Inventory inv, Player p) {
        ItemStack edge = item(mat("CYAN_STAINED_GLASS_PANE"), " ");
        ItemStack dark = item(mat("BLACK_STAINED_GLASS_PANE"), " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, (i < 9 || i >= 18) ? edge : dark);
        String[] names = {"Скорборд справа", "Боссбар зверху", "TAB (список гравців)"};
        Material[] icons = {mat("OAK_SIGN"), mat("DRAGON_HEAD"), mat("NAME_TAG")};
        int[] slots = {11, 13, 15};
        for (int i = 0; i < 3; i++) {
            boolean on = isOn(p, KEYS[i]);
            inv.setItem(slots[i], item(icons[i],
                    (on ? "&a\u2714 " : "&c\u2718 ") + names[i],
                    "&7Стан: " + (on ? "&aувімкнено" : "&cвимкнено"),
                    "&eНатисни щоб " + (on ? "вимкнути" : "увімкнути")));
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getInventory();
        if (!(top.getHolder() instanceof Gui)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        if (e.getClickedInventory() != top) return;
        int s = e.getSlot();
        int idx = s == 11 ? 0 : s == 13 ? 1 : s == 15 ? 2 : -1;
        if (idx < 0) return;
        toggle(p, KEYS[idx]);
        draw(top, p);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Gui) e.setCancelled(true);
    }
}
