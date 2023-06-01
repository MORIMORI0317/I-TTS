package dev.felnull.itts.core.tts;

import dev.felnull.itts.core.ITTSRuntimeUse;
import dev.felnull.itts.core.tts.saidtext.SaidText;
import dev.felnull.itts.core.util.TTSUtils;
import dev.felnull.itts.core.voice.Voice;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

public class TTSManager implements ITTSRuntimeUse {
    private final Map<Long, TTSInstance> instances = new ConcurrentHashMap<>();

    public int getTTSCount() {
        return instances.size();
    }

    public void setReadAroundChannel(@NotNull Guild guild, @NotNull MessageChannel textChannel) {
        long guildId = guild.getIdLong();
        var data = getSaveDataManager().getBotStateData(guildId);
        data.setReadAroundTextChannel(textChannel.getIdLong());
    }

    public void connect(@NotNull Guild guild, @NotNull AudioChannel audioChannel) {
        long guildId = guild.getIdLong();
        long channelId = audioChannel.getIdLong();

        var pre = getTTSInstance(guildId);

        if (pre != null) {
            if (pre.getAudioChannel() == channelId)
                return;
            disconnect(guild);
        }

        var data = getSaveDataManager().getBotStateData(guildId);
        var serverData = getSaveDataManager().getServerData(guildId);
        instances.put(guildId, new TTSInstance(guild, channelId, data.getReadAroundTextChannel(), serverData.isOverwriteAloud()));
        data.setConnectedAudioChannel(channelId);
    }

    public void disconnect(@NotNull Guild guild) {
        long guildId = guild.getIdLong();

        var instance = getTTSInstance(guildId);
        if (instance == null)
            return;

        instance.dispose();
        instances.remove(guildId);

        var data = getSaveDataManager().getBotStateData(guildId);
        data.setConnectedAudioChannel(-1);
    }

    public void reload(@NotNull Guild guild) {
        long guildId = guild.getIdLong();

        var instance = getTTSInstance(guildId);
        if (instance == null)
            return;

        disconnect(guild);

        var rc = guild.getChannelById(AudioChannel.class, instance.getAudioChannel());
        if (rc != null)
            connect(guild, rc);
    }

    @Nullable
    public TTSInstance getTTSInstance(long guildId) {
        return instances.get(guildId);
    }

    public void sayChat(@NotNull Guild guild, @NotNull MessageChannel messageChannel, @Nullable Member member, @NotNull Message message) {
        var sm = getSaveDataManager();
        String ignoreRegex = sm.getServerData(guild.getIdLong()).getIgnoreRegex();
        if (ignoreRegex != null) {
            Pattern ignorePattern = Pattern.compile(ignoreRegex);
            if (ignorePattern.matcher(message.getContentDisplay()).matches())
                return;
        }

        sayGuildMemberText(guild, messageChannel, member, voice -> SaidText.message(voice, message));
    }

    public void sayUploadFile(@NotNull Guild guild, @NotNull MessageChannel messageChannel, @Nullable Member member, @NotNull List<Message.Attachment> attachments) {
        if (attachments.isEmpty()) return;

        sayGuildMemberText(guild, messageChannel, member, voice -> SaidText.fileUpload(voice, attachments));
    }

    public void sayGuildMemberText(@NotNull Guild guild, @NotNull MessageChannel messageChannel, @Nullable Member member, @NotNull Function<Voice, SaidText> saidTextFactory) {
        if (!canSpeak(guild)) return;

        long guildId = guild.getIdLong();
        long textChannelId = messageChannel.getIdLong();

        if (member == null) return;

        var user = member.getUser();
        if (user.isBot() || user.isSystem()) return;

        long userId = user.getIdLong();

        var ti = getTTSInstance(guildId);
        if (ti == null || ti.getTextChannel() != textChannelId) return;

        var sm = getSaveDataManager();
        if (sm.getServerData(guildId).isNeedJoin()) {
            var vs = member.getVoiceState();
            if (vs == null) return;

            var vc = vs.getChannel();
            if (vc == null || vc.getIdLong() != ti.getAudioChannel()) return;
        }

        var vt = getVoiceManager().getVoiceType(guildId, userId);
        if (vt == null) return;

        ti.sayText(saidTextFactory.apply(vt.createVoice(guildId, userId)));
    }

    public void sayText(@NotNull Guild guild, @NotNull SaidText saidText) {
        if (!canSpeak(guild)) return;

        long guildId = guild.getIdLong();

        var ti = getTTSInstance(guildId);
        if (ti == null) return;

        ti.sayText(saidText);
    }

    public boolean canSpeak(@NotNull Guild guild) {
        long guildId = guild.getIdLong();

        var ti = getTTSInstance(guildId);
        if (ti == null) return false;

        AudioChannel audioChannel = guild.getChannelById(AudioChannel.class, ti.getAudioChannel());
        if (audioChannel == null) return false;

        return guild.getVoiceStates().stream().
                filter(it -> it.getChannel() != null && it.getChannel().getIdLong() == audioChannel.getIdLong())
                .anyMatch(TTSUtils::canListen);
    }

    public void onVCEvent(@NotNull Guild guild, @NotNull Member member, @Nullable AudioChannelUnion join, @Nullable AudioChannelUnion left) {
        long guildId = guild.getIdLong();
        var user = member.getUser();
        long userId = user.getIdLong();

        var ti = getTTSInstance(guildId);
        if (ti == null || !((join != null && ti.getAudioChannel() == join.getIdLong()) || (left != null && ti.getAudioChannel() == left.getIdLong())))
            return;

        var sm = getSaveDataManager();
        if (!sm.getServerData(guildId).isNotifyMove()) return;

        var vt = getVoiceManager().getVoiceType(guildId, userId);
        if (vt == null) return;

        if (join != null && join.getIdLong() == ti.getAudioChannel()) {
            var vcs = member.getVoiceState();
            if (vcs != null && vcs.isGuildMuted())
                return;
        }

        VCEventType vce = null;

        if (join != null && left == null) {
            vce = VCEventType.JOIN;
        } else if (join == null) {
            vce = VCEventType.LEAVE;
        } else if (join.getIdLong() == ti.getAudioChannel() && left.getIdLong() != ti.getAudioChannel()) {
            vce = VCEventType.MOVE_FROM;
        } else if (join.getIdLong() != ti.getAudioChannel() && left.getIdLong() == ti.getAudioChannel()) {
            vce = VCEventType.MOVE_TO;
        }

        if (canSpeak(guild))
            sayVCEvent(vce, ti, vt.createVoice(guildId, userId), member, join, left);
    }

    private void sayVCEvent(VCEventType vcEventType, TTSInstance ttsInstance, Voice voice, Member member, AudioChannelUnion join, AudioChannelUnion left) {
        if (vcEventType == null) return;

        ttsInstance.sayText(SaidText.vcEvent(voice, vcEventType, member, join, left));
    }
}