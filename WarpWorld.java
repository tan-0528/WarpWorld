package tan0528.warpworld;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

public class WarpWorld extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private List<String> worldList;
    private final List<WarpGate> gates = new ArrayList<>();
    private final Map<UUID, Location[]> selections = new HashMap<>();
    private final Map<UUID, BukkitRunnable> activeWarps = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadManagedWorlds();

        // 【修正】再起動時にマルチワールドのロードを待ってからゲートを構築
        new BukkitRunnable() {
            @Override
            public void run() {
                loadGates();
            }
        }.runTaskLater(this, 60L); // 3秒待機

        getServer().getPluginManager().registerEvents(this, this);

        getCommand("vwarp").setExecutor(this);
        getCommand("vwarpworld").setExecutor(this);
        getCommand("vwarpworld").setTabCompleter(this);

        getLogger().info("WarpWorld v1.4 (All Features Integrated) enabled!");
    }

    private void loadManagedWorlds() {
        worldList = getConfig().getStringList("worlds");
        if (worldList.isEmpty()) {
            worldList = new ArrayList<>(Arrays.asList("world", "world_nether", "world_the_end"));
            getConfig().set("worlds", worldList);
            saveConfig();
        }
        for (String name : worldList) {
            if (Bukkit.getWorld(name) == null) {
                Bukkit.createWorld(new WorldCreator(name));
            }
        }
    }

    private void loadGates() {
        gates.clear();
        ConfigurationSection section = getConfig().getConfigurationSection("gates");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            String wName = section.getString(key + ".world");
            if (wName == null) continue;
            World world = Bukkit.getWorld(wName);
            if (world == null) continue;

            // 座標を数値として1つずつ復元（再起動時のLocation欠落対策）
            Location l1 = new Location(world, section.getDouble(key + ".x1"), section.getDouble(key + ".y1"), section.getDouble(key + ".z1"));
            Location l2 = new Location(world, section.getDouble(key + ".x2"), section.getDouble(key + ".y2"), section.getDouble(key + ".z2"));
            String target = section.getString(key + ".target");

            if (target != null) {
                gates.add(new WarpGate(key, l1, l2, target));
            }
        }
        getLogger().info("[WarpWorld] " + gates.size() + " 個のゲートを読み込みました。");
    }

    private void saveGateToConfig(String id, Location l1, Location l2, String target) {
        String path = "gates." + id;
        getConfig().set(path + ".world", l1.getWorld().getName());
        getConfig().set(path + ".x1", l1.getX());
        getConfig().set(path + ".y1", l1.getY());
        getConfig().set(path + ".z1", l1.getZ());
        getConfig().set(path + ".x2", l2.getX());
        getConfig().set(path + ".y2", l2.getY());
        getConfig().set(path + ".z2", l2.getZ());
        getConfig().set(path + ".target", target);
        saveConfig();
    }

    @EventHandler
    public void onWandInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!player.isOp() || player.getInventory().getItemInMainHand().getType() != Material.WOODEN_SWORD) return;

        Block block = event.getClickedBlock();
        if (block == null) return;

        event.setCancelled(true);
        Location[] sel = selections.computeIfAbsent(player.getUniqueId(), k -> new Location[2]);

        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            sel[0] = block.getLocation();
            player.sendMessage("§a[WarpWorld] §7地点1 (Pos1) を設定しました。");
        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            sel[1] = block.getLocation();
            player.sendMessage("§e[WarpWorld] §7地点2 (Pos2) を設定しました。");
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return true;

        if (label.equalsIgnoreCase("vwarp")) {
            if (args.length == 0) {
                player.sendMessage("§b[WarpWorld] §f移動可能なワールド一覧:");
                for (String s : worldList) player.sendMessage("§7 - §e" + s);
                return true;
            }
            startWarpProcess(player, args[0]);
            return true;
        }

        if (label.equalsIgnoreCase("vwarpworld")) {
            if (!player.isOp()) {
                player.sendMessage("§c権限がありません。");
                return true;
            }

            if (args.length < 1) {
                sendDetailedHelp(player);
                return true;
            }

            switch (args[0].toLowerCase()) {
                case "add" -> {
                    if (args.length < 2) {
                        player.sendMessage("§c使い方: /vwarpworld add <ワールド名>");
                        return true;
                    }
                    if (!worldList.contains(args[1])) {
                        worldList.add(args[1]);
                        getConfig().set("worlds", worldList);
                        saveConfig();
                        Bukkit.createWorld(new WorldCreator(args[1]));
                        player.sendMessage("§aワールド '" + args[1] + "' を追加し、ロードしました。");
                    } else {
                        player.sendMessage("§eそのワールドは既にリストに存在します。");
                    }
                }
                case "creategate" -> {
                    if (args.length < 2) {
                        player.sendMessage("§c使い方: /vwarpworld creategate <移動先ワールド名>");
                        return true;
                    }
                    Location[] sel = selections.get(player.getUniqueId());
                    if (sel == null || sel[0] == null || sel[1] == null) {
                        player.sendMessage("§c木の剣で範囲を選択してから実行してください。");
                        return true;
                    }
                    String id = "gate_" + System.currentTimeMillis();
                    saveGateToConfig(id, sel[0], sel[1], args[1]);
                    loadGates();
                    player.sendMessage("§6§l[Gate] §fゲートを作成しました！ ターゲット: " + args[1]);
                }
                case "removegate" -> {
                    WarpGate toRemove = null;
                    for (WarpGate gate : gates) {
                        if (gate.isInside(player.getLocation())) { toRemove = gate; break; }
                    }
                    if (toRemove != null) {
                        getConfig().set("gates." + toRemove.id, null);
                        saveConfig();
                        loadGates();
                        player.sendMessage("§c現在地のゲートを削除しました。");
                    } else {
                        player.sendMessage("§7削除したいゲートの中に立ってください。");
                    }
                }
                case "reload" -> {
                    reloadConfig();
                    loadManagedWorlds();
                    loadGates();
                    player.sendMessage("§a設定ファイルをリロードしました。");
                }
                default -> sendDetailedHelp(player);
            }
        }
        return true;
    }

    private void sendDetailedHelp(Player p) {
        p.sendMessage(" ");
        p.sendMessage("§b§l§m   §r §b§l[ WarpWorld 管理マニュアル ] §b§l§m   ");
        p.sendMessage("§e§l1. ワールド管理");
        p.sendMessage(" §b/vwarpworld add <名> §f: 既存フォルダを読み込み対象に追加");
        p.sendMessage(" §b/vwarpworld reload §f: configの再読み込み");
        p.sendMessage(" ");
        p.sendMessage("§e§l2. ゲート作成 (木の剣を使用)");
        p.sendMessage(" §f① 範囲の角を§a左クリック§f、反対の角を§e右クリック§f。");
        p.sendMessage(" §f② §b/vwarpworld creategate <移動先> §fを実行。");
        p.sendMessage(" ");
        p.sendMessage("§e§l3. ゲート削除");
        p.sendMessage(" §f① 消したいゲートの§6範囲内に立ちます§f。");
        p.sendMessage(" §f② §b/vwarpworld removegate §fを実行。");
        p.sendMessage(" ");
        p.sendMessage("§d§l※ §fカウントダウン中はパーティクルが表示され、動くと中断されます。");
        p.sendMessage("§b§l§m                             ");
    }

    private void startWarpProcess(Player player, String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            player.sendMessage("§cワールド '" + worldName + "' が見つかりません。");
            return;
        }
        cancelWarp(player, false);

        BukkitRunnable task = new BukkitRunnable() {
            int count = 3;
            int ticks = 0;
            @Override
            public void run() {
                // 【追加】周囲からも見えるパーティクル演出
                if (ticks % 2 == 0) {
                    player.getWorld().spawnParticle(Particle.PORTAL, player.getLocation().add(0, 1, 0), 15, 0.4, 0.7, 0.4, 0.1);
                    player.getWorld().spawnParticle(Particle.ENCHANTMENT_TABLE, player.getLocation().add(0, 1, 0), 8, 0.4, 0.7, 0.4, 0.1);
                }

                if (ticks % 20 == 0) {
                    if (count > 0) {
                        player.sendTitle("§e" + count, "§f移動まで", 0, 21, 0);
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1, 1);
                        count--;
                    } else {
                        player.teleport(world.getSpawnLocation());
                        player.sendTitle("§aTeleported!", "§7" + worldName, 10, 20, 10);
                        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1, 1);
                        activeWarps.remove(player.getUniqueId());
                        this.cancel();
                    }
                }
                ticks++;
            }
        };
        task.runTaskTimer(this, 0L, 1L);
        activeWarps.put(player.getUniqueId(), task);
    }

    private void cancelWarp(Player player, boolean notify) {
        if (activeWarps.containsKey(player.getUniqueId())) {
            activeWarps.get(player.getUniqueId()).cancel();
            activeWarps.remove(player.getUniqueId());
            if (notify) player.sendTitle("§cCancelled", "§7移動を検知しました", 5, 10, 5);
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        if (event.getFrom().getBlockX() == event.getTo().getBlockX() &&
                event.getFrom().getBlockY() == event.getTo().getBlockY() &&
                event.getFrom().getBlockZ() == event.getTo().getBlockZ()) return;

        Player player = event.getPlayer();
        for (WarpGate gate : gates) {
            if (gate.isInside(event.getTo())) {
                if (!activeWarps.containsKey(player.getUniqueId())) {
                    startWarpProcess(player, gate.targetWorld);
                }
                return;
            }
        }
        cancelWarp(player, true);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command c, @NotNull String a, @NotNull String[] args) {
        String lowerA = a.toLowerCase();
        if (lowerA.equals("vwarp")) {
            if (args.length == 1) return worldList.stream().filter(w -> w.toLowerCase().startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        }
        if (lowerA.equals("vwarpworld") && s.isOp()) {
            if (args.length == 1) {
                return Arrays.asList("add", "creategate", "removegate", "reload").stream()
                        .filter(sub -> sub.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("creategate")) {
                return worldList.stream().filter(w -> w.toLowerCase().startsWith(args[1].toLowerCase())).collect(Collectors.toList());
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("add")) {
                return Collections.emptyList();
            }
        }
        return Collections.emptyList();
    }

    private static class WarpGate {
        String id;
        Location l1, l2;
        String targetWorld;
        WarpGate(String id, Location loc1, Location loc2, String target) {
            this.id = id; this.l1 = loc1; this.l2 = loc2; this.targetWorld = target;
        }
        boolean isInside(Location loc) {
            if (l1 == null || l1.getWorld() == null || loc.getWorld() == null) return false;
            if (!loc.getWorld().getName().equals(l1.getWorld().getName())) return false;

            double x = loc.getX(), y = loc.getY(), z = loc.getZ();
            return x >= Math.min(l1.getX(), l2.getX()) && x <= Math.max(l1.getX(), l2.getX()) + 1.0 &&
                    y >= Math.min(l1.getY(), l2.getY()) && y <= Math.max(l1.getY(), l2.getY()) + 2.0 &&
                    z >= Math.min(l1.getZ(), l2.getZ()) && z <= Math.max(l1.getZ(), l2.getZ()) + 1.0;
        }
    }
}