package com.kitbot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Settings + saved selections. Stored in .minecraft/config/kitbot.json (edit it, then run ".kb reload"). */
public class KitBotData {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ---- settings (defaults used when a key is missing) ----
    /** Chat command that requests a kit, followed by the kit name. */
    public String prefix = "!1kit";
    /** Regex for chat lines. Group 1 = player name, group 2 = message. Adjust to the server's chat format. */
    public String chatRegex = "^<(\\w{1,16})> (.+)$";
    public int cooldownMinutes = 60;
    public int killDelaySeconds = 5;
    public int tpaTimeoutSeconds = 60;
    /** Teleport request command, without the slash. */
    public String tpaCommand = "tpa";
    public int maxQueue = 5;
    public boolean whisperReplies = true;
    /** Distance from the chest at which the bot stops walking and opens it. */
    public double chestReach = 4.0;
    /** Drop the kit once the requester is this close after teleporting. */
    public double dropRange = 4.0;

    // ---- saved state ----
    public Long pos1;
    public Long pos2;
    /** Kit name -> chest BlockPos (as long), set with the oak button. Overrides sign scanning. */
    public Map<String, Long> chests = new HashMap<>();
    /** Lowercase player name -> last delivery time (ms). */
    public Map<String, Long> cooldowns = new HashMap<>();

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("kitbot.json");
    }

    public static KitBotData load() {
        try {
            Path f = file();
            if (Files.exists(f)) {
                try (Reader r = Files.newBufferedReader(f)) {
                    KitBotData d = GSON.fromJson(r, KitBotData.class);
                    if (d != null) {
                        if (d.chests == null) d.chests = new HashMap<>();
                        if (d.cooldowns == null) d.cooldowns = new HashMap<>();
                        return d;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[KitBot] Could not read config: " + e);
        }
        KitBotData fresh = new KitBotData();
        fresh.save();
        return fresh;
    }

    public void save() {
        try (Writer w = Files.newBufferedWriter(file())) {
            GSON.toJson(this, w);
        } catch (Exception e) {
            System.err.println("[KitBot] Could not save config: " + e);
        }
    }
}
