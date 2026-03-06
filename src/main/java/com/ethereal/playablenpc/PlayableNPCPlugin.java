package com.ethereal.playablenpc;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class PlayableNPCPlugin extends JavaPlugin implements Listener {

    // Соответствие: игрок -> NPC, в которого он вселился
    private final Map<UUID, UUID> possessingMap = new HashMap<>();
    // NPC -> игрок
    private final Map<UUID, UUID> possessedByMap = new HashMap<>();
    // Хранилище скинов для NPC (URL -> текстура, но пока просто сохраняем URL)
    private final Map<UUID, String> npcSkinUrls = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("PlayableNPCPlugin включён!");

        // Запускаем таймер синхронизации позиций вселённых игроков и NPC
        new BukkitRunnable() {
            @Override
            public void run() {
                syncPossessed();
            }
        }.runTaskTimer(this, 0L, 1L); // каждый тик
    }

    @Override
    public void onDisable() {
        // При выключении выселяем всех игроков
        for (UUID npcId : possessedByMap.keySet()) {
            Entity npc = Bukkit.getEntity(npcId);
            if (npc != null) npc.remove();
        }
        possessingMap.clear();
        possessedByMap.clear();
        npcSkinUrls.clear();
        getLogger().info("PlayableNPCPlugin выключён!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cТолько игрок может использовать эту команду!");
            return true;
        }
        Player player = (Player) sender;

        switch (command.getName().toLowerCase()) {
            case "createnpc":
                if (args.length < 1) {
                    player.sendMessage("§cИспользование: /createnpc <имя> [url скина]");
                    return true;
                }
                String name = args[0];
                String skinUrl = (args.length >= 2) ? args[1] : null;
                return createNPC(player, name, skinUrl);

            case "playnpc":
                if (args.length < 1) {
                    player.sendMessage("§cИспользование: /playnpc <имя NPC>");
                    return true;
                }
                return playAsNPC(player, args[0]);

            case "stopnpc":
                return stopPlaying(player);

            case "setskin":
                if (args.length < 2) {
                    player.sendMessage("§cИспользование: /setskin <имя NPC> <url скина>");
                    return true;
                }
                return setNPCSkin(player, args[0], args[1]);

            case "removeskin":
                if (args.length < 1) {
                    player.sendMessage("§cИспользование: /removeskin <имя NPC>");
                    return true;
                }
                return removeNPCSkin(player, args[0]);

            case "vanish":
                return vanishPlayer(player, true);

            case "unvanish":
                return vanishPlayer(player, false);

            default:
                return false;
        }
    }

    private boolean createNPC(Player player, String name, String skinUrl) {
        Location loc = player.getLocation();
        ArmorStand npc = player.getWorld().spawn(loc, ArmorStand.class);
        npc.setVisible(true);
        npc.setCustomName(name);
        npc.setCustomNameVisible(true);
        npc.setGravity(true);
        npc.setCanPickupItems(false);
        npc.setMarker(false);
        npc.setSmall(false);
        npc.setBasePlate(false);
        npc.setArms(true);

        // Если указан URL скина, попытаемся применить
        if (skinUrl != null && !skinUrl.isEmpty()) {
            setNPCSkin(npc.getUniqueId(), skinUrl);
            npcSkinUrls.put(npc.getUniqueId(), skinUrl);
        } else {
            // Для дефолтного скина выдадим голову скином игрока (позже можно заменить)
            setDefaultPlayerSkin(npc, player);
        }

        player.sendMessage("§aNPC '" + name + "' создан! ID: " + npc.getUniqueId());
        return true;
    }

    private void setDefaultPlayerSkin(ArmorStand npc, Player owner) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(owner);
        head.setItemMeta(meta);
        npc.getEquipment().setHelmet(head);
    }

    private boolean playAsNPC(Player player, String npcName) {
        // Проверяем, не вселён ли уже игрок
        if (possessingMap.containsKey(player.getUniqueId())) {
            player.sendMessage("§cТы уже вселён в NPC! Используй /stopnpc");
            return true;
        }

        // Ищем NPC по имени
        Entity targetNPC = findNPCByName(npcName, player.getWorld());
        if (targetNPC == null) {
            player.sendMessage("§cNPC с именем '" + npcName + "' не найден!");
            return true;
        }

        // Сохраняем позицию игрока
        player.setMetadata("playablenpc_lastloc", new org.bukkit.metadata.FixedMetadataValue(this, player.getLocation()));

        // Прячем игрока от всех (встроенный ваниш)
        vanishPlayer(player, true);

        // Телепортируем игрока к NPC (для синхронизации)
        player.teleport(targetNPC.getLocation());

        // Запоминаем связку
        possessingMap.put(player.getUniqueId(), targetNPC.getUniqueId());
        possessedByMap.put(targetNPC.getUniqueId(), player.getUniqueId());

        player.sendMessage("§aТы вселился в NPC '" + npcName + "'. Управляй им как обычным игроком. Для выхода используй /stopnpc.");
        return true;
    }

    private boolean stopPlaying(Player player) {
        if (!possessingMap.containsKey(player.getUniqueId())) {
            player.sendMessage("§cТы не вселён ни в одного NPC!");
            return true;
        }

        UUID npcId = possessingMap.get(player.getUniqueId());
        Entity npc = Bukkit.getEntity(npcId);

        // Делаем игрока видимым
        vanishPlayer(player, false);

        // Возвращаем на сохранённую позицию
        if (player.hasMetadata("playablenpc_lastloc")) {
            Location lastLoc = (Location) player.getMetadata("playablenpc_lastloc").get(0).value();
            if (lastLoc != null) {
                player.teleport(lastLoc);
            }
        }

        // Убираем метки
        player.removeMetadata("playablenpc_lastloc", this);

        // Удаляем из мап
        possessingMap.remove(player.getUniqueId());
        possessedByMap.remove(npcId);

        player.sendMessage("§aТы вышел из NPC!");
        return true;
    }

    private boolean setNPCSkin(Player player, String npcName, String url) {
        Entity npc = findNPCByName(npcName, player.getWorld());
        if (npc == null) {
            player.sendMessage("§cNPC не найден!");
            return true;
        }
        setNPCSkin(npc.getUniqueId(), url);
        npcSkinUrls.put(npc.getUniqueId(), url);
        player.sendMessage("§aСкин для NPC '" + npcName + "' установлен по URL.");
        return true;
    }

    private void setNPCSkin(UUID npcId, String url) {
        // Здесь должна быть реальная загрузка скина по URL.
        // В упрощённом варианте просто сохраняем URL в мапу.
        // Для полноценной работы нужно использовать библиотеку для Minecraft текстур.
        // Пока ограничимся уведомлением.
        getLogger().info("Запрос на установку скина для NPC " + npcId + " по URL: " + url);
    }

    private boolean removeNPCSkin(Player player, String npcName) {
        Entity npc = findNPCByName(npcName, player.getWorld());
        if (npc == null) {
            player.sendMessage("§cNPC не найден!");
            return true;
        }
        npcSkinUrls.remove(npc.getUniqueId());
        // Сбрасываем скин на дефолтный (голова игрока-владельца)
        if (npc instanceof ArmorStand) {
            setDefaultPlayerSkin((ArmorStand) npc, player);
        }
        player.sendMessage("§aСкин для NPC '" + npcName + "' убран.");
        return true;
    }

    private boolean vanishPlayer(Player player, boolean vanish) {
        if (vanish) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.equals(player)) {
                    online.hidePlayer(this, player);
                }
            }
            player.sendMessage("§7Ты стал невидимым.");
        } else {
            for (Player online : Bukkit.getOnlinePlayers()) {
                online.showPlayer(this, player);
            }
            player.sendMessage("§7Ты стал видимым.");
        }
        return true;
    }

    private void syncPossessed() {
        for (Map.Entry<UUID, UUID> entry : possessingMap.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            Entity npc = Bukkit.getEntity(entry.getValue());

            if (player == null || npc == null || !npc.isValid()) {
                // Если что-то не так, очищаем
                if (player != null) {
                    player.setInvisible(false);
                    possessingMap.remove(player.getUniqueId());
                }
                if (npc != null) {
                    possessedByMap.remove(npc.getUniqueId());
                }
                continue;
            }

            // Синхронизируем позицию NPC с игроком
            npc.teleport(player.getLocation());

            // Вращение головы
            if (npc instanceof ArmorStand) {
                ArmorStand stand = (ArmorStand) npc;
                // Поворачиваем голову в соответствии с взглядом игрока
                stand.setHeadPose(stand.getHeadPose().setX(Math.toRadians(player.getLocation().getPitch())));
            }
        }
    }

    private Entity findNPCByName(String name, org.bukkit.World world) {
        for (Entity e : world.getEntities()) {
            if (e instanceof ArmorStand && e.getCustomName() != null && e.getCustomName().equalsIgnoreCase(name)) {
                return e;
            }
        }
        return null;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        stopPlaying(event.getPlayer());
    }
}
