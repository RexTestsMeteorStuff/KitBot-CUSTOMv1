package com.kitbot;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Chat-triggered kit bot.
 * "!1kit pvp" -> walk to chest -> take first stack -> /tpa requester -> drop -> wait -> /kill -> respawn -> repeat.
 * Controlled with local ".kb" chat commands (see README).
 */
public final class KitBot {
    public static final KitBot INSTANCE = new KitBot();

    private enum State { IDLE, WALK, OPEN, WAIT_SCREEN, TRANSFER, SEND_TPA, WAIT_TP, DROP, WAIT_KILL, KILL_SENT, DEAD, RESPAWN_WAIT }

    private enum SelectMode {
        OFF(null), REGION(null), PVP(Kit.PVP), TOTEMS(Kit.TOTEMS), OBSIDIAN(Kit.OBSIDIAN), RANDOM(Kit.RANDOM);

        final Kit kit;

        SelectMode(Kit kit) {
            this.kit = kit;
        }
    }

    private static class Job {
        final String player;
        final Kit kit;
        BlockPos chest;
        boolean deposit, clicked;
        int openAttempts;

        Job(String player, Kit kit) {
            this.player = player;
            this.kit = kit;
        }
    }

    private final Minecraft mc = Minecraft.getInstance();
    private final Queue<Job> queue = new ConcurrentLinkedQueue<>();

    private KitBotData data = KitBotData.load();
    private boolean enabled;
    private SelectMode mode = SelectMode.OFF;
    private State state = State.IDLE;
    private Job job;
    private int ticks;       // ticks in the current state
    private int globalTicks; // ever-increasing, for effects

    private Pattern cachedPattern;
    private String cachedRegex;

    private KitBot() {}

    private void setState(State s) {
        state = s;
        ticks = 0;
    }

    private void reset() {
        queue.clear();
        job = null;
        setState(State.IDLE);
        stopWalking();
    }

    // ------------------------------------------------------------ local commands (.kb ...)

    public boolean handleLocalCommand(String msg) {
        String m = msg.trim();
        String lower = m.toLowerCase();
        if (!lower.equals(".kb") && !lower.startsWith(".kb ")) return false;

        String[] a = m.split("\\s+");
        String sub = a.length > 1 ? a[1].toLowerCase() : "status";
        switch (sub) {
            case "on" -> {
                data = KitBotData.load();
                reset();
                enabled = true;
                local("Enabled. Waiting for " + data.prefix + " <kit> requests.");
            }
            case "off" -> {
                enabled = false;
                reset();
                local("Disabled.");
            }
            case "reload" -> {
                data = KitBotData.load();
                local("Config reloaded.");
            }
            case "mode" -> {
                if (a.length < 3) {
                    local("Usage: .kb mode <off|region|pvp|totems|obsidian|random>");
                } else {
                    try {
                        mode = SelectMode.valueOf(a[2].toUpperCase());
                        local("Select mode: " + mode.name().toLowerCase() + (mode == SelectMode.REGION
                            ? " (hold an oak button: left click = corner 1, right click = corner 2)"
                            : mode.kit != null ? " (hold an oak button and click the " + mode.kit.display() + " chest)" : ""));
                    } catch (IllegalArgumentException e) {
                        local("Unknown mode. Use off, region, pvp, totems, obsidian or random.");
                    }
                }
            }
            case "clear" -> {
                data.pos1 = null;
                data.pos2 = null;
                data.chests.clear();
                data.save();
                local("Selections cleared.");
            }
            case "resetcd" -> {
                if (a.length < 3) {
                    local("Usage: .kb resetcd <player>");
                } else {
                    data.cooldowns.remove(a[2].toLowerCase());
                    data.save();
                    local("Cooldown reset for " + a[2]);
                }
            }
            default -> {
                local("Bot " + (enabled ? "ON" : "OFF") + ", state " + state + ", queue " + queue.size()
                    + ", select mode " + mode.name().toLowerCase());
                local("Region: " + (data.pos1 != null ? BlockPos.of(data.pos1).toShortString() : "-")
                    + " to " + (data.pos2 != null ? BlockPos.of(data.pos2).toShortString() : "-"));
                for (Kit k : Kit.values()) {
                    Long c = data.chests.get(k.name());
                    local(k.display() + " chest: " + (c != null ? BlockPos.of(c).toShortString() : "from signs"));
                }
                local("Commands: .kb on | off | reload | mode <..> | clear | resetcd <player> | status");
            }
        }
        return true;
    }

    private void local(String text) {
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[KitBot] " + text), false);
    }

    // ------------------------------------------------------------ chat requests

    private Pattern pattern() {
        if (!data.chatRegex.equals(cachedRegex)) {
            try {
                cachedPattern = Pattern.compile(data.chatRegex);
            } catch (PatternSyntaxException e) {
                cachedPattern = null;
            }
            cachedRegex = data.chatRegex;
        }
        return cachedPattern;
    }

