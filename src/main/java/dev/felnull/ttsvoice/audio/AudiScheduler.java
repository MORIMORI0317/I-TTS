package dev.felnull.ttsvoice.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import dev.felnull.fnjl.util.FNMath;
import dev.felnull.ttsvoice.Main;
import dev.felnull.ttsvoice.audio.loader.VoiceLoaderManager;
import dev.felnull.ttsvoice.audio.player.VoiceTrackLoader;
import dev.felnull.ttsvoice.tts.BotAndGuild;
import dev.felnull.ttsvoice.tts.TTSManager;
import dev.felnull.ttsvoice.tts.TTSVoiceEntry;
import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.commons.lang3.tuple.Pair;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AudiScheduler extends AudioEventAdapter {
    private final int previsionLoadCount = Runtime.getRuntime().availableProcessors();
    private final ExecutorService executorService;
    private final Map<TTSVoiceEntry, CompletableFuture<Pair<VoiceTrackLoader, AudioTrack>>> previsionLoadTracks = new HashMap<>();
    private final Map<TTSVoiceEntry, VoiceTrackLoader> loaders = new HashMap<>();
    private final AudioPlayer player;
    private final BotAndGuild botAndGuild;
    private final Object nextLock = new Object();
    private final Object stopLock = new Object();
    private boolean loading;
    private Thread loadThread;
    private CoolDownThread coolDownThread;
    private VoiceTrackLoader currentTrackLoader;

    public AudiScheduler(AudioPlayer player, BotAndGuild bag) {
        this.player = player;
        this.player.addListener(this);
        var guild = bag.getGuild();
        guild.getAudioManager().setSendingHandler(new AudioPlayerSendHandler(player));
        this.botAndGuild = bag;
        this.executorService = Executors.newFixedThreadPool(previsionLoadCount, new BasicThreadFactory.Builder().namingPattern("voice-tack-loader-" + bag.guildId() + "-%d").daemon(true).build());
    }

    public void dispose() {
        executorService.shutdown();

        if (coolDownThread != null)
            coolDownThread.interrupt();

        if (loading && loadThread != null)
            loadThread.interrupt();

        if (currentTrackLoader != null)
            currentTrackLoader.end();

        synchronized (loaders) {
            for (VoiceTrackLoader value : loaders.values()) {
                value.end();
            }
        }

        player.destroy();

        var guild = botAndGuild.getGuild();
        guild.getAudioManager().setSendingHandler(null);
    }

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        if (currentTrackLoader != null)
            currentTrackLoader.end();
        currentTrackLoader = null;
        startCoolDown();
    }

    private void startCoolDown() {
        if (coolDownThread != null) {
            coolDownThread.interrupt();
        }
        coolDownThread = new CoolDownThread();
        coolDownThread.start();
    }

    public void play(AudioTrack track, float volume) {
        player.startTrack(track, false);
        player.setVolume((int) (100 * volume));
    }

    public boolean isLoadingOrPlaying() {
        return (coolDownThread != null && coolDownThread.isAlive()) || player.getPlayingTrack() != null || loading;
    }

    public void stop() {
        synchronized (stopLock) {
            if (loadThread != null) {
                loadThread.interrupt();
                loadThread = null;
                loading = false;
            }
            player.stopTrack();
            if (coolDownThread != null) {
                coolDownThread.interrupt();
                coolDownThread = null;
            }
        }
    }

    public boolean next() {
        synchronized (nextLock) {
            var vlm = VoiceLoaderManager.getInstance();
            var tm = TTSManager.getInstance();
            var queue = tm.getTTSQueue(botAndGuild);
            TTSVoiceEntry next;
            synchronized (queue) {
                next = queue.poll();
            }
            if (next == null) return false;
            loading = true;
            loadThread = Thread.currentThread();

            AudioTrack track;
            try {
                CompletableFuture<Pair<VoiceTrackLoader, AudioTrack>> loaded;
                synchronized (previsionLoadTracks) {
                    loaded = previsionLoadTracks.remove(next);
                }
                synchronized (loaders) {
                    loaders.remove(next);
                }

                if (loaded == null) {
                    var l = vlm.getTrackLoader(next.voice());
                    if (l != null)
                        loaded = l.loaded().thenApply(n -> Pair.of(l, n));
                }

                if (loaded == null) {
                    startCoolDown();
                    loading = false;
                    loadThread = null;
                    return true;
                }

                var lg = loaded.get();
                track = lg.getRight();

                if (currentTrackLoader != null)
                    currentTrackLoader.end();

                currentTrackLoader = lg.getLeft();

                if (!Main.getServerConfig(botAndGuild.guildId()).isOverwriteAloud()) {
                    synchronized (queue) {
                        List<TTSVoiceEntry> qc = queue.stream().filter(n -> !previsionLoadTracks.containsKey(n)).toList();
                        int lc = FNMath.clamp(qc.size(), 0, previsionLoadCount);

                        if (lc >= 1) {
                            for (int i = 0; i < lc; i++) {
                                var l = qc.get(i);
                                var ll = CompletableFuture.supplyAsync(() -> vlm.getTrackLoader(l.voice()), executorService).thenApplyAsync(n -> {
                                    try {
                                        synchronized (loaders) {
                                            loaders.put(l, n);
                                        }
                                        return Pair.of(n, n.loaded().get());
                                    } catch (InterruptedException | ExecutionException e) {
                                        throw new RuntimeException(e);
                                    }
                                }, executorService);
                                synchronized (previsionLoadTracks) {
                                    previsionLoadTracks.put(l, ll);
                                }
                            }
                        }
                    }
                }

            } catch (Exception ex) {
                startCoolDown();
                loading = false;
                loadThread = null;
                return true;
            }
            play(track, next.voice().voiceType().getVolume());
            loading = false;
            loadThread = null;
            return true;
        }
    }

    private class CoolDownThread extends Thread {
        @Override
        public void run() {
            try {
                Thread.sleep(1000);
                next();
            } catch (InterruptedException ignored) {
            }
        }
    }
}
