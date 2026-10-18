package com.kitbot;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;

public class KitBotMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        KitBot bot = KitBot.INSTANCE;

        ClientTickEvents.END_CLIENT_TICK.register(client -> bot.tick());

        // Servers like 6b6t usually deliver chat as "game" messages; signed chat arrives via CHAT.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) bot.onChat(message.getString());
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> bot.onChat(message.getString()));

        // ".kb ..." commands typed in chat are handled locally and never sent to the server.
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> !bot.handleLocalCommand(message));

        // Oak button selection tool.
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
            bot.onAttackBlock(level, pos) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseBlockCallback.EVENT.register((player, level, hand, hit) ->
            hand == InteractionHand.MAIN_HAND && bot.onUseBlock(level, hit.getBlockPos()) ? InteractionResult.FAIL : InteractionResult.PASS);
    }
}
