package dev.felnull.itts.core.discord.command;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

public class ReconnectCommand extends BaseCommand {
    public ReconnectCommand() {
        super("reconnect");
    }

    @NotNull
    @Override
    public SlashCommandData createSlashCommand() {
        return Commands.slash("reconnect", "読み上げBOTをVCに再接続")
                .setGuildOnly(true)
                .setDefaultPermissions(MEMBERS_PERMISSIONS);
    }

    @Override
    public void commandInteraction(SlashCommandInteractionEvent event) {
        var audioManager = event.getGuild().getAudioManager();

        if (audioManager.isConnected()) {
            var connectedChannel = audioManager.getConnectedChannel();
            event.reply(connectedChannel.getAsMention() + "に再接続します。").queue();

            audioManager.closeAudioConnection();

            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                }

                getTTSManager().setReadAroundChannel(event.getGuild(), event.getChannel());
                audioManager.openAudioConnection(connectedChannel.asVoiceChannel());
            }, getAsyncExecutor());
        } else {
            event.reply("現在VCに接続していません。").setEphemeral(true).queue();
        }
    }
}