    public void onChat(String raw) {
        if (!enabled || mc.player == null || mc.getConnection() == null) return;
        Pattern p = pattern();
        if (p == null) return;

        Matcher m = p.matcher(raw);
        if (!m.matches() || m.groupCount() < 2) return;

        String name = m.group(1);
        String text = m.group(2).trim();
        String pre = data.prefix.trim();
        if (pre.isEmpty() || !text.regionMatches(true, 0, pre, 0, pre.length())) return;

        if (name.equalsIgnoreCase(mc.player.getName().getString())) return;
        // Only serve players that are actually online (tab list).
        if (mc.getConnection().getPlayerInfo(name) == null) return;

        Kit kit = Kit.parse(text.substring(pre.length()).trim());
        if (kit == null) {
            whisper(name, "Usage: " + pre + " pvp | totems | obsidian | random");
            return;
        }

        long now = System.currentTimeMillis();
        long cd = data.cooldownMinutes * 60_000L;
        Long last = data.cooldowns.get(name.toLowerCase());
        if (last != null && now - last < cd) {
            whisper(name, "Kit cooldown: " + ((cd - (now - last)) / 60_000L + 1) + " min left.");
            return;
        }

        boolean already = (job != null && job.player.equalsIgnoreCase(name))
            || queue.stream().anyMatch(j -> j.player.equalsIgnoreCase(name));
        if (already) return;

        if (queue.size() >= data.maxQueue) {
            whisper(name, "Queue is full, try again soon.");
            return;
        }

        queue.add(new Job(name, kit));
        whisper(name, "Queued " + kit.display() + " kit (#" + (queue.size() + (job != null ? 1 : 0)) + "). Accept my TPA when it arrives.");
    }

    private void whisper(String player, String msg) {
        if (data.whisperReplies && mc.getConnection() != null) mc.getConnection().sendCommand("w " + player + " " + msg);
    }

    private void sendCommand(String cmd) {
        if (mc.getConnection() != null) mc.getConnection().sendCommand(cmd);
    }

    // ------------------------------------------------------------ oak button selection

    private boolean holdingWand() {
        return mc.player != null && mc.player.getMainHandItem().is(Items.OAK_BUTTON) && mode != SelectMode.OFF;
    }

    /** Left click. Returns true to cancel the click. */
    public boolean onAttackBlock(Level level, BlockPos pos) {
        if (!level.isClientSide() || !holdingWand()) return false;
        select(level, pos, true);
        return true;
    }

    /** Right click. Returns true to cancel the click. */
    public boolean onUseBlock(Level level, BlockPos pos) {
        if (!level.isClientSide() || !holdingWand()) return false;
        select(level, pos, false);
        return true;
    }

    private void select(Level level, BlockPos pos, boolean left) {
        if (mode == SelectMode.REGION) {
            if (left) data.pos1 = pos.asLong();
            else data.pos2 = pos.asLong();
            data.save();
            local((left ? "Corner 1" : "Corner 2") + " set to " + pos.toShortString());
        } else if (mode.kit != null) {
            if (!(level.getBlockState(pos).getBlock() instanceof ChestBlock)) {
                local("That block is not a chest.");
                return;
            }
            data.chests.put(mode.kit.name(), pos.asLong());
            data.save();
            local(mode.kit.display() + " chest bound to " + pos.toShortString());
        }
    }

    /** Particle outline of the region and the bound chests, shown while holding the oak button. */
    private void highlight() {
        if (!holdingWand() || globalTicks % 8 != 0 || mc.level == null) return;

        if (data.pos1 != null || data.pos2 != null) {
            BlockPos a = BlockPos.of(data.pos1 != null ? data.pos1 : data.pos2);
            BlockPos b = BlockPos.of(data.pos2 != null ? data.pos2 : data.pos1);
            double x1 = Math.min(a.getX(), b.getX()), x2 = Math.max(a.getX(), b.getX()) + 1;
            double y1 = Math.min(a.getY(), b.getY()), y2 = Math.max(a.getY(), b.getY()) + 1;
            double z1 = Math.min(a.getZ(), b.getZ()), z2 = Math.max(a.getZ(), b.getZ()) + 1;
            for (double x : new double[]{x1, x2}) for (double y : new double[]{y1, y2}) edge(x, y, z1, x, y, z2);
            for (double x : new double[]{x1, x2}) for (double z : new double[]{z1, z2}) edge(x, y1, z, x, y2, z);
            for (double y : new double[]{y1, y2}) for (double z : new double[]{z1, z2}) edge(x1, y, z, x2, y, z);
        }
        for (Long l : data.chests.values()) {
            BlockPos c = BlockPos.of(l);
            mc.level.addParticle(ParticleTypes.HAPPY_VILLAGER, c.getX() + 0.5, c.getY() + 1.3, c.getZ() + 0.5, 0, 0, 0);
        }
    }

