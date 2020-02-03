package com.github.arboriginal.TamedTPFixer;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sittable;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public class Plugin extends JavaPlugin implements Listener {
    private final BlockFace[] cards = { BlockFace.EAST, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST };

    private HashSet<Material>   forbidden;
    private HashSet<EntityType> types;

    private HashMap<UUID, BukkitRunnable> recents = new HashMap<UUID, BukkitRunnable>();

    // JavaPLugin methods ----------------------------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("ttpfix-reload")) return super.onCommand(sender, command, label, args);
        reloadConfig();
        sender.sendMessage("§8[§6TamedTPFixer§8] §aConfiguration reloaded.");
        return true;
    }

    @Override
    public void onEnable() {
        super.onEnable();
        reloadConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    public void reloadConfig() {
        super.reloadConfig();
        saveDefaultConfig();
        FileConfiguration conf = getConfig();
        conf.options().copyDefaults(true);
        saveConfig();
        setForbidden(conf);
        setTypes(conf);
    }

    // Listener methods ------------------------------------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    private void onChunkUnload(ChunkUnloadEvent e) {
        for (Entity mob : e.getChunk().getEntities()) if (mob instanceof Tameable) TP2Owner((Tameable) mob);
    }

    @EventHandler(ignoreCancelled = true)
    private void onEntityDamage(EntityDamageEvent e) {
        Entity pet = e.getEntity();
        UUID   id  = pet.getUniqueId();
        if (!taskRemove(id)) return;
        e.setCancelled(true);
        Player owner = getOwner((Tameable) pet);
        if (owner == null) return;
        if (owner.isOnGround()) pet.teleport(owner);
        else TP2BetterPlace(pet);
    }

    // Private methods -------------------------------------------------------------------------------------------------

    private Location findAllowedLoc(Location loc) {
        int o = loc.getBlockY();
        if (o > 5) for (int y = o; y > 5; y--) if (isAllowedLoc(loc, y)) return loc;
        for (int y = o; y < loc.getWorld().getMaxHeight(); y++) if (isAllowedLoc(loc, y)) return loc;
        return null;
    }

    private Location findBetterPlace(Location loc, int h, int w) {
        World o = loc.getWorld();
        int   x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        for (int i = 0; i < 2; i++) {
            Block block = o.getBlockAt(x, y + i, z);
            for (int j = 0; j < 3; j++) for (BlockFace f : cards) {
                Block b = block.getRelative(f, j);
                if (hasEnoughPlace(o, b, h, w)) return b.getLocation();
            }
        }
        return null;
    }

    private Player getOwner(Tameable pet) {
        AnimalTamer owner = pet.getOwner();
        if (owner == null) return null;
        Player player = Bukkit.getPlayer(owner.getUniqueId());
        return (player != null && player.isOnline()) ? player : null;
    }

    private boolean hasEnoughPlace(World o, Block b, int h, int w) {
        if (!b.isPassable()) return false;
        int bX = b.getX(), bY = b.getY(), bZ = b.getZ();
        for (int y = 0; y <= h; y++) for (int x = bX - w; x <= bX + w; x++) for (int z = bZ - w; z <= bZ + w; z++)
            if (!o.getBlockAt(x, bY + y, z).isPassable()) return false;
        return true;
    }

    private boolean isAllowedLoc(Location loc, int y) {
        loc.setY(y);
        return !forbidden.contains(loc.getBlock().getType());
    }

    private void setForbidden(FileConfiguration conf) {
        forbidden = new HashSet<Material>();
        List<String> fb = conf.getStringList("forbiddenBlocks");
        if (fb == null) return;
        fb.forEach(s -> {
            Material m = Material.getMaterial(s);
            if (m != null && m.isBlock()) forbidden.add(m);
            else Bukkit.getLogger().warning("Material « " + s + " » is not a valid block material... Ignored!");
        });
    }

    private void setTypes(FileConfiguration conf) {
        types = new HashSet<EntityType>();
        List<String> pt = conf.getStringList("petFollowTypes");
        if (pt == null) return;
        Class<EntityType> ec = EntityType.class;
        Class<Tameable>   tc = Tameable.class;
        pt.forEach(t -> {
            try {
                EntityType et = Enum.valueOf(ec, t);
                if (!Arrays.asList(et.getEntityClass().getInterfaces()).contains(tc)) throw new Exception();
                types.add(et);
            }
            catch (Exception e) {
                Bukkit.getLogger().warning("Type « " + t + " » is not a valid tamed pet type... Ignored!");
            }
        });
    }

    private void taskCreate(UUID id) {
        taskRemove(id);
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                taskRemove(id);
            }
        };
        recents.put(id, task);
        task.runTaskLater(this, 20);
    }

    private boolean taskRemove(UUID uid) {
        BukkitRunnable task = recents.remove(uid);
        if (task == null) return false;
        task.cancel();
        return true;
    }

    private void TP2BetterPlace(Entity pet) {
        Location loc = findBetterPlace(pet.getLocation(),
                (int) Math.ceil(pet.getHeight()), (int) Math.ceil(pet.getWidth()));
        if (loc == null) return;
        taskCreate(pet.getUniqueId());
        pet.teleport(loc);
    }

    private void TP2Owner(Tameable pet) {
        if (!pet.isTamed() || !types.contains(pet.getType())) return;
        if (pet instanceof Sittable && ((Sittable) pet).isSitting()) return;
        Player owner = getOwner(pet);
        if (owner == null) return;
        if (owner.isOnGround()) pet.teleport(owner);
        else TP2Safe(pet, owner.getLocation());
    }

    private void TP2Safe(Entity pet, Location loc) {
        loc = findAllowedLoc(loc);
        if (loc == null) return;
        loc.add(0, 1, 0);
        taskCreate(pet.getUniqueId());
        pet.teleport(loc);
    }
}