    private void edge(double x1, double y1, double z1, double x2, double y2, double z2) {
        int n = (int) Math.min(60, Math.max(1, Math.max(Math.abs(x2 - x1), Math.max(Math.abs(y2 - y1), Math.abs(z2 - z1)))));
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            mc.level.addParticle(ParticleTypes.END_ROD, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, z1 + (z2 - z1) * t, 0, 0, 0);
        }
    }

    // ------------------------------------------------------------ locating chests

    private BlockPos findChest(Kit kit) {
        Long bound = data.chests.get(kit.name());
        if (bound != null) return BlockPos.of(bound);
        if (data.pos1 == null || data.pos2 == null) return null;

        BlockPos a = BlockPos.of(data.pos1), b = BlockPos.of(data.pos2);
        long volume = (long) (Math.abs(a.getX() - b.getX()) + 1) * (Math.abs(a.getY() - b.getY()) + 1) * (Math.abs(a.getZ() - b.getZ()) + 1);
        if (volume > 500_000) return null;

        for (BlockPos p : BlockPos.betweenClosed(a, b)) {
            if (mc.level.getBlockEntity(p) instanceof SignBlockEntity sign && signMatches(sign, kit)) {
                BlockPos chest = chestBehind(p.immutable());
                if (chest != null) return chest;
            }
        }
        return null;
    }

    private boolean signMatches(SignBlockEntity sign, Kit kit) {
        for (int i = 0; i < 4; i++) {
            if (kit.matches(sign.getFrontText().getMessage(i, false).getString())) return true;
        }
        return false;
    }

    private BlockPos chestBehind(BlockPos signPos) {
        BlockState st = mc.level.getBlockState(signPos);
        if (st.getBlock() instanceof WallSignBlock) {
            BlockPos attached = signPos.relative(st.getValue(WallSignBlock.FACING).getOpposite());
            if (mc.level.getBlockState(attached).getBlock() instanceof ChestBlock) return attached;
        }
        for (Direction d : Direction.values()) {
            BlockPos n = signPos.relative(d);
            if (mc.level.getBlockState(n).getBlock() instanceof ChestBlock) return n;
        }
        return null;
    }

    // ------------------------------------------------------------ movement (simple straight-line walker)

    private void faceYaw(Vec3 target) {
        Vec3 eye = mc.player.getEyePosition();
        mc.player.setYRot((float) (Math.toDegrees(Math.atan2(target.z - eye.z, target.x - eye.x)) - 90.0));
    }

    private void facePoint(Vec3 target) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        mc.player.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        mc.player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
    }

    private void walkToward(Vec3 target) {
        faceYaw(target);
        mc.options.keyUp.setDown(true);
        mc.options.keyJump.setDown(mc.player.horizontalCollision && mc.player.onGround());
    }

    private void stopWalking() {
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
    }

    private double distanceTo(BlockPos pos) {
        return mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
    }

    // ------------------------------------------------------------ inventory helpers

    private int firstLootSlot() {
        for (int i = 9; i <= 44; i++) {
            var slot = mc.player.inventoryMenu.getSlot(i);
            if (slot.hasItem() && !slot.getItem().is(Items.OAK_BUTTON)) return i;
        }
        return -1;
    }

    private boolean hasLoot() {
        return firstLootSlot() != -1;
    }

    private void click(int containerId, int slot, int button, ClickType type) {
        mc.gameMode.handleInventoryMouseClick(containerId, slot, button, type, mc.player);
    }

    private Player findPlayer(String name) {
        for (Player p : mc.level.players()) {
            if (p != mc.player && p.getName().getString().equalsIgnoreCase(name)) return p;
        }
        return null;
    }

    private void fail(String reason) {
        stopWalking();
        if (job == null) {
            setState(State.IDLE);
            return;
        }
        whisper(job.player, reason);
        local("Request from " + job.player + " failed: " + reason);
        if (mc.player.containerMenu.containerId != 0) mc.player.closeContainer();

        if (!job.deposit && hasLoot()) {
            // Put the kit back in the chest so it isn't lost.
            job.deposit = true;
            job.clicked = false;
            job.openAttempts = 0;
            setState(State.WALK);
        } else {
            job = null;
            setState(State.IDLE);
        }
    }

    // ------------------------------------------------------------ main loop

    public void tick() {
        globalTicks++;
        if (mc.player == null || mc.level == null) {
            if (state != State.IDLE || job != null) reset();
            return;
        }
        highlight();
        if (!enabled) return;
        ticks++;

        if (mc.player.isDeadOrDying()) {
            stopWalking();
            if (state != State.DEAD) {
                job = null;
                setState(State.DEAD);
            }
            if (ticks % 20 == 5) mc.player.respawn();
            return;
        }

        switch (state) {
            case IDLE -> {
                if (ticks < 20) return;
                Job next = queue.poll();
                if (next == null) return;

                BlockPos chest = findChest(next.kit);
                if (chest == null) {
                    whisper(next.player, next.kit.display() + " kit is unavailable right now.");
                    local("No chest found for " + next.kit.display() + " (check signs / region / bound chests).");
                    return;
                }
                job = next;
                job.chest = chest;
                setState(State.WALK);
            }

            case WALK -> {
                if (distanceTo(job.chest) <= data.chestReach) {
                    stopWalking();
                    setState(State.OPEN);
                    return;
                }
                walkToward(Vec3.atCenterOf(job.chest));
                if (ticks > 20 * 45) fail("Could not reach the kit chest.");
            }

            case OPEN -> {
                Vec3 c = Vec3.atCenterOf(job.chest);
                facePoint(c);
                if (ticks < 5) return; // let the look direction reach the server
                job.openAttempts++;
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(c, Direction.UP, job.chest, false));
                setState(State.WAIT_SCREEN);
            }

            case WAIT_SCREEN -> {
                AbstractContainerMenu menu = mc.player.containerMenu;
                if (menu instanceof ChestMenu && menu.containerId != 0) {
                    if (ticks >= 4) setState(State.TRANSFER); // wait for the contents to sync
                } else if (ticks > 40) {
                    if (job.openAttempts >= 3) fail("Could not open the kit chest.");
                    else setState(State.OPEN);
                }
            }

            case TRANSFER -> {
                if (!(mc.player.containerMenu instanceof ChestMenu menu) || menu.containerId == 0) {
                    fail("Chest closed unexpectedly.");
                    return;
                }
                int chestSlots = menu.getRowCount() * 9;

                if (job.deposit) {
                    // Return everything to the chest, one slot per tick.
                    for (int i = chestSlots; i < menu.slots.size(); i++) {
                        var s = menu.getSlot(i);
                        if (s.hasItem() && !s.getItem().is(Items.OAK_BUTTON)) {
                            click(menu.containerId, i, 0, ClickType.QUICK_MOVE);
                            return;
                        }
                    }
                    mc.player.closeContainer();
                    job = null;
                    setState(State.IDLE);
                    return;
                }

                if (!job.clicked) {
                    // Take the first item stack (one shulker box / one stack).
                    for (int i = 0; i < chestSlots; i++) {
                        if (menu.getSlot(i).hasItem()) {
                            click(menu.containerId, i, 0, ClickType.QUICK_MOVE);
                            job.clicked = true;
                            ticks = 0;
                            return;
                        }
                    }
                    mc.player.closeContainer();
                    fail(job.kit.display() + " kit is out of stock.");
                } else if (ticks >= 6) {
                    mc.player.closeContainer();
                    if (hasLoot()) setState(State.SEND_TPA);
                    else fail("Could not take the kit.");
                }
            }

            case SEND_TPA -> {
                if (ticks < 5) return;
                sendCommand(data.tpaCommand + " " + job.player);
                whisper(job.player, "Sent you a TPA with your " + job.kit.display() + " kit. Please accept it.");
                setState(State.WAIT_TP);
            }

            case WAIT_TP -> {
                Player target = findPlayer(job.player);
                if (target != null && mc.player.distanceTo(target) <= data.dropRange) {
                    setState(State.DROP);
                    return;
                }
                if (ticks > data.tpaTimeoutSeconds * 20) fail("TPA was not accepted in time.");
            }

            case DROP -> {
                if (ticks < 20) return; // let the teleport settle
                int slot = firstLootSlot();
                if (slot != -1) {
                    click(0, slot, 1, ClickType.THROW); // button 1 = drop the whole stack
                    return;
                }
                data.cooldowns.put(job.player.toLowerCase(), System.currentTimeMillis());
                data.save();
                local("Delivered " + job.kit.display() + " kit to " + job.player);
                setState(State.WAIT_KILL);
            }

            case WAIT_KILL -> {
                if (ticks >= data.killDelaySeconds * 20) {
                    sendCommand("kill");
                    job = null;
                    setState(State.KILL_SENT);
                }
            }

            case KILL_SENT -> {
                // Still alive 5 seconds after /kill: it didn't work, carry on anyway.
                if (ticks > 100) {
                    local("/kill did not kill the bot; continuing.");
                    setState(State.IDLE);
                }
            }

            case DEAD -> setState(State.RESPAWN_WAIT); // alive again after respawn

            case RESPAWN_WAIT -> {
                if (ticks >= 60) setState(State.IDLE);
            }
        }
    }
}